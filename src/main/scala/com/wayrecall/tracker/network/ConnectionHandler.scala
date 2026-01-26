package com.wayrecall.tracker.network

import zio.*
import zio.stream.*
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.protocol.ProtocolParser
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}
import java.net.InetSocketAddress

/**
 * Состояние соединения - immutable (неизменяемый объект)
 * 
 * Хранит информацию о текущем TCP-соединении:
 * - IMEI устройства (после аутентификации)
 * - VehicleId из базы данных
 * - Время подключения (для расчёта длительности сессии)
 * - Кэш последних позиций (для фильтрации)
 * 
 * @param imei IMEI устройства (None до аутентификации)
 * @param vehicleId ID транспортного средства в системе
 * @param connectedAt Unix timestamp подключения в миллисекундах
 * @param positionCache Кэш последних позиций по vehicleId для фильтрации
 */
final case class ConnectionState(
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    connectedAt: Long = 0L,
    positionCache: Map[Long, GpsPoint] = Map.empty
):
  /** Устанавливает IMEI и vehicleId после успешной аутентификации */
  def withImei(newImei: String, vid: Long, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = Some(vid), connectedAt = timestamp)
  
  /** Обновляет последнюю известную позицию для vehicleId */
  def updatePosition(vid: Long, point: GpsPoint): ConnectionState =
    copy(positionCache = positionCache.updated(vid, point))
  
  /** Получает последнюю известную позицию для vehicleId (для Dead Reckoning фильтра) */
  def getPosition(vid: Long): Option[GpsPoint] =
    positionCache.get(vid)

/**
 * Сервис обработки GPS данных - чисто функциональный
 * 
 * Отвечает за:
 * 1. Аутентификацию устройства по IMEI
 * 2. Парсинг GPS-пакетов
 * 3. Фильтрацию аномальных точек (Dead Reckoning)
 * 4. Фильтрацию стоянок (Stationary Filter)
 * 5. Сохранение позиций в Redis
 * 6. Публикацию событий в Kafka
 * 7. Отслеживание статуса подключения/отключения
 */
trait GpsProcessingService:
  /**
   * Обрабатывает первый пакет с IMEI
   * 
   * Алгоритм:
   * 1. Парсим IMEI из бинарного пакета
   * 2. Ищем vehicleId в Redis по ключу vehicle:{imei}
   * 3. Если не найден - устройство неизвестно
   * 4. Получаем предыдущую позицию из Redis (для фильтра)
   * 
   * @param buffer Netty ByteBuf с сырыми данными
   * @param remoteAddress IP адрес трекера
   * @param protocolName Название протокола (teltonika, wialon и т.д.)
   * @return Кортеж (IMEI, vehicleId, предыдущая позиция)
   */
  def processImeiPacket(
    buffer: ByteBuf,
    remoteAddress: InetSocketAddress,
    protocolName: String
  ): IO[ProtocolError, (String, Long, Option[GpsPoint])]
  
  /**
   * Обрабатывает пакет с GPS данными
   * 
   * Алгоритм:
   * 1. Парсим сырые точки из бинарного пакета
   * 2. Для каждой точки применяем фильтры
   * 3. Сохраняем в Redis (для фронтенда)
   * 4. Публикуем в Kafka (для аналитики)
   * 
   * @param buffer Netty ByteBuf с сырыми данными
   * @param imei IMEI устройства
   * @param vehicleId ID транспортного средства
   * @param prevPosition Предыдущая позиция (для фильтрации)
   * @return Кортеж (валидные точки, общее количество точек)
   */
  def processDataPacket(
    buffer: ByteBuf,
    imei: String,
    vehicleId: Long,
    prevPosition: Option[GpsPoint]
  ): IO[Throwable, (List[GpsPoint], Int)]
  
  /**
   * Вызывается при успешном подключении устройства
   * 
   * Действия:
   * 1. Регистрирует соединение в Redis (для мониторинга)
   * 2. Публикует событие DeviceStatus(isOnline=true) в Kafka
   */
  def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress): UIO[Unit]
  
  /**
   * Вызывается при отключении устройства
   * 
   * Действия:
   * 1. Удаляет соединение из Redis
   * 2. Публикует событие DeviceStatus(isOnline=false) в Kafka с причиной отключения
   */
  def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit]
  
  /**
   * Вызывается при подключении неизвестного устройства
   * 
   * Публикует событие UnknownDeviceEvent в Kafka топик unknown-devices
   * Полезно для:
   * - Автоматического provisioning новых устройств
   * - Мониторинга попыток подключения
   * - Безопасности (обнаружение подозрительных IMEI)
   */
  def onUnknownDevice(imei: String, protocolName: String, remoteAddress: InetSocketAddress): UIO[Unit]

