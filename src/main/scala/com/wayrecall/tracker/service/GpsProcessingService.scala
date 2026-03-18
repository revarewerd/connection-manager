package com.wayrecall.tracker.service

import zio.*
import zio.json.*
import io.netty.buffer.ByteBuf
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.config.{AppConfig, KafkaConfig}
import com.wayrecall.tracker.protocol.ProtocolParser
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}
import java.net.InetSocketAddress
import java.time.Instant

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
      kafkaConfig: KafkaConfig,
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
        _ <- ZIO.succeed {
          CmMetrics.packetsReceived.increment()
          if rawPoints.nonEmpty then CmMetrics.gpsPointsReceived.add(rawPoints.size.toLong)
        }
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
              (point :: processed, Some(point))  // O(1) prepend вместо O(n) append
            }.catchAll { error =>
              // Точка отфильтрована (Dead Reckoning) — публикуем в gps-events с isValid=false
              ZIO.logDebug(s"[FILTER] IMEI=$imei: точка отфильтрована - ${error.getMessage}") *>
              publishFilteredPoint(raw, vehicleId, imei, parser.protocolName, actualData).as((processed, prev))
            }
        }
        (validPointsRev, _) = result
        validPoints = validPointsRev.reverse  // Восстанавливаем хронологический порядок
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
        now <- ZIO.succeed(java.time.Instant.now())
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
        _ <- ZIO.succeed(CmMetrics.parseErrors.increment())
        now <- ZIO.succeed(java.lang.System.currentTimeMillis())
        
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
        now <- ZIO.succeed(java.time.Instant.now())
        
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
        
        // Сериализуем JSON один раз — используется для всех 3 топиков (gps-events, rules, retranslation)
        msgJson = msg.toJson
        msgKey = vehicleId.toString
        
        // Шаг 6: ВСЕГДА публикуем в gps-events (ВСЕ точки — для TimescaleDB)
        _ <- kafkaProducer.publish(kafkaConfig.topics.gpsEvents, msgKey, msgJson)
               .tap(_ => ZIO.succeed(CmMetrics.gpsPointsPublished.increment()))
               .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events vehicleId=$vehicleId isMoving=$isMoving"))
               .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 7: Маршрутизация в rules и retranslation — ТОЛЬКО движущиеся точки (параллельно)
        _ <- ZIO.when(isMoving)(
          dd match
            case Some(d) =>
              val rulesEffect = ZIO.when(d.needsRulesCheck)(
                kafkaProducer.publish(kafkaConfig.topics.gpsEventsRules, msgKey, msgJson)
                  .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-rules vehicleId=$vehicleId"))
                  .tapError(e => ZIO.logError(s"[KAFKA] Ошибка rules: ${e.message}"))
                  .mapError(e => new Exception(e.message))
              )
              val retranslationEffect = ZIO.when(d.hasRetranslation)(
                kafkaProducer.publish(kafkaConfig.topics.gpsEventsRetranslation, msgKey, msgJson)
                  .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-retranslation vehicleId=$vehicleId"))
                  .tapError(e => ZIO.logError(s"[KAFKA] Ошибка retranslation: ${e.message}"))
                  .mapError(e => new Exception(e.message))
              )
              // Публикация в rules и retranslation параллельно — они независимы
              rulesEffect.zipPar(retranslationEffect).unit
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
        now <- ZIO.succeed(java.lang.System.currentTimeMillis())
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
        _ <- ZIO.succeed(CmMetrics.unknownDevices.increment())
        now <- ZIO.succeed(java.lang.System.currentTimeMillis())
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
        now <- ZIO.succeed(java.lang.System.currentTimeMillis())
        
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
    yield Live(redis, kafka, config.kafka, deadReckoning, stationary, config.instanceId)
  }
