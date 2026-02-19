package com.wayrecall.tracker.domain

import zio.json.*
import scala.util.Try
import java.time.Instant

/**
 * Географические расчёты - чистые функции
 */
object GeoMath:
  
  private val EarthRadiusKm: Double = 6371.0
  
  /**
   * Вычисляет расстояние между двумя географическими точками в метрах
   * Использует формулу Хаверсина (Haversine formula)
   * 
   * Complexity: O(1)
   * Precision: ~0.5% на расстояниях до 1000 км
   * 
   * @param lat1 Широта первой точки (градусы)
   * @param lon1 Долгота первой точки (градусы)
   * @param lat2 Широта второй точки (градусы)
   * @param lon2 Долгота второй точки (градусы)
   * @return Расстояние в метрах
   */
  def haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    val latRad1 = Math.toRadians(lat1)
    val latRad2 = Math.toRadians(lat2)
    val deltaLatRad = Math.toRadians(lat2 - lat1)
    val deltaLonRad = Math.toRadians(lon2 - lon1)
    
    val sinDeltaLat = Math.sin(deltaLatRad / 2)
    val sinDeltaLon = Math.sin(deltaLonRad / 2)
    
    val a = sinDeltaLat * sinDeltaLat +
            Math.cos(latRad1) * Math.cos(latRad2) * sinDeltaLon * sinDeltaLon
    
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    EarthRadiusKm * c * 1000 // метры

/**
 * GPS точка с координатами, высотой, скоростью и углом наклона
 */
case class GpsPoint(
    vehicleId: Long,
    latitude: Double,      // градусы (-90..90)
    longitude: Double,     // градусы (-180..180)
    altitude: Int,         // метры
    speed: Int,            // км/ч
    angle: Int,            // градусы (0-360)
    satellites: Int,       // количество спутников
    timestamp: Long        // миллисекунды
) derives JsonCodec:
  
  /**
   * Вычисляет расстояние между этой точкой и другой в метрах
   * Использует формулу Хаверсина
   */
  def distanceTo(other: GpsPoint): Double =
    GeoMath.haversineDistance(latitude, longitude, other.latitude, other.longitude)

/**
 * Конфигурация транспортного средства — маршрутизационные флаги
 * 
 * Кешируется в ConnectionState при аутентификации, обновляется через device-events.
 * Определяет, в какие Kafka топики маршрутизировать GPS точки.
 */
case class VehicleConfig(
    organizationId: Long,
    imei: String,
    name: String = "",
    hasGeozones: Boolean = false,       // Есть геозоны → gps-events-rules
    hasSpeedRules: Boolean = false,     // Есть правила скорости → gps-events-rules
    hasRetranslation: Boolean = false,  // Есть ретрансляция → gps-events-retranslation
    retranslationTargets: List[String] = List.empty  // ["wialon-42", "webhook-7"]
) derives JsonCodec:
  /** Нужно ли публиковать в топик gps-events-rules */
  def needsRulesCheck: Boolean = hasGeozones || hasSpeedRules

