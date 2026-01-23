package com.wayrecall.tracker.domain

import zio.json.*

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
