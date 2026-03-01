package com.wayrecall.tracker.network

import zio.*
import zio.stream.*
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.config.AppConfig
import com.wayrecall.tracker.protocol.{ProtocolParser, MultiProtocolParser}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}
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
 * Отвечает за:
 * 1. Аутентификацию устройства по IMEI
 * 2. Парсинг GPS-пакетов
 * 3. Фильтрацию аномальных точек (Dead Reckoning)
 * 4. Фильтрацию стоянок (Stationary Filter)
 * 5. Публикацию событий в Kafka
 * 6. Отслеживание статуса подключения/отключения
 * 
 * ИЗМЕНЕНИЕ v3.0: parser передаётся как параметр в методы,
 * а не хранится как поле. Каждый ConnectionHandler имеет свой parser,
 * соответствующий протоколу TCP-порта.
 */
trait GpsProcessingService:
  /**
   * Обрабатывает первый пакет с IMEI
   * 
   * @param buffer Netty ByteBuf с сырыми данными
   * @param remoteAddress IP адрес трекера
   * @param parser Парсер конкретного протокола (определяется TCP-портом)
   * @return Кортеж (IMEI, vehicleId, предыдущая позиция, DeviceData)
   */
  def processImeiPacket(
    buffer: ByteBuf,
    remoteAddress: InetSocketAddress,
    parser: ProtocolParser
  ): IO[ProtocolError, (String, Long, Option[GpsPoint], Option[DeviceData])]
  
  /**
   * Обрабатывает пакет с GPS данными
   * 
   * @param buffer Netty ByteBuf с сырыми данными
   * @param imei IMEI устройства
   * @param vehicleId ID транспортного средства
   * @param prevPosition Предыдущая позиция (для фильтрации)
   * @param parser Парсер конкретного протокола
   * @param deviceData Кэшированные данные устройства из ConnectionState
   * @return Кортеж (валидные точки, общее количество точек)
   */
  def processDataPacket(
    buffer: ByteBuf,
    imei: String,
    vehicleId: Long,
    prevPosition: Option[GpsPoint],
    parser: ProtocolParser,
    deviceData: Option[DeviceData] = None
  ): IO[Throwable, (List[GpsPoint], Int)]
  
  /**
   * Вызывается при успешном подключении устройства
   */
  def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress, protocolName: String): UIO[Unit]
  
  /**
   * Вызывается при отключении устройства
   */
  def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit]
  
  /**
   * Вызывается при подключении неизвестного устройства
   */
  def onUnknownDevice(imei: String, protocolName: String, remoteAddress: InetSocketAddress): UIO[Unit]
  
  /**
   * Обрабатывает GPS пакет от незарегистрированного трекера
   */
  def processUnknownDataPacket(
    buffer: ByteBuf,
    imei: String,
    parser: ProtocolParser,
    instanceId: String
  ): IO[Throwable, Int]

  /**
   * Обновляет контекст устройства из Redis (при истечении TTL кэша)
   * 
   * Выполняет HGETALL device:{imei} и возвращает свежие DeviceData.
   * Вызывается ~1 раз в час для каждого активного соединения.
   */
  def refreshDeviceContext(imei: String): IO[Throwable, Option[DeviceData]]