// ═══════════════════════════════════════════════════════════════════════════
// DeviceData — ЕДИНАЯ структура из Redis HASH device:{imei}
// ═══════════════════════════════════════════════════════════════════════════
//
// Все данные об устройстве хранятся в ОДНОМ HASH ключе device:{imei}:
//
// ┌───────────────────────────────────────────────────────────────────────┐
// │ CONTEXT FIELDS (записывает Device Manager)                           │
// │   vehicleId, organizationId, name, speedLimit, hasGeozones,          │
// │   hasSpeedRules, hasRetranslation, retranslationTargets,             │
// │   fuelTankVolume, sensorConfig                                       │
// ├───────────────────────────────────────────────────────────────────────┤
// │ POSITION FIELDS (записывает Connection Manager)                      │
// │   lat, lon, speed, course, altitude, satellites, time, isMoving      │
// ├───────────────────────────────────────────────────────────────────────┤
// │ CONNECTION FIELDS (записывает Connection Manager)                    │
// │   instanceId, protocol, connectedAt, lastActivity, remoteAddress     │
// └───────────────────────────────────────────────────────────────────────┘
//
// CM читает ВСЁ одним HGETALL на КАЖДЫЙ data-пакет, чтобы:
// - Обогатить точку (organizationId, speedLimit и т.д.)
// - Получить актуальные флаги (hasGeozones мог измениться)
// - Иметь prev position для Dead Reckoning (из того же запроса)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Полные данные устройства из Redis HASH device:{imei}
 *
 * Одна структура — три секции (context / position / connection).
 * Device Manager пишет context, Connection Manager пишет position + connection.
 *
 * @param vehicleId       ID транспортного средства в PostgreSQL
 * @param organizationId  ID организации (мультитенантность)
 * @param name            Название ТС ("Газель АА123")
 * @param speedLimit      Ограничение скорости км/ч, None = нет ограничения
 * @param hasGeozones     Есть привязанные геозоны → gps-events-rules
 * @param hasSpeedRules   Есть правила скорости → gps-events-rules
 * @param hasRetranslation  Есть ретрансляция → gps-events-retranslation
 * @param retranslationTargets  Список целей ретрансляции: ["wialon-42", "webhook-7"]
 * @param fuelTankVolume  Объём бака в литрах (для калибровки датчика топлива)
 * @param lat             Последняя широта (записывает CM)
 * @param lon             Последняя долгота (записывает CM)
 * @param speed           Последняя скорость км/ч
 * @param course          Последний курс 0-359
 * @param altitude        Последняя высота в метрах
 * @param satellites      Кол-во спутников
 * @param time            Время последней позиции (ISO8601)
 * @param isMoving        Движется или стоит (Stationary Filter)
 * @param instanceId      ID инстанса CM (cm-teltonika-1)
 * @param protocol        Протокол (teltonika, wialon, ruptela, navtelecom)
 * @param connectedAt     Время подключения ISO8601
 * @param lastActivity    Время последней активности ISO8601
 * @param remoteAddress   IP:port трекера
 */
case class DeviceData(
    // === CONTEXT (Device Manager записывает при CRUD) ===
    vehicleId: Long,
    organizationId: Long,
    name: String = "",
    speedLimit: Option[Int] = None,
    hasGeozones: Boolean = false,
    hasSpeedRules: Boolean = false,
    hasRetranslation: Boolean = false,
    retranslationTargets: List[String] = List.empty,
    fuelTankVolume: Option[Double] = None,
    // === POSITION (Connection Manager записывает при DATA пакете) ===
    lat: Option[Double] = None,
    lon: Option[Double] = None,
    speed: Option[Int] = None,
    course: Option[Int] = None,
    altitude: Option[Int] = None,
    satellites: Option[Int] = None,
    time: Option[String] = None,       // ISO8601 строка
    isMoving: Option[Boolean] = None,
    // === CONNECTION (Connection Manager записывает при CONNECT) ===
    instanceId: Option[String] = None,
    protocol: Option[String] = None,
    connectedAt: Option[String] = None,
    lastActivity: Option[String] = None,
    remoteAddress: Option[String] = None
):
  /** Нужно ли публиковать в gps-events-rules */
  def needsRulesCheck: Boolean = hasGeozones || hasSpeedRules

  /** Предыдущая позиция как GpsPoint (для Dead Reckoning фильтра) */
  def previousPosition: Option[GpsPoint] =
    for
      la <- lat
      lo <- lon
    yield GpsPoint(
      vehicleId = vehicleId,
      latitude = la,
      longitude = lo,
      altitude = altitude.getOrElse(0),
      speed = speed.getOrElse(0),
      angle = course.getOrElse(0),
      satellites = satellites.getOrElse(0),
      timestamp = time.flatMap(s => Try(Instant.parse(s).toEpochMilli).toOption).getOrElse(0L)
    )

  /** Конвертация в VehicleConfig (для обратной совместимости) */
  def toVehicleConfig: VehicleConfig = VehicleConfig(
    organizationId = organizationId,
    imei = "",  // IMEI есть в ключе, а не в данных
    name = name,
    hasGeozones = hasGeozones,
    hasSpeedRules = hasSpeedRules,
    hasRetranslation = hasRetranslation,
    retranslationTargets = retranslationTargets
  )