object GpsProcessingService:
  
  /**
   * Live реализация сервиса обработки GPS
   * 
   * Зависимости:
   * - parser: ProtocolParser - парсер протокола (Teltonika, Wialon и т.д.)
   * - redisClient: RedisClient - клиент Redis для кэширования
   * - kafkaProducer: KafkaProducer - продюсер для публикации событий
   * - deadReckoningFilter: DeadReckoningFilter - фильтр аномальных точек
   * - stationaryFilter: StationaryFilter - фильтр стоянок
   */
  final case class Live(
      parser: ProtocolParser,
      redisClient: RedisClient,
      kafkaProducer: KafkaProducer,
      deadReckoningFilter: DeadReckoningFilter,
      stationaryFilter: StationaryFilter
  ) extends GpsProcessingService:
    
    override def processImeiPacket(
      buffer: ByteBuf,
      remoteAddress: InetSocketAddress,
      protocolName: String
    ): IO[ProtocolError, (String, Long, Option[GpsPoint])] =
      for
        // Шаг 1: Парсим IMEI из бинарного пакета
        imei <- parser.parseImei(buffer)
        _ <- ZIO.logDebug(s"[AUTH] Получен IMEI: $imei от ${remoteAddress.getAddress.getHostAddress}")
        
        // Шаг 2: Ищем vehicleId в Redis по ключу vehicle:{imei}
        // Здесь в будущем будет fallback на PostgreSQL через VehicleLookupService
        maybeVehicleId <- redisClient.getVehicleId(imei)
                            .tapError(e => ZIO.logError(s"[AUTH] Ошибка Redis при поиске vehicleId: ${e.message}"))
                            .mapError(e => ProtocolError.ParseError(e.message))
        _ <- ZIO.logDebug(s"[AUTH] Результат поиска vehicleId в Redis: $maybeVehicleId")
        
        // Шаг 3: Если не найден - устройство неизвестно
        vehicleId <- ZIO.fromOption(maybeVehicleId)
                       .orElseFail(ProtocolError.UnknownDevice(imei))
        _ <- ZIO.logInfo(s"[AUTH] ✓ Устройство аутентифицировано: IMEI=$imei → vehicleId=$vehicleId")
        
        // Шаг 4: Получаем предыдущую позицию из Redis (для Dead Reckoning фильтра)
        prevPosition <- redisClient.getPosition(vehicleId)
                          .tapError(e => ZIO.logWarning(s"[AUTH] Ошибка получения предыдущей позиции: ${e.message}"))
                          .mapError(e => ProtocolError.ParseError(e.message))
        _ <- ZIO.logDebug(s"[AUTH] Предыдущая позиция: ${prevPosition.map(p => s"lat=${p.latitude}, lon=${p.longitude}").getOrElse("нет")}")
      yield (imei, vehicleId, prevPosition)
    
    override def processDataPacket(
      buffer: ByteBuf,
      imei: String,
      vehicleId: Long,
      prevPosition: Option[GpsPoint]
    ): IO[Throwable, (List[GpsPoint], Int)] =
      for
        // Шаг 1: Парсим сырые точки из бинарного пакета
        rawPoints <- parser.parseData(buffer, imei)
                       .tapError(e => ZIO.logError(s"[DATA] Ошибка парсинга пакета: ${e.message}"))
                       .mapError(e => new Exception(e.message))
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: распарсено ${rawPoints.size} сырых точек")
        
        // Шаг 2: Обрабатываем каждую точку через фильтры
        result <- ZIO.foldLeft(rawPoints)((List.empty[GpsPoint], prevPosition)) { 
          case ((processed, prev), raw) =>
            processPoint(raw, vehicleId, prev).map { point =>
              (processed :+ point, Some(point))
            }.catchAll { error =>
              // Точка отфильтрована - это нормально, не ошибка
              ZIO.logDebug(s"[FILTER] IMEI=$imei: точка отфильтрована - ${error.getMessage}") *>
              ZIO.succeed((processed, prev))
            }
        }
        (validPoints, _) = result
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: после фильтрации осталось ${validPoints.size}/${rawPoints.size} точек")
      yield (validPoints, rawPoints.size)
    
    /**
     * Обрабатывает одну GPS точку через все фильтры
     * 
     * Pipeline обработки:
     * 1. Dead Reckoning Filter - отсеивает точки с нереальной скоростью
     * 2. Преобразование в GpsPoint с vehicleId
     * 3. Stationary Filter - определяет, нужно ли публиковать
     * 4. Сохранение в Redis (всегда)
     * 5. Публикация в Kafka (только при движении)
     */
    private def processPoint(
      raw: GpsRawPoint,
      vehicleId: Long,
      prev: Option[GpsPoint]
    ): IO[Throwable, GpsPoint] =
      for
        // Шаг 1: Dead Reckoning - проверяем скорость перехода
        _ <- deadReckoningFilter.validateWithPrev(raw, prev)
               .tapError(e => ZIO.logDebug(s"[FILTER] Dead Reckoning отклонил точку: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 2: Преобразуем сырую точку в валидированную
        point = raw.toValidated(vehicleId)
        
        // Шаг 3: Stationary Filter - определяем нужно ли публиковать
        shouldPublish <- stationaryFilter.shouldPublish(point, prev)
        _ <- ZIO.logDebug(s"[FILTER] vehicleId=$vehicleId: shouldPublish=$shouldPublish (lat=${point.latitude}, lon=${point.longitude})")
        
        // Шаг 4: Сохраняем в Redis (всегда, для фронтенда)
        _ <- redisClient.setPosition(point)
               .tapError(e => ZIO.logError(s"[REDIS] Ошибка сохранения позиции: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 5: Публикуем в Kafka только если движемся
        _ <- ZIO.when(shouldPublish)(
               kafkaProducer.publishGpsEvent(point)
                 .tap(_ => ZIO.logDebug(s"[KAFKA] Опубликована точка для vehicleId=$vehicleId"))
                 .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации: ${e.message}"))
                 .mapError(e => new Exception(e.message))
             )
      yield point
    
    override def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        ip = remoteAddress.getAddress.getHostAddress
        port = remoteAddress.getPort
        
        // Создаём информацию о соединении
        connInfo = ConnectionInfo(
          imei = imei,
          connectedAt = now,
          remoteAddress = ip,
          port = port
        )
        
        // Регистрируем в Redis для мониторинга
        _ <- redisClient.registerConnection(connInfo)
               .tapError(e => ZIO.logWarning(s"[CONNECT] Ошибка регистрации в Redis: ${e.message}"))
        _ <- ZIO.logDebug(s"[CONNECT] Соединение зарегистрировано в Redis: connection:$imei")
        
        // Создаём событие статуса устройства
        status = DeviceStatus(
          imei = imei,
          vehicleId = vehicleId,
          isOnline = true,
          lastSeen = now
        )
        
        // Публикуем в Kafka для аналитики и уведомлений
        _ <- kafkaProducer.publishDeviceStatus(status)
               .tapError(e => ZIO.logWarning(s"[CONNECT] Ошибка публикации статуса в Kafka: ${e.message}"))
        _ <- ZIO.logDebug(s"[CONNECT] Статус опубликован в Kafka: DeviceStatus(online=true)")
        
        // Основной лог - уровень INFO
        _ <- ZIO.logInfo(s"[CONNECT] ✓ Устройство подключено: IMEI=$imei, vehicleId=$vehicleId, адрес=$ip:$port")
      yield ()
      
      effect.ignore
    
    override def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        sessionDurationMs = now - connectedAt
        sessionSeconds = sessionDurationMs / 1000
        
        // Удаляем из Redis
        _ <- redisClient.unregisterConnection(imei)
               .tapError(e => ZIO.logWarning(s"[DISCONNECT] Ошибка удаления из Redis: ${e.message}"))
        _ <- ZIO.logDebug(s"[DISCONNECT] Соединение удалено из Redis: connection:$imei")
        
        // Создаём событие статуса
        status = DeviceStatus(
          imei = imei,
          vehicleId = vehicleId,
          isOnline = false,
          lastSeen = now,
          disconnectReason = Some(reason),
          sessionDurationMs = Some(sessionDurationMs)
        )
        
        // Публикуем в Kafka
        _ <- kafkaProducer.publishDeviceStatus(status)
               .tapError(e => ZIO.logWarning(s"[DISCONNECT] Ошибка публикации статуса в Kafka: ${e.message}"))
        
        // Основной лог - уровень INFO
        // Преобразуем DisconnectReason в человекочитаемую строку на русском
        reasonText = reason match
          case DisconnectReason.GracefulClose => "нормальное закрытие"
          case DisconnectReason.IdleTimeout => "таймаут неактивности"
          case DisconnectReason.ReadTimeout => "таймаут чтения"
          case DisconnectReason.WriteTimeout => "таймаут записи"
          case DisconnectReason.ConnectionReset => "сброс соединения (TCP RST)"
          case DisconnectReason.ProtocolError => "ошибка протокола"
          case DisconnectReason.ServerShutdown => "остановка сервера"
          case DisconnectReason.AdminDisconnect => "отключено администратором"
          case DisconnectReason.Unknown => "неизвестная причина"
        _ <- ZIO.logInfo(s"[DISCONNECT] ✗ Устройство отключено: IMEI=$imei, причина=$reasonText, длительность сессии=${sessionSeconds}с")
      yield ()
      
      effect.ignore
    
    override def onUnknownDevice(imei: String, protocolName: String, remoteAddress: InetSocketAddress): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        ip = remoteAddress.getAddress.getHostAddress
        port = remoteAddress.getPort
        
        // Создаём событие о неизвестном устройстве
        event = UnknownDeviceEvent(
          imei = imei,
          protocol = protocolName,
          remoteAddress = ip,
          port = port,
          timestamp = now
        )
        
        // Публикуем в Kafka в топик unknown-devices
        _ <- kafkaProducer.publishUnknownDevice(event)
               .tapError(e => ZIO.logWarning(s"[UNKNOWN] Ошибка публикации события: ${e.message}"))
        
        // Основной лог - WARNING (неизвестное устройство - это важно!)
        _ <- ZIO.logWarning(s"[UNKNOWN] ⚠ Попытка подключения неизвестного устройства: IMEI=$imei, протокол=$protocolName, адрес=$ip:$port")
      yield ()
      
      effect.ignore
  
  val live: ZLayer[
    ProtocolParser & RedisClient & KafkaProducer & DeadReckoningFilter & StationaryFilter,
    Nothing,
    GpsProcessingService
  ] = ZLayer {
    for
      parser <- ZIO.service[ProtocolParser]
      redis <- ZIO.service[RedisClient]
      kafka <- ZIO.service[KafkaProducer]
      deadReckoning <- ZIO.service[DeadReckoningFilter]
      stationary <- ZIO.service[StationaryFilter]
    yield Live(parser, redis, kafka, deadReckoning, stationary)
  }

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
  
  /**
   * Вызывается Netty при получении данных
   * 
   * Определяет тип пакета по текущему состоянию:
   * - Если IMEI ещё не установлен → это IMEI пакет (аутентификация)
   * - Если IMEI установлен → это DATA пакет (GPS точки)
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
            case Some(_) => 
              // Последующие пакеты - GPS данные
              handleDataPacket(ctx, buffer, state)
        yield ()
        
        // Важно: освобождаем ByteBuf после обработки (Netty reference counting)
        runEffect(effect.ensuring(ZIO.succeed(buffer.release())))
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
   * - Публикуем UnknownDeviceEvent
   * - Отправляем NACK
   * - Закрываем соединение
   */
  private def handleImeiPacket(ctx: ChannelHandlerContext, buffer: ByteBuf): UIO[Unit] =
    val remoteAddr = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
    val ip = remoteAddr.getAddress.getHostAddress
    
    val effect = for
      _ <- ZIO.logDebug(s"[HANDLER] Получен IMEI пакет от $ip, размер=${buffer.readableBytes()} байт")
      
      // Вызываем сервис для аутентификации
      result <- service.processImeiPacket(buffer, remoteAddr, parser.protocolName)
      (imei, vehicleId, prevPosition) = result
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      
      // Обновляем состояние соединения
      _ <- stateRef.update { state =>
        val updated = state.withImei(imei, vehicleId, now)
        prevPosition.fold(updated)(pos => updated.updatePosition(vehicleId, pos))
      }
      _ <- ZIO.logDebug(s"[HANDLER] Состояние обновлено: IMEI=$imei, vehicleId=$vehicleId")
      
      // Регистрируем подключение в реестре (для отправки команд и мониторинга)
      _ <- registry.register(imei, ctx, parser)
      _ <- ZIO.logDebug(s"[HANDLER] Соединение зарегистрировано в ConnectionRegistry")
      
      // Регистрируем подключение в Redis/Kafka
      _ <- service.onConnect(imei, vehicleId, remoteAddr)
      
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
        // Неизвестное устройство - публикуем событие и закрываем
        for
          _ <- ZIO.logWarning(s"[HANDLER] ✗ Неизвестное устройство: IMEI=${error.imei}, адрес=$ip")
          _ <- service.onUnknownDevice(error.imei, parser.protocolName, remoteAddr)
          _ <- ZIO.succeed {
            val ack = parser.imeiAck(false)
            ctx.writeAndFlush(ack)
            ctx.close()
          }
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
      // Это нужно для IdleConnectionWatcher - чтобы не отключать активные соединения
      _ <- registry.updateLastActivity(imei)
      
      // Получаем предыдущую позицию из кэша (для фильтрации)
      prevPosition = state.getPosition(vehicleId)
      
      // Обрабатываем пакет через сервис
      result <- service.processDataPacket(buffer, imei, vehicleId, prevPosition)
      (validPoints, totalCount) = result
      
      // Обновляем кэш последней позицией
      _ <- ZIO.foreach(validPoints.lastOption) { lastPoint =>
        stateRef.update(_.updatePosition(vehicleId, lastPoint)) *>
        ZIO.logDebug(s"[HANDLER] Кэш обновлён: последняя позиция lat=${lastPoint.latitude}, lon=${lastPoint.longitude}")
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
   * Вызывается Netty при установке TCP соединения
   */
  override def channelActive(ctx: ChannelHandlerContext): Unit =
    val remoteAddr = ctx.channel().remoteAddress()
    runEffect(ZIO.logInfo(s"[HANDLER] → Новое TCP соединение от $remoteAddr"))
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
    
    val effect = for
      state <- stateRef.get
      _ <- (state.imei, state.vehicleId) match
        case (Some(imei), Some(vid)) =>
          // Устройство было аутентифицировано - уведомляем об отключении
          for
            _ <- ZIO.logDebug(s"[HANDLER] Соединение закрыто для аутентифицированного устройства: IMEI=$imei")
            _ <- registry.unregister(imei)
            _ <- service.onDisconnect(imei, vid, DisconnectReason.GracefulClose, state.connectedAt)
          yield ()
        case _ =>
          // Соединение закрыто до аутентификации - просто логируем
          ZIO.logDebug(s"[HANDLER] Соединение закрыто до аутентификации от $remoteAddr")
      _ <- ZIO.logInfo(s"[HANDLER] ← TCP соединение закрыто: $remoteAddr")
    yield ()
    
    runEffect(effect)
    super.channelInactive(ctx)
  
  /**
   * Вызывается Netty при ошибке в соединении
   */
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    val remoteAddr = ctx.channel().remoteAddress()
    runEffect(ZIO.logError(s"[HANDLER] ✗ Ошибка в соединении от $remoteAddr: ${cause.getMessage}"))
    ctx.close()
  
  /**
   * Выполняет ZIO эффект синхронно (необходимо для Netty callbacks)
   */
  private def runEffect[A](effect: UIO[A]): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
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