object GpsProcessingService:
  
  /**
   * Live реализация сервиса обработки GPS
   * 
   * ИЗМЕНЕНИЕ v3.0: parser убран из полей — передаётся как параметр.
   * GpsProcessingService теперь протоколо-независим.
   * Конкретный parser определяется ConnectionHandler'ом (= TCP-портом).
   * 
   * Зависимости:
   * - redisClient: RedisClient - клиент Redis для аутентификации и кэширования
   * - kafkaProducer: KafkaProducer - продюсер для публикации событий
   * - deadReckoningFilter: DeadReckoningFilter - фильтр аномальных точек
   * - stationaryFilter: StationaryFilter - фильтр стоянок
   * - instanceId: String - идентификатор этого CM инстанса
   */
  final case class Live(
      redisClient: RedisClient,
      kafkaProducer: KafkaProducer,
      deadReckoningFilter: DeadReckoningFilter,
      stationaryFilter: StationaryFilter,
      instanceId: String
  ) extends GpsProcessingService:
    
    override def processImeiPacket(
      buffer: ByteBuf,
      remoteAddress: InetSocketAddress,
      parser: ProtocolParser
    ): IO[ProtocolError, (String, Long, Option[GpsPoint], Option[DeviceData])] =
      for
        // Шаг 1: Парсим IMEI из бинарного пакета (протокол-специфичная логика)
        imei <- parser.parseImei(buffer)
        _ <- ZIO.logDebug(s"[AUTH] Получен IMEI: $imei от ${remoteAddress.getAddress.getHostAddress}")
        
        // Шаг 2: HGETALL device:{imei} — ЕДИНСТВЕННЫЙ запрос в Redis
        // Получаем ВСЕ данные: vehicleId, organizationId, флаги маршрутизации,
        // предыдущую позицию, connection info — всё за 1 roundtrip!
        maybeDeviceData <- redisClient.getDeviceData(imei)
                             .tapError(e => ZIO.logError(s"[AUTH] Ошибка Redis HGETALL device:$imei: ${e.message}"))
                             .mapError(e => ProtocolError.ParseError(e.message))
        _ <- ZIO.logDebug(s"[AUTH] DeviceData: ${maybeDeviceData.map(d => s"vehicleId=${d.vehicleId}, org=${d.organizationId}").getOrElse("нет (устройство не зарегистрировано)")}")
        
        // Шаг 3: Если DeviceData нет — устройство неизвестно (IMEI не в Redis)
        // Device Manager должен был записать device:{imei} при регистрации
        deviceData <- ZIO.fromOption(maybeDeviceData)
                        .orElseFail(ProtocolError.UnknownDevice(imei))
        vehicleId = deviceData.vehicleId
        _ <- ZIO.logInfo(s"[AUTH] ✓ Аутентификация: IMEI=$imei → vehicleId=$vehicleId, org=${deviceData.organizationId}")
        
        // Шаг 4: Предыдущая позиция уже в DeviceData (lat/lon поля из того же HASH!)
        // Не нужен отдельный GET position:{vehicleId} — всё в одном HGETALL
        prevPosition = deviceData.previousPosition
        _ <- ZIO.logDebug(s"[AUTH] Предыдущая позиция: ${prevPosition.map(p => s"lat=${p.latitude}, lon=${p.longitude}").getOrElse("нет")}")
        _ <- ZIO.logDebug(s"[AUTH] Маршрутизация: geozones=${deviceData.hasGeozones}, speedRules=${deviceData.hasSpeedRules}, retrans=${deviceData.hasRetranslation}")
      yield (imei, vehicleId, prevPosition, Some(deviceData))
    
    override def processDataPacket(
      buffer: ByteBuf,
      imei: String,
      vehicleId: Long,
      prevPosition: Option[GpsPoint],
      parser: ProtocolParser,
      deviceData: Option[DeviceData] = None
    ): IO[Throwable, (List[GpsPoint], Int)] =
      for
        // Шаг 1: Парсим сырые точки из бинарного пакета (протокол-специфичный парсинг)
        // При ошибке парсинга — публикуем в gps-parse-errors и возвращаем пустой результат
        rawPointsOpt <- parser.parseData(buffer, imei)
                          .tapError(e => ZIO.logError(s"[DATA] Ошибка парсинга пакета: ${e.message}"))
                          .foldZIO(
                            error => publishParseError(imei, parser.protocolName, error, buffer)
                              .as(None),
                            points => ZIO.succeed(Some(points))
                          )
        rawPoints = rawPointsOpt.getOrElse(List.empty)
        _ <- ZIO.when(rawPoints.nonEmpty)(
               ZIO.logDebug(s"[DATA] IMEI=$imei: распарсено ${rawPoints.size} сырых точек")
             )
        
        // Шаг 2: Используем контекст из in-memory кэша (ConnectionState.deviceData)
        // НЕ делаем HGETALL на каждый пакет! Кэш обновляется:
        // - При подключении (1 раз)
        // - По TTL (раз в час)
        // - По Redis Pub/Sub (device-config-changed)
        actualData = deviceData
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: контекст из in-memory кэша, " +
          s"routing: geozones=${actualData.map(_.hasGeozones)}, retrans=${actualData.map(_.hasRetranslation)}")
        
        // Шаг 3: Предыдущая позиция из in-memory кэша (НЕ из Redis!)
        actualPrev = prevPosition
        
        // Шаг 4: Обрабатываем каждую точку через фильтры (fold для цепочки prev → next)
        // ВСЕ точки публикуются в gps-events (даже отфильтрованные),
        // только valid+moving → gps-events-rules / gps-events-retranslation
        result <- ZIO.foldLeft(rawPoints)((List.empty[GpsPoint], actualPrev)) { 
          case ((processed, prev), raw) =>
            processPoint(raw, vehicleId, prev, actualData, imei, parser.protocolName).map { point =>
              (processed :+ point, Some(point))
            }.catchAll { error =>
              // Точка отфильтрована (Dead Reckoning) — публикуем в gps-events с isValid=false
              ZIO.logDebug(s"[FILTER] IMEI=$imei: точка отфильтрована - ${error.getMessage}") *>
              publishFilteredPoint(raw, vehicleId, imei, parser.protocolName, actualData).as((processed, prev))
            }
        }
        (validPoints, _) = result
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: после фильтрации ${validPoints.size}/${rawPoints.size} точек")
      yield (validPoints, rawPoints.size)
    
    /**
     * Публикует отфильтрованную точку в gps-events с isValid=false
     * 
     * TimescaleDB получает ВСЕ точки: валидные и отфильтрованные.
     * History Writer использует isValid для маркировки, а не для фильтрации.
     */
    private def publishFilteredPoint(
      raw: GpsRawPoint,
      vehicleId: Long,
      imei: String,
      protocolName: String,
      deviceData: Option[DeviceData]
    ): UIO[Unit] =
      val effect = for
        now <- Clock.instant
        dd = deviceData
        msg = GpsEventMessage(
          vehicleId = vehicleId,
          organizationId = dd.map(_.organizationId).getOrElse(0L),
          imei = imei,
          latitude = raw.latitude,
          longitude = raw.longitude,
          altitude = raw.altitude,
          speed = raw.speed,
          course = raw.angle,
          satellites = raw.satellites,
          deviceTime = raw.timestamp,
          serverTime = now.toEpochMilli,
          hasGeozones = dd.exists(_.hasGeozones),
          hasSpeedRules = dd.exists(_.hasSpeedRules),
          hasRetranslation = dd.exists(_.hasRetranslation),
          retranslationTargets = dd.flatMap(d => if d.retranslationTargets.isEmpty then None else Some(d.retranslationTargets)),
          isMoving = false,
          isValid = false,  // Точка отфильтрована Dead Reckoning
          protocol = protocolName
        )
        _ <- kafkaProducer.publishGpsEventMessage(msg)
               .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации отфильтрованной точки: ${e.message}"))
      yield ()
      effect.ignore
    
    /**
     * Публикует ошибку парсинга в gps-parse-errors
     * 
     * Используется когда parser.parseData не может обработать пакет.
     * Сохраняет hex-дамп первых 512 байт для отладки.
     */
    private def publishParseError(
      imei: String,
      protocolName: String,
      error: ProtocolError,
      buffer: ByteBuf
    ): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Hex-дамп первых 512 байт пакета для диагностики
        hexDump = ZIO.attempt {
          val readable = math.min(buffer.readableBytes(), 512)
          if readable > 0 then
            val bytes = new Array[Byte](readable)
            buffer.getBytes(buffer.readerIndex(), bytes)
            Some(bytes.map(b => f"${b & 0xFF}%02X").mkString(" "))
          else None
        }.orElse(ZIO.succeed(None))
        hex <- hexDump
        
        // Определяем тип ошибки из ProtocolError ADT
        errorType = error match
          case _: ProtocolError.InsufficientData       => "InsufficientData"
          case _: ProtocolError.InvalidChecksum        => "InvalidChecksum"
          case _: ProtocolError.InvalidCodec           => "InvalidCodec"
          case _: ProtocolError.InvalidImei            => "InvalidImei"
          case _: ProtocolError.ParseError             => "ParseError"
          case _: ProtocolError.UnknownDevice          => "UnknownDevice"
          case _: ProtocolError.UnsupportedProtocol    => "UnsupportedProtocol"
          case _: ProtocolError.ProtocolDetectionFailed => "ProtocolDetectionFailed"
          case _                                       => "GenericError"
        
        event = GpsParseErrorEvent(
          imei = imei,
          protocol = protocolName,
          errorType = errorType,
          errorMessage = error.message,
          rawPacketHex = hex,
          rawPacketSize = buffer.readableBytes(),
          remoteAddress = "",  // TODO: передать из ConnectionHandler
          instanceId = instanceId,
          timestamp = now
        )
        _ <- kafkaProducer.publishParseError(event)
               .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации parse error: ${e.message}"))
      yield ()
      effect.ignore
    
    /**
     * Обрабатывает одну GPS точку через все фильтры и обогащает контекстом
     * 
     * Pipeline обработки:
     * 1. Dead Reckoning Filter — отсеивает точки с нереальной скоростью/позицией
     * 2. Преобразование GpsRawPoint → GpsPoint с vehicleId
     * 3. Stationary Filter — определяет движение/стоянку
     * 4. ВСЕГДА публикуем в Kafka gps-events (ВСЕ точки, включая стоянки — для TimescaleDB)
     * 5. Обогащение GpsEventMessage из DeviceData (organizationId, speedLimit, флаги)
     * 6. Условная маршрутизация: ТОЛЬКО valid+moving → gps-events-rules / gps-events-retranslation
     *
     * ИЗМЕНЕНИЕ v4.0: gps-events получает ВСЕ точки (isMoving=true/false).
     * TimescaleDB нужна полная история для аналитики и отчётов.
     * Правила и ретрансляция получают только движущиеся точки.
     */
    private def processPoint(
      raw: GpsRawPoint,
      vehicleId: Long,
      prev: Option[GpsPoint],
      deviceData: Option[DeviceData],
      imei: String,
      protocolName: String
    ): IO[Throwable, GpsPoint] =
      for
        // Шаг 1: Dead Reckoning — проверяем скорость перехода между prev и raw
        _ <- deadReckoningFilter.validateWithPrev(raw, prev)
               .tapError(e => ZIO.logDebug(s"[FILTER] Dead Reckoning отклонил точку: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 2: Преобразуем сырую точку в валидированную GpsPoint с vehicleId
        point = raw.toValidated(vehicleId)
        
        // Шаг 3: Stationary Filter — определяем движение или стоянку
        isMoving <- stationaryFilter.shouldPublish(point, prev)
        _ <- ZIO.logDebug(s"[FILTER] vehicleId=$vehicleId: isMoving=$isMoving")
        
        // Шаг 4: Получаем текущий момент времени
        now <- Clock.instant
        
        // Шаг 5: Обогащённое сообщение GpsEventMessage с контекстом из DeviceData (in-memory)
        dd = deviceData
        msg = GpsEventMessage(
          vehicleId = vehicleId,
          organizationId = dd.map(_.organizationId).getOrElse(0L),
          imei = imei,
          latitude = point.latitude,
          longitude = point.longitude,
          altitude = point.altitude,
          speed = point.speed,
          course = point.angle,
          satellites = point.satellites,
          deviceTime = point.timestamp,
          serverTime = now.toEpochMilli,
          hasGeozones = dd.exists(_.hasGeozones),
          hasSpeedRules = dd.exists(_.hasSpeedRules),
          hasRetranslation = dd.exists(_.hasRetranslation),
          retranslationTargets = dd.flatMap(d => if d.retranslationTargets.isEmpty then None else Some(d.retranslationTargets)),
          isMoving = isMoving,
          isValid = true,
          protocol = protocolName
        )
        
        // Шаг 6: ВСЕГДА публикуем в gps-events (ВСЕ точки — для TimescaleDB)
        _ <- kafkaProducer.publishGpsEventMessage(msg)
               .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events vehicleId=$vehicleId isMoving=$isMoving"))
               .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 7: Маршрутизация в rules и retranslation — ТОЛЬКО движущиеся точки
        _ <- ZIO.when(isMoving)(
          dd match
            case Some(d) =>
              for
                // Публикация в gps-events-rules (геозоны + правила скорости)
                _ <- ZIO.when(d.needsRulesCheck)(
                  kafkaProducer.publishGpsRulesEvent(msg)
                    .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-rules vehicleId=$vehicleId"))
                    .tapError(e => ZIO.logError(s"[KAFKA] Ошибка rules: ${e.message}"))
                    .mapError(e => new Exception(e.message))
                )
                // Публикация в gps-events-retranslation (пересылка в внешние системы)
                _ <- ZIO.when(d.hasRetranslation)(
                  kafkaProducer.publishGpsRetranslationEvent(msg)
                    .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-retranslation vehicleId=$vehicleId"))
                    .tapError(e => ZIO.logError(s"[KAFKA] Ошибка retranslation: ${e.message}"))
                    .mapError(e => new Exception(e.message))
                )
              yield ()
            case None => ZIO.unit
        )
      yield point
    
    override def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress, protocolName: String): UIO[Unit] =
      val effect = for
        now <- Clock.instant
        ip = remoteAddress.getAddress.getHostAddress
        port = remoteAddress.getPort
        
        // HMSET device:{imei} — записываем connection поля в единый Redis HASH
        // Другие сервисы (WebSocket, API, мониторинг) видят что устройство подключено
        connectionFields = DeviceData.connectionToHash(
          instanceId = instanceId,
          protocol = protocolName,
          connectedAt = now,
          remoteAddress = s"$ip:$port"
        )
        _ <- redisClient.setDeviceConnectionFields(imei, connectionFields)
               .tapError(e => ZIO.logWarning(s"[CONNECT] Ошибка HMSET connection device:$imei: ${e.message}"))
        _ <- ZIO.logDebug(s"[CONNECT] Connection поля записаны в device:$imei")
        
        // Legacy: registerConnection в отдельный ключ connection:{imei}
        // TODO: убрать после миграции всех потребителей на device:{imei}
        connInfo = ConnectionInfo(
          imei = imei,
          connectedAt = now.toEpochMilli,
          remoteAddress = ip,
          port = port
        )
        _ <- redisClient.registerConnection(connInfo)
               .tapError(e => ZIO.logWarning(s"[CONNECT] Ошибка legacy registerConnection: ${e.message}"))
        
        // Создаём событие статуса устройства для Kafka
        status = DeviceStatus(
          imei = imei,
          vehicleId = vehicleId,
          isOnline = true,
          lastSeen = now.toEpochMilli
        )
        
        // Публикуем в Kafka device-status для аналитики и уведомлений
        _ <- kafkaProducer.publishDeviceStatus(status)
               .tapError(e => ZIO.logWarning(s"[CONNECT] Ошибка публикации статуса: ${e.message}"))
        
        _ <- ZIO.logInfo(s"[CONNECT] ✓ Устройство подключено: IMEI=$imei, vehicleId=$vehicleId, адрес=$ip:$port")
      yield ()
      
      effect.ignore
    
    override def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        sessionDurationMs = now - connectedAt
        sessionSeconds = sessionDurationMs / 1000
        
        // HDEL device:{imei} — очищаем connection поля из единого Redis HASH
        // После этого другие сервисы видят что устройство offline (нет instanceId/protocol)
        _ <- redisClient.clearDeviceConnectionFields(imei)
               .tapError(e => ZIO.logWarning(s"[DISCONNECT] Ошибка HDEL connection device:$imei: ${e.message}"))
        _ <- ZIO.logDebug(s"[DISCONNECT] Connection поля удалены из device:$imei")
        
        // Legacy: удаляем отдельный ключ connection:{imei}
        // TODO: убрать после миграции всех потребителей на device:{imei}
        _ <- redisClient.unregisterConnection(imei)
               .tapError(e => ZIO.logWarning(s"[DISCONNECT] Ошибка legacy unregisterConnection: ${e.message}"))
        
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
    
    override def processUnknownDataPacket(
      buffer: ByteBuf,
      imei: String,
      parser: ProtocolParser,
      instanceId: String
    ): IO[Throwable, Int] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Парсим сырые точки из бинарного пакета
        rawPoints <- parser.parseData(buffer, imei)
                       .tapError(e => ZIO.logError(s"[UNKNOWN-DATA] Ошибка парсинга: ${e.message}"))
                       .mapError(e => new Exception(e.message))
        _ <- ZIO.logDebug(s"[UNKNOWN-DATA] IMEI=$imei: распарсено ${rawPoints.size} точек от незарегистрированного трекера")
        
        // Публикуем в unknown-gps-events (без фильтрации — сохраняем всё)
        _ <- ZIO.foreachDiscard(rawPoints) { raw =>
          val point = UnknownGpsPoint(
            imei = imei,
            latitude = raw.latitude,
            longitude = raw.longitude,
            altitude = raw.altitude,
            speed = raw.speed,
            angle = raw.angle,
            satellites = raw.satellites,
            deviceTime = raw.timestamp,
            serverTime = now,
            protocol = parser.protocolName,
            instanceId = instanceId
          )
          kafkaProducer.publishUnknownGpsEvent(point)
            .tapError(e => ZIO.logError(s"[UNKNOWN-DATA] Ошибка публикации: ${e.message}"))
            .ignore
        }
        _ <- ZIO.logDebug(s"[UNKNOWN-DATA] IMEI=$imei: опубликовано ${rawPoints.size} точек в unknown-gps-events")
      yield rawPoints.size
    
    override def refreshDeviceContext(imei: String): IO[Throwable, Option[DeviceData]] =
      for
        _ <- ZIO.logDebug(s"[REFRESH] IMEI=$imei: обновление контекста устройства из Redis")
        deviceData <- redisClient.getDeviceData(imei)
                        .mapError(e => new Exception(s"Redis ошибка при обновлении контекста: ${e.getMessage}"))
        _ <- ZIO.logInfo(s"[REFRESH] IMEI=$imei: контекст обновлён, deviceData=${deviceData.isDefined}")
      yield deviceData
  
  val live: ZLayer[
    AppConfig & RedisClient & KafkaProducer & DeadReckoningFilter & StationaryFilter,
    Nothing,
    GpsProcessingService
  ] = ZLayer {
    for
      config <- ZIO.service[AppConfig]
      redis <- ZIO.service[RedisClient]
      kafka <- ZIO.service[KafkaProducer]
      deadReckoning <- ZIO.service[DeadReckoningFilter]
      stationary <- ZIO.service[StationaryFilter]
    yield Live(redis, kafka, deadReckoning, stationary, config.instanceId)
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
   * - Если IMEI установлен + isUnknownDevice → GPS данные → unknown-gps-events
   * - Если IMEI установлен + vehicleId → GPS данные → gps-events
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
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      
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
          now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
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
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
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