object DeviceData:
  /**
   * Парсинг из Redis HASH (Map[String, String]).
   *
   * Redis хранит все поля как строки.
   * Обязательные поля: vehicleId, organizationId.
   * Если нет vehicleId — устройство не зарегистрировано (None).
   */
  def fromRedisHash(hash: Map[String, String]): Option[DeviceData] =
    for
      vehicleId <- hash.get("vehicleId").flatMap(_.toLongOption)
      orgId     <- hash.get("organizationId").flatMap(_.toLongOption)
    yield DeviceData(
      vehicleId = vehicleId,
      organizationId = orgId,
      name = hash.getOrElse("name", ""),
      speedLimit = hash.get("speedLimit").filter(_.nonEmpty).flatMap(_.toIntOption),
      hasGeozones = hash.get("hasGeozones").contains("true"),
      hasSpeedRules = hash.get("hasSpeedRules").contains("true"),
      hasRetranslation = hash.get("hasRetranslation").contains("true"),
      retranslationTargets = hash.get("retranslationTargets")
        .filter(_.nonEmpty)
        .map(_.split(",").toList)
        .getOrElse(List.empty),
      fuelTankVolume = hash.get("fuelTankVolume").flatMap(_.toDoubleOption),
      lat = hash.get("lat").flatMap(_.toDoubleOption),
      lon = hash.get("lon").flatMap(_.toDoubleOption),
      speed = hash.get("speed").flatMap(_.toIntOption),
      course = hash.get("course").flatMap(_.toIntOption),
      altitude = hash.get("altitude").flatMap(_.toIntOption),
      satellites = hash.get("satellites").flatMap(_.toIntOption),
      time = hash.get("time").filter(_.nonEmpty),
      isMoving = hash.get("isMoving").map(_ == "true"),
      instanceId = hash.get("instanceId").filter(_.nonEmpty),
      protocol = hash.get("protocol"),
      connectedAt = hash.get("connectedAt").filter(_.nonEmpty),
      lastActivity = hash.get("lastActivity").filter(_.nonEmpty),
      remoteAddress = hash.get("remoteAddress")
    )

  /**
   * Position поля для HMSET после обработки GPS пакета.
   *
   * Обновляет только position + lastActivity (connection fields не трогаем).
   */
  def positionToHash(point: GpsPoint, isMoving: Boolean, now: Instant): Map[String, String] =
    Map(
      "lat" -> point.latitude.toString,
      "lon" -> point.longitude.toString,
      "speed" -> point.speed.toString,
      "course" -> point.angle.toString,
      "altitude" -> point.altitude.toString,
      "satellites" -> point.satellites.toString,
      "time" -> Instant.ofEpochMilli(point.timestamp).toString,
      "isMoving" -> isMoving.toString,
      "lastActivity" -> now.toString
    )

  /**
   * Connection поля для HMSET при подключении трекера.
   */
  def connectionToHash(instanceId: String, protocol: String, connectedAt: Instant, remoteAddress: String): Map[String, String] =
    Map(
      "instanceId" -> instanceId,
      "protocol" -> protocol,
      "connectedAt" -> connectedAt.toString,
      "lastActivity" -> connectedAt.toString,
      "remoteAddress" -> remoteAddress
    )

  /** Имена connection полей для HDEL при отключении */
  val connectionFieldNames: List[String] = List("instanceId", "protocol", "connectedAt", "remoteAddress")

/**
 * Обогащённое GPS сообщение для Kafka — содержит точку + метаданные маршрутизации
 * 
 * Это формат сообщения в топиках gps-events, gps-events-rules, gps-events-retranslation.
 * Consumers используют поля hasGeozones, hasRetranslation для маршрутизации.
 */
case class GpsEventMessage(
    vehicleId: Long,
    organizationId: Long,
    imei: String,
    latitude: Double,
    longitude: Double,
    altitude: Int,
    speed: Int,
    course: Int,            // angle в терминах GPS
    satellites: Int,
    deviceTime: Long,       // Время от трекера
    serverTime: Long,       // Время сервера при получении
    // Маршрутизационные флаги
    hasGeozones: Boolean = false,
    hasSpeedRules: Boolean = false,
    hasRetranslation: Boolean = false,
    retranslationTargets: Option[List[String]] = None,
    // Метаданные
    isMoving: Boolean = true,
    isValid: Boolean = true,
    protocol: String = "",
    instanceId: String = ""
) derives JsonCodec

/**
 * Сырые GPS данные парсеные из протокола
 */
