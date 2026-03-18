package com.wayrecall.tracker.network

import zio.*
import zio.stream.*
import zio.json.*
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.protocol.{ProtocolParser, MultiProtocolParser}
import com.wayrecall.tracker.service.GpsProcessingService
import java.net.InetSocketAddress
import java.time.Instant

/**
 * Состояние соединения — immutable (неизменяемый объект)
 * 
 * Хранит ВСЮ информацию о TCP-соединении в памяти:
 * - IMEI устройства (после аутентификации)
 * - VehicleId из базы данных
 * - DeviceData — кэш контекста с TTL (НЕ ходим в Redis на каждый пакет!)
 * - Определённый протокол (MultiProtocolParser кэширует результат)
 * - Последняя позиция (in-memory, без Redis HMSET)
 * - Время подключения (для расчёта длительности сессии)
 *
 * КРИТИЧНО: DeviceData кэшируется при подключении на contextCacheTtlMs (1 час).
 * Инвалидация происходит через Redis Pub/Sub (device-config-changed).
 * Результат: 864M HGETALL/день → ~10K/день (86,400x сокращение).
 * 
 * @param imei IMEI устройства (None до аутентификации)
 * @param vehicleId ID транспортного средства в системе
 * @param connectedAt Unix timestamp подключения в миллисекундах
 * @param lastPosition Последняя обработанная позиция (in-memory, заменяет Redis HMSET)
 * @param isUnknownDevice Трекер не зарегистрирован, но мы принимаем данные
 * @param deviceData Кэш данных устройства из Redis HASH device:{imei}
 * @param detectedProtocol Определённый протокол (кэшируется после первого пакета)
 * @param contextCachedAt Время кэширования контекста (для TTL проверки)
 * @param contextCacheTtlMs TTL кэша контекста в миллисекундах (по умолчанию 1 час)
 */
final case class ConnectionState(
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    connectedAt: Long = 0L,
    lastPosition: Option[GpsPoint] = None,
    isUnknownDevice: Boolean = false,
    deviceData: Option[DeviceData] = None,
    detectedProtocol: Option[Protocol] = None,
    contextCachedAt: Long = 0L,
    contextCacheTtlMs: Long = 3_600_000L   // 1 час по умолчанию
):
  /** Устанавливает IMEI и vehicleId после успешной аутентификации */
  def withImei(newImei: String, vid: Long, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = Some(vid), connectedAt = timestamp)
  
  /** Устанавливает IMEI, vehicleId + полные данные из Redis HASH + кэш */
  def withImeiAndDeviceData(newImei: String, vid: Long, timestamp: Long, data: DeviceData): ConnectionState =
    copy(
      imei = Some(newImei),
      vehicleId = Some(vid),
      connectedAt = timestamp,
      deviceData = Some(data),
      contextCachedAt = timestamp,
      lastPosition = data.previousPosition
    )
  
  /** Устанавливает IMEI для незарегистрированного трекера (без vehicleId) */
  def withUnknownImei(newImei: String, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = None, connectedAt = timestamp, isUnknownDevice = true)
  
  /** Обновляет DeviceData (при получении device-events или invalidation) */
  def withUpdatedDeviceData(data: DeviceData, now: Long): ConnectionState =
    copy(deviceData = Some(data), contextCachedAt = now)
  
  /** Инвалидирует кэш контекста (по Pub/Sub уведомлению) */
  def invalidateContext: ConnectionState =
    copy(contextCachedAt = 0L)
  
  /** Обновляет последнюю позицию (in-memory, без Redis HMSET!) */
  def withLastPosition(point: GpsPoint): ConnectionState =
    copy(lastPosition = Some(point))
  
  /** Устанавливает определённый протокол (кэш MultiProtocolParser) */
  def withDetectedProtocol(protocol: Protocol): ConnectionState =
    copy(detectedProtocol = Some(protocol))
  
  /** Проверяет, истёк ли TTL кэша контекста */
  def isContextExpired(now: Long): Boolean =
    contextCachedAt == 0L || (now - contextCachedAt) > contextCacheTtlMs
  
  /** Получает предыдущую позицию для фильтрации (in-memory кэш) */
  def getPreviousPosition: Option[GpsPoint] =
    lastPosition

