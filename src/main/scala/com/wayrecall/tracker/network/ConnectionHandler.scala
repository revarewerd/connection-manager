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
import java.time.Instant

/**
 * Состояние соединения — immutable (неизменяемый объект)
 * 
 * Хранит информацию о текущем TCP-соединении:
 * - IMEI устройства (после аутентификации)
 * - VehicleId из базы данных
 * - DeviceData из Redis HASH device:{imei} (контекст + позиция + connection)
 * - Время подключения (для расчёта длительности сессии)
 * - Кэш последних позиций (для фильтрации)
 *
 * DeviceData заменяет VehicleConfig — теперь храним ВСЕ данные
 * из единого Redis HASH, а не только маршрутизационные флаги.
 * 
 * @param imei IMEI устройства (None до аутентификации)
 * @param vehicleId ID транспортного средства в системе
 * @param connectedAt Unix timestamp подключения в миллисекундах
 * @param positionCache Кэш последних позиций по vehicleId для фильтрации
 * @param deviceData Полные данные устройства из Redis HASH device:{imei}
 */
final case class ConnectionState(
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    connectedAt: Long = 0L,
    positionCache: Map[Long, GpsPoint] = Map.empty,
    isUnknownDevice: Boolean = false,   // Трекер не зарегистрирован, но мы принимаем данные
    deviceData: Option[DeviceData] = None  // Все данные устройства из Redis HASH device:{imei}
):
  /** Устанавливает IMEI и vehicleId после успешной аутентификации */
  def withImei(newImei: String, vid: Long, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = Some(vid), connectedAt = timestamp)
  
  /** Устанавливает IMEI, vehicleId + полные данные из Redis HASH */
  def withImeiAndDeviceData(newImei: String, vid: Long, timestamp: Long, data: DeviceData): ConnectionState =
    copy(imei = Some(newImei), vehicleId = Some(vid), connectedAt = timestamp, deviceData = Some(data))
  
  /** Устанавливает IMEI для незарегистрированного трекера (без vehicleId) */
  def withUnknownImei(newImei: String, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = None, connectedAt = timestamp, isUnknownDevice = true)
  
  /** Обновляет DeviceData (при получении device-events или fresh HGETALL) */
  def withUpdatedDeviceData(data: DeviceData): ConnectionState =
    copy(deviceData = Some(data))
  
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
  ): IO[ProtocolError, (String, Long, Option[GpsPoint], Option[DeviceData])]
  
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
    prevPosition: Option[GpsPoint],
    deviceData: Option[DeviceData] = None
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
  
  /**
   * Обрабатывает GPS пакет от незарегистрированного трекера
   * 
   * Парсит точки и публикует в Kafka топик unknown-gps-events.
   * History Writer сохраняет их в отдельную таблицу unknown_device_positions.
   * Device Manager показывает в вебе для ручной регистрации.
   */
  def processUnknownDataPacket(
    buffer: ByteBuf,
    imei: String,
    protocolName: String,
    instanceId: String
  ): IO[Throwable, Int]

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
      deviceData: Option[DeviceData] = None
    ): IO[Throwable, (List[GpsPoint], Int)] =
      for
        // Шаг 1: Парсим сырые точки из бинарного пакета (протокол-специфичный парсинг)
        rawPoints <- parser.parseData(buffer, imei)
                       .tapError(e => ZIO.logError(s"[DATA] Ошибка парсинга пакета: ${e.message}"))
                       .mapError(e => new Exception(e.message))
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: распарсено ${rawPoints.size} сырых точек")
        
        // Шаг 2: HGETALL device:{imei} — СВЕЖИЙ контекст на КАЖДЫЙ data-пакет!
        // Это критически важно: Device Manager мог обновить флаги (hasGeozones, hasRetranslation)
        // между пакетами, и мы должны маршрутизировать по актуальным данным.
        // Стоимость: ~0.1мс на HGETALL — пренебрежимо мала по сравнению с Kafka publish.
        freshData <- redisClient.getDeviceData(imei)
                       .tapError(e => ZIO.logWarning(s"[DATA] Ошибка HGETALL device:$imei: ${e.message}"))
                       .catchAll(_ => ZIO.succeed(None))
        // Используем свежие данные или fallback на кэш из ConnectionState
        actualData = freshData.orElse(deviceData)
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: freshData=${freshData.isDefined}, " +
          s"routing: geozones=${actualData.map(_.hasGeozones)}, retrans=${actualData.map(_.hasRetranslation)}")
        
        // Шаг 3: Определяем предыдущую позицию — кэш > свежие данные из Redis
        // Кэш в ConnectionState точнее (обновляется после каждой точки),
        // но если кэш пуст (первый пакет после реконнекта) — берём из Redis HASH
        actualPrev = prevPosition.orElse(freshData.flatMap(_.previousPosition))
        
        // Шаг 4: Обрабатываем каждую точку через фильтры (fold для цепочки prev → next)
        result <- ZIO.foldLeft(rawPoints)((List.empty[GpsPoint], actualPrev)) { 
          case ((processed, prev), raw) =>
            processPoint(raw, vehicleId, prev, actualData, imei).map { point =>
              (processed :+ point, Some(point))
            }.catchAll { error =>
              // Точка отфильтрована — это НОРМАЛЬНО, не ошибка (Dead Reckoning / Stationary)
              ZIO.logDebug(s"[FILTER] IMEI=$imei: точка отфильтрована - ${error.getMessage}") *>
              ZIO.succeed((processed, prev))
            }
        }
        (validPoints, _) = result
        _ <- ZIO.logDebug(s"[DATA] IMEI=$imei: после фильтрации ${validPoints.size}/${rawPoints.size} точек")
      yield (validPoints, rawPoints.size)
    
    /**
     * Обрабатывает одну GPS точку через все фильтры и обогащает контекстом
     * 
     * Pipeline обработки:
     * 1. Dead Reckoning Filter — отсеивает точки с нереальной скоростью/позицией
     * 2. Преобразование GpsRawPoint → GpsPoint с vehicleId
     * 3. Stationary Filter — определяет движение/стоянку
     * 4. HMSET device:{imei} — обновляем position поля в едином Redis HASH
     * 5. Legacy: SETEX position:{vehicleId} — обратная совместимость
     * 6. Публикация в Kafka gps-events (только при движении)
     * 7. Обогащение GpsEventMessage из DeviceData (organizationId, speedLimit, флаги)
     * 8. Условная маршрутизация в gps-events-rules / gps-events-retranslation
     *
     * @param raw Сырая точка из протокола
     * @param vehicleId ID транспортного средства
     * @param prev Предыдущая позиция (для фильтрации)
     * @param deviceData Контекст устройства из Redis HASH (для обогащения + маршрутизации)
     * @param imei IMEI устройства (для ключа Redis и Kafka)
     */
    private def processPoint(
      raw: GpsRawPoint,
      vehicleId: Long,
      prev: Option[GpsPoint],
      deviceData: Option[DeviceData],
      imei: String
    ): IO[Throwable, GpsPoint] =
      for
        // Шаг 1: Dead Reckoning — проверяем скорость перехода между prev и raw
        // Если точка "телепортировалась" на невозможное расстояние — отклоняем
        _ <- deadReckoningFilter.validateWithPrev(raw, prev)
               .tapError(e => ZIO.logDebug(s"[FILTER] Dead Reckoning отклонил точку: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 2: Преобразуем сырую точку в валидированную GpsPoint с vehicleId
        point = raw.toValidated(vehicleId)
        
        // Шаг 3: Stationary Filter — определяем движение или стоянку
        // shouldPublish = true только если ТС движется (скорость >= порога или расстояние >= порога)
        shouldPublish <- stationaryFilter.shouldPublish(point, prev)
        _ <- ZIO.logDebug(s"[FILTER] vehicleId=$vehicleId: shouldPublish=$shouldPublish (lat=${point.latitude}, lon=${point.longitude})")
        
        // Шаг 4: Получаем текущий момент времени
        now <- Clock.instant
        
        // Шаг 5: HMSET device:{imei} — обновляем position поля в едином Redis HASH
        // Это позволяет другим сервисам (WebSocket, API) получить позицию из того же ключа
        _ <- redisClient.updateDevicePosition(imei, DeviceData.positionToHash(point, shouldPublish, now))
               .tapError(e => ZIO.logError(s"[REDIS] Ошибка HMSET позиции device:$imei: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 6: Legacy — SETEX position:{vehicleId} (обратная совместимость)
        // TODO: убрать после миграции всех потребителей на device:{imei}
        _ <- redisClient.setPosition(point)
               .tapError(e => ZIO.logError(s"[REDIS] Ошибка legacy setPosition: ${e.message}"))
               .mapError(e => new Exception(e.message))
        
        // Шаг 7: Публикация в Kafka gps-events (только при движении)
        _ <- ZIO.when(shouldPublish)(
               kafkaProducer.publishGpsEvent(point)
                 .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events vehicleId=$vehicleId"))
                 .tapError(e => ZIO.logError(s"[KAFKA] Ошибка публикации: ${e.message}"))
                 .mapError(e => new Exception(e.message))
             )
        
        // Шаг 8: Обогащённое сообщение GpsEventMessage с контекстом из DeviceData
        // DeviceData содержит organizationId, speedLimit, флаги маршрутизации —
        // всё что нужно downstream-сервисам чтобы НЕ ходить в БД самостоятельно
        _ <- deviceData match
          case Some(dd) if shouldPublish =>
            val msg = GpsEventMessage(
              vehicleId = vehicleId,
              organizationId = dd.organizationId,
              imei = imei,
              latitude = point.latitude,
              longitude = point.longitude,
              altitude = point.altitude,
              speed = point.speed,
              course = point.angle,
              satellites = point.satellites,
              deviceTime = point.timestamp,
              serverTime = now.toEpochMilli,
              hasGeozones = dd.hasGeozones,
              hasSpeedRules = dd.hasSpeedRules,
              hasRetranslation = dd.hasRetranslation,
              retranslationTargets = if dd.retranslationTargets.isEmpty then None else Some(dd.retranslationTargets),
              isMoving = shouldPublish,
              isValid = true,
              protocol = parser.protocolName
            )
            for
              // Публикация в gps-events-rules (геозоны + правила скорости)
              _ <- ZIO.when(dd.needsRulesCheck)(
                kafkaProducer.publishGpsRulesEvent(msg)
                  .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-rules vehicleId=$vehicleId"))
                  .tapError(e => ZIO.logError(s"[KAFKA] Ошибка rules: ${e.message}"))
                  .mapError(e => new Exception(e.message))
              )
              // Публикация в gps-events-retranslation (пересылка в внешние системы)
              _ <- ZIO.when(dd.hasRetranslation)(
                kafkaProducer.publishGpsRetranslationEvent(msg)
                  .tap(_ => ZIO.logDebug(s"[KAFKA] Точка → gps-events-retranslation vehicleId=$vehicleId"))
                  .tapError(e => ZIO.logError(s"[KAFKA] Ошибка retranslation: ${e.message}"))
                  .mapError(e => new Exception(e.message))
              )
            yield ()
          case _ => ZIO.unit
      yield point
    
    override def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress): UIO[Unit] =
      val effect = for
        now <- Clock.instant
        ip = remoteAddress.getAddress.getHostAddress
        port = remoteAddress.getPort
        
        // HMSET device:{imei} — записываем connection поля в единый Redis HASH
        // Другие сервисы (WebSocket, API, мониторинг) видят что устройство подключено
        connectionFields = DeviceData.connectionToHash(
          instanceId = "cm-instance",  // TODO: брать из конфигурации (для нескольких CM инстансов)
          protocol = parser.protocolName,
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
      protocolName: String,
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
            protocol = protocolName,
            instanceId = instanceId
          )
          kafkaProducer.publishUnknownGpsEvent(point)
            .tapError(e => ZIO.logError(s"[UNKNOWN-DATA] Ошибка публикации: ${e.message}"))
            .ignore
        }
        _ <- ZIO.logDebug(s"[UNKNOWN-DATA] IMEI=$imei: опубликовано ${rawPoints.size} точек в unknown-gps-events")
      yield rawPoints.size
  
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
      result <- service.processImeiPacket(buffer, remoteAddr, parser.protocolName)
      (imei, vehicleId, prevPosition, deviceData) = result
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      
      // Обновляем состояние соединения — сохраняем DeviceData из Redis HASH
      // DeviceData содержит ВСЕ: vehicleId, organizationId, флаги, предыдущую позицию
      _ <- stateRef.update { state =>
        val updated = deviceData match
          case Some(dd) => state.withImeiAndDeviceData(imei, vehicleId, now, dd)
          case None     => state.withImei(imei, vehicleId, now)
        prevPosition.fold(updated)(pos => updated.updatePosition(vehicleId, pos))
      }
      _ <- ZIO.logDebug(s"[HANDLER] Состояние обновлено: IMEI=$imei, vehicleId=$vehicleId, deviceData=${deviceData.isDefined}")
      
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
      // Это нужно для IdleConnectionWatcher - чтобы не отключать активные соединения
      _ <- registry.updateLastActivity(imei)
      
      // Получаем предыдущую позицию из кэша (для фильтрации)
      prevPosition = state.getPosition(vehicleId)
      
      // Обрабатываем пакет через сервис (processDataPacket сделает fresh HGETALL!)
      // Передаём deviceData как fallback — если HGETALL не сработает, используем кэш
      result <- service.processDataPacket(buffer, imei, vehicleId, prevPosition, state.deviceData)
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
      totalCount <- service.processUnknownDataPacket(buffer, imei, parser.protocolName, "cm-instance")
      
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