case class GpsRawPoint(
    imei: String,
    latitude: Double,
    longitude: Double,
    altitude: Int,
    speed: Int,
    angle: Int,
    satellites: Int,
    timestamp: Long
):
  /**
   * Преобразует сырую точку в валидную GpsPoint с vehicleId
   */
  def toValidated(vehicleId: Long): GpsPoint =
    GpsPoint(
      vehicleId = vehicleId,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude,
      speed = speed,
      angle = angle,
      satellites = satellites,
      timestamp = timestamp
    )
  
  /**
   * Вычисляет расстояние до другой точки
   */
  def distanceTo(other: GpsPoint): Double =
    GeoMath.haversineDistance(latitude, longitude, other.latitude, other.longitude)

/**
 * Информация о подключении трекера
 */
case class ConnectionInfo(
    imei: String,
    connectedAt: Long,
    remoteAddress: String,
    port: Int
) derives JsonCodec

/**
 * Информация о транспортном средстве
 */
case class Vehicle(
    id: Long,
    imei: String,
    name: String,
    deviceType: String
) derives JsonCodec

/**
 * Причина отключения трекера
 */
enum DisconnectReason derives JsonCodec:
  case GracefulClose      // Трекер сам закрыл соединение (норма)
  case IdleTimeout        // Принудительно отключён из-за неактивности
  case ReadTimeout        // Timeout на чтение данных (Netty)
  case WriteTimeout       // Timeout на запись данных (Netty)
  case ConnectionReset    // TCP reset (сетевая проблема)
  case ProtocolError      // Ошибка парсинга протокола
  case ServerShutdown     // Сервер остановлен
  case AdminDisconnect    // Отключен администратором (устройство заблокировано)
  case Unknown            // Неизвестная причина

/**
 * Статус устройства
 */
case class DeviceStatus(
    imei: String,
    vehicleId: Long,
    isOnline: Boolean,
    lastSeen: Long,
    lastLatitude: Option[Double] = None,
    lastLongitude: Option[Double] = None,
    disconnectReason: Option[DisconnectReason] = None,  // Причина отключения (только когда isOnline = false)
    sessionDurationMs: Option[Long] = None              // Длительность сессии (только при отключении)
) derives JsonCodec

/**
 * Extension methods для GpsPoint
 */
extension (p1: GpsPoint)
  /**
   * Alias для distanceTo - вычисляет расстояние до другой точки по формуле Haversine (в метрах)
   */
  def distance(p2: GpsPoint): Double = p1.distanceTo(p2)

/**
 * Событие: неизвестное устройство пытается подключиться
 * 
 * Публикуется в Kafka топик "unknown-devices" когда трекер
 * с незарегистрированным IMEI пытается подключиться.
 * 
 * Может использоваться для:
 * - Автоматической регистрации (provisioning)
 * - Уведомления администраторов
 * - Аудита попыток подключения
 */
case class UnknownDeviceEvent(
    imei: String,
    protocol: String,        // teltonika, wialon, ruptela, navtelecom
    remoteAddress: String,   // IP адрес
    port: Int,
    timestamp: Long,
    connectionAttempt: Int = 1  // Номер попытки (для rate limit)
) derives JsonCodec

/**
 * GPS точка от неизвестного (незарегистрированного) трекера
 * 
 * Публикуется в Kafka топик "unknown-gps-events".
 * History Writer пишет эти точки в отдельную таблицу unknown_device_positions.
 * Device Manager может показать их в веб-интерфейсе для регистрации.
 * 
 * В отличие от GpsPoint, здесь нет vehicleId — только IMEI.
 */
case class UnknownGpsPoint(
    imei: String,
    latitude: Double,
    longitude: Double,
    altitude: Int,
    speed: Int,
    angle: Int,
    satellites: Int,
    deviceTime: Long,        // Время от трекера
    serverTime: Long,        // Время сервера
    protocol: String,        // teltonika, wialon, ruptela, navtelecom
    instanceId: String       // ID инстанса CM (для диагностики)
) derives JsonCodec

/**
 * Событие изменения конфигурации устройства из Kafka топика device-events
 * 
 * Публикуется Device Manager при:
 * - Обновлении маршрутизационных флагов (геозоны, ретрансляция)
 * - Привязке/отвязке правил к транспортному средству
 * - Изменении целей ретрансляции
 * 
 * CM обрабатывает эти события для обновления Redis-кеша VehicleConfig
 */
case class DeviceEvent(
    imei: String,
    vehicleId: Long,
    organizationId: Long,
    eventType: String,             // "config_updated", "config_deleted"
    vehicleConfig: Option[VehicleConfig] = None,
    timestamp: Long = 0L
) derives JsonCodec