/**
 * Сервис обработки GPS данных - чисто функциональный
 * 
 * ПЕРЕМЕЩЕНО в service/GpsProcessingService.scala (v5.0 refactor)
 * 
 * Контролирует полный цикл обработки:
 * - Аутентификацию по IMEI
 * - Парсинг и фильтрацию GPS-пакетов
 * - Публикацию в Kafka
 * - Статус подключения/отключения
 */
// trait GpsProcessingService — see service/GpsProcessingService.scala

/**
 * Netty ChannelHandler - минимальный мост между Netty и ZIO
 * 
 * Это "тонкий" адаптер, который:
 * - Получает TCP пакеты от Netty
 * - Делегирует обработку в GpsProcessingService (чистый ZIO)
 * - Отправляет ответы (ACK) обратно трекеру
 * 
 * Жизненный цикл соединения:
 * 1. channelActive() - TCP соединение установлено
 * 2. channelRead() с IMEI пакетом - аутентификация
 * 3. channelRead() с DATA пакетами - GPS точки
 * 4. channelInactive() - соединение закрыто
 * 
 * @param service GpsProcessingService для обработки данных
 * @param parser ProtocolParser для конкретного протокола
 * @param registry ConnectionRegistry для отслеживания соединений
 * @param runtime ZIO Runtime для выполнения эффектов
 */
class ConnectionHandler(
    service: GpsProcessingService,
    parser: ProtocolParser,
    registry: ConnectionRegistry,
    runtime: Runtime[Any]
) extends ChannelInboundHandlerAdapter:
  
  // ZIO Ref для иммутабельного состояния соединения
  // Создаётся сразу при инстанцировании handler'а
  private val stateRef: Ref[ConnectionState] = 
    Unsafe.unsafe(implicit u => runtime.unsafe.run(Ref.make(ConnectionState())).getOrThrow())
  
  // Семафор для последовательной обработки пакетов одного соединения.
  // Гарантирует: IMEI пакет полностью обработан ДО первого DATA пакета.
  // Без этого fork() мог бы создать гонку между аутентификацией и обработкой данных.
  private val processingPermit: Semaphore =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(Semaphore.make(1)).getOrThrow())
  
  /**
   * Вызывается Netty при получении данных
   * 
   * Определяет тип пакета по текущему состоянию:
   * - Если IMEI ещё не установлен → это IMEI пакет (аутентификация)
   * - Если IMEI установлен + isUnknownDevice → GPS данные → unknown-gps-events
   * - Если IMEI установлен + vehicleId → GPS данные → gps-events
   * 
   * Обработка запускается асинхронно (forkEffect) — Netty I/O поток не блокируется.
   * Семафор processingPermit гарантирует последовательность пакетов одного соединения.
   */
  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit =
    msg match
      case buffer: ByteBuf =>
        val effect = for
          state <- stateRef.get
          _ <- state.imei match
            case None => 
              // Первый пакет - аутентификация по IMEI
              handleImeiPacket(ctx, buffer)
            case Some(_) if state.isUnknownDevice =>
              // Незарегистрированный трекер — данные → unknown-gps-events
              handleUnknownDataPacket(ctx, buffer, state)
            case Some(_) => 
              // Зарегистрированный трекер — данные → gps-events
              handleDataPacket(ctx, buffer, state)
        yield ()
        
        // Асинхронная обработка: НЕ блокируем Netty I/O поток!
        // Семафор гарантирует последовательность пакетов одного соединения (FIFO).
        // ByteBuf освобождается после обработки в fiber (refCnt=1 до release).
        forkEffect(
          processingPermit.withPermit(effect)
            .ensuring(ZIO.succeed(buffer.release()))
        )
      case _ =>
        // Неизвестный тип сообщения - передаём дальше по pipeline
        ctx.fireChannelRead(msg)
  
  /**
   * Обрабатывает первый пакет с IMEI (аутентификация)
   * 
   * Алгоритм:
   * 1. Парсим IMEI и получаем vehicleId из Redis
   * 2. Сохраняем в состояние соединения
   * 3. Регистрируем в ConnectionRegistry
   * 4. Отправляем ACK трекеру
   * 
   * Если IMEI неизвестен:
   * - Публикуем UnknownDeviceEvent в unknown-devices
   * - Отправляем ACK (принимаем соединение!)
   * - Сохраняем IMEI в состояние с флагом isUnknownDevice
   * - Продолжаем принимать GPS данные → unknown-gps-events
   */
  private def handleImeiPacket(ctx: ChannelHandlerContext, buffer: ByteBuf): UIO[Unit] =
    val remoteAddr = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
    val ip = remoteAddr.getAddress.getHostAddress
    
    val effect = for
      _ <- ZIO.logDebug(s"[HANDLER] Получен IMEI пакет от $ip, размер=${buffer.readableBytes()} байт")
      
      // Вызываем сервис для аутентификации (HGETALL device:{imei} — один запрос)
      result <- service.processImeiPacket(buffer, remoteAddr, parser)
      (imei, vehicleId, prevPosition, deviceData) = result
      now <- ZIO.succeed(java.lang.System.currentTimeMillis())
      
      // Обновляем состояние соединения — сохраняем DeviceData из Redis HASH
      _ <- stateRef.update { state =>
        val updated = deviceData match
          case Some(dd) => state.withImeiAndDeviceData(imei, vehicleId, now, dd)
          case None     => state.withImei(imei, vehicleId, now)
        updated
      }
      _ <- ZIO.logDebug(s"[HANDLER] Состояние обновлено: IMEI=$imei, vehicleId=$vehicleId, deviceData=${deviceData.isDefined}")
      
      // Регистрируем подключение в реестре (для отправки команд и мониторинга)
      _ <- registry.register(imei, ctx, parser)
      _ <- ZIO.logDebug(s"[HANDLER] Соединение зарегистрировано в ConnectionRegistry")
      
      // Регистрируем подключение в Redis/Kafka (передаём protocolName для Redis HASH)
      _ <- service.onConnect(imei, vehicleId, remoteAddr, parser.protocolName)
      
      // Отправляем ACK (подтверждение успешной аутентификации)
      _ <- ZIO.succeed {
        val ack = parser.imeiAck(true)
        ctx.writeAndFlush(ack)
      }
      _ <- ZIO.logDebug(s"[HANDLER] Отправлен ACK для IMEI=$imei")
    yield ()
    
    // Обработка ошибок
    effect.catchAll { 
      case error: ProtocolError.UnknownDevice =>
        // Неизвестное устройство — НЕ закрываем соединение!
        // Принимаем ACK, сохраняем IMEI, продолжаем принимать GPS данные
        for
          now <- ZIO.succeed(java.lang.System.currentTimeMillis())
          _ <- ZIO.logWarning(s"[HANDLER] ⚠ Незарегистрированный трекер: IMEI=${error.imei}, адрес=$ip — принимаем данные")
          _ <- service.onUnknownDevice(error.imei, parser.protocolName, remoteAddr)
          
          // Сохраняем IMEI в состояние с флагом isUnknownDevice (без vehicleId)
          _ <- stateRef.update(_.withUnknownImei(error.imei, now))
          
          // Регистрируем в реестре (для мониторинга и idle timeout)
          _ <- registry.register(error.imei, ctx, parser)
          
          // Отправляем ACK — трекер продолжит слать данные
          _ <- ZIO.succeed {
            val ack = parser.imeiAck(true)
            ctx.writeAndFlush(ack)
          }
          _ <- ZIO.logInfo(s"[HANDLER] ✓ Незарегистрированный трекер IMEI=${error.imei} принят, данные → unknown-gps-events")
        yield ()
        
      case error: ProtocolError =>
        // Другие ошибки протокола (невалидный формат и т.д.)
        for
          _ <- ZIO.logWarning(s"[HANDLER] ✗ Ошибка аутентификации от $ip: ${error.message}")
          _ <- ZIO.succeed {
            val ack = parser.imeiAck(false)
            ctx.writeAndFlush(ack)
            ctx.close()
          }
        yield ()
        
      case error =>
        // Неожиданная ошибка
        for
          _ <- ZIO.logError(s"[HANDLER] ✗ Неожиданная ошибка аутентификации от $ip: ${error.getMessage}")
          _ <- ZIO.succeed {
            val ack = parser.imeiAck(false)
            ctx.writeAndFlush(ack)
            ctx.close()
          }
        yield ()
    }
  
  /**
   * Обрабатывает пакет с GPS данными
   * 
   * Алгоритм:
   * 1. Обновляем время последней активности (для idle timeout)
   * 2. Парсим и фильтруем точки через сервис
   * 3. Обновляем кэш последней позиции
   * 4. Отправляем ACK с количеством принятых точек
   */
  private def handleDataPacket(
    ctx: ChannelHandlerContext,
    buffer: ByteBuf,
    state: ConnectionState
  ): UIO[Unit] =
    val effect = for
      // Получаем IMEI и vehicleId из состояния
      imei <- ZIO.fromOption(state.imei)
                .orElseFail(new Exception("IMEI не установлен - внутренняя ошибка"))
      vehicleId <- ZIO.fromOption(state.vehicleId)
                     .orElseFail(new Exception("VehicleId не установлен - внутренняя ошибка"))
      
      _ <- ZIO.logDebug(s"[HANDLER] Получен DATA пакет от IMEI=$imei, размер=${buffer.readableBytes()} байт")
      
      // Обновляем время последней активности в реестре
      _ <- registry.updateLastActivity(imei)
      
      // Проверяем TTL контекста — обновляем из Redis если истёк (раз в час)
      now <- ZIO.succeed(java.lang.System.currentTimeMillis())
      currentState <- if state.isContextExpired(now) then
        for
          _ <- ZIO.logInfo(s"[HANDLER] IMEI=$imei: контекст устарел (TTL), обновляем из Redis")
          freshData <- service.refreshDeviceContext(imei).catchAll { error =>
            ZIO.logWarning(s"[HANDLER] IMEI=$imei: не удалось обновить контекст: ${error.getMessage}") *>
            ZIO.succeed(state.deviceData) // Используем старые данные при ошибке
          }
          newState = freshData match
            case Some(dd) => state.withUpdatedDeviceData(dd, now)
            case None     => state.copy(contextCachedAt = now) // Сбрасываем таймер
          _ <- stateRef.set(newState)
        yield newState
      else ZIO.succeed(state)
      
      // Получаем предыдущую позицию из in-memory кэша (НЕ из Redis!)
      prevPosition = currentState.getPreviousPosition
      
      // Обрабатываем пакет через сервис — контекст из in-memory кэша ConnectionState
      result <- service.processDataPacket(buffer, imei, vehicleId, prevPosition, parser, currentState.deviceData)
      (validPoints, totalCount) = result
      
      // Обновляем in-memory кэш последней позицией (без Redis HMSET!)
      _ <- ZIO.foreach(validPoints.lastOption) { lastPoint =>
        stateRef.update(_.withLastPosition(lastPoint)) *>
        ZIO.logDebug(s"[HANDLER] In-memory позиция: lat=${lastPoint.latitude}, lon=${lastPoint.longitude}")
      }
      
      // Отправляем ACK с количеством принятых точек
      _ <- ZIO.succeed {
        val ack = parser.ack(totalCount)
        ctx.writeAndFlush(ack)
      }
      
      _ <- ZIO.logDebug(s"[HANDLER] IMEI=$imei: обработано ${validPoints.size}/$totalCount точек, отправлен ACK")
    yield ()
    
    effect.catchAll { error =>
      ZIO.logError(s"[HANDLER] Ошибка обработки данных: ${error.getMessage}")
    }
  
  /**
   * Обрабатывает GPS пакет от незарегистрированного трекера
   * 
   * Парсит точки и публикует в unknown-gps-events.
   * Не применяет фильтры (Dead Reckoning, Stationary) — сохраняем всё.
   */
  private def handleUnknownDataPacket(
    ctx: ChannelHandlerContext,
    buffer: ByteBuf,
    state: ConnectionState
  ): UIO[Unit] =
    val effect = for
      imei <- ZIO.fromOption(state.imei)
                .orElseFail(new Exception("IMEI не установлен для unknown device"))
      
      _ <- ZIO.logDebug(s"[HANDLER] Получен DATA пакет от незарегистрированного IMEI=$imei, размер=${buffer.readableBytes()} байт")
      _ <- registry.updateLastActivity(imei)
      
      // Обрабатываем через сервис — публикация в unknown-gps-events
      totalCount <- service.processUnknownDataPacket(buffer, imei, parser, instanceId = "cm-instance")
      
      // Отправляем ACK
      _ <- ZIO.succeed {
        val ack = parser.ack(totalCount)
        ctx.writeAndFlush(ack)
      }
      
      _ <- ZIO.logDebug(s"[HANDLER] IMEI=$imei (unknown): обработано $totalCount точек → unknown-gps-events, ACK отправлен")
    yield ()
    
    effect.catchAll { error =>
      ZIO.logError(s"[HANDLER] Ошибка обработки данных от незарегистрированного трекера: ${error.getMessage}")
    }
  
  /**
   * Вызывается Netty при установке TCP соединения
   */
  override def channelActive(ctx: ChannelHandlerContext): Unit =
    val remoteAddr = ctx.channel().remoteAddress()
    forkEffect(ZIO.logInfo(s"[HANDLER] → Новое TCP соединение от $remoteAddr"))
    super.channelActive(ctx)
  
  /**
   * Вызывается Netty при закрытии TCP соединения
   * 
   * Причины закрытия:
   * - Трекер сам закрыл соединение (GracefulClose)
   * - Таймаут неактивности (IdleTimeout)
   * - Сетевые проблемы (ConnectionReset)
   */
  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    val remoteAddr = ctx.channel().remoteAddress()
    
    // Семафор ждёт завершения текущего пакета перед cleanup
    val effect = processingPermit.withPermit {
      for
        state <- stateRef.get
        _ <- (state.imei, state.vehicleId) match
          case (Some(imei), Some(vid)) =>
            // Устройство было аутентифицировано - уведомляем об отключении
            for
              _ <- ZIO.logDebug(s"[HANDLER] Соединение закрыто для аутентифицированного устройства: IMEI=$imei")
              _ <- registry.unregisterIfSame(imei, ctx)
              _ <- service.onDisconnect(imei, vid, DisconnectReason.GracefulClose, state.connectedAt)
            yield ()
          case _ =>
            // Соединение закрыто до аутентификации - просто логируем
            ZIO.logDebug(s"[HANDLER] Соединение закрыто до аутентификации от $remoteAddr")
        _ <- ZIO.logInfo(s"[HANDLER] ← TCP соединение закрыто: $remoteAddr")
      yield ()
    }
    
    forkEffect(effect)
    super.channelInactive(ctx)
  
  /**
   * Вызывается Netty при ошибке в соединении
   */
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    val remoteAddr = ctx.channel().remoteAddress()
    forkEffect(ZIO.logError(s"[HANDLER] ✗ Ошибка в соединении от $remoteAddr: ${cause.getMessage}"))
    ctx.close() // Закрываем канал синхронно — не ждём логирование
  
  /**
   * Запускает ZIO эффект асинхронно в background fiber.
   * НЕ блокирует Netty I/O поток — возвращает управление мгновенно (<1μs).
   * 
   * Эффект выполняется в ZIO thread pool, результат отбрасывается.
   * Для упорядоченной обработки используй processingPermit.withPermit().
   */
  private def forkEffect(effect: UIO[Any]): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.fork(effect)
    }

object ConnectionHandler:
  /**
   * Создает фабрику обработчиков
   */
  def factory(
      service: GpsProcessingService,
      parser: ProtocolParser,
      registry: ConnectionRegistry,
      runtime: Runtime[Any]
  ): () => ConnectionHandler =
    () => new ConnectionHandler(service, parser, registry, runtime)
