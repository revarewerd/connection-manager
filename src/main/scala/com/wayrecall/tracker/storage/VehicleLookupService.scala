package com.wayrecall.tracker.storage

import zio.*
import com.wayrecall.tracker.domain.{VehicleInfo, RedisError}

/**
 * Сервис для получения VehicleId с fallback на PostgreSQL
 * 
 * ✅ Сначала ищет в Redis (быстро)
 * ✅ Если не найдено — запрос в PostgreSQL
 * ✅ При успехе из PostgreSQL — кеширует в Redis
 * ✅ Проверяет, что устройство активно (enabled)
 */
trait VehicleLookupService:
  /**
   * Получить vehicleId по IMEI
   * 
   * @return vehicleId если устройство найдено И активно
   */
  def getVehicleId(imei: String): IO[RedisError, Option[Long]]
  
  /**
   * Синхронизировать все активные устройства в Redis
   * Используется при старте сервиса
   */
  def syncAllToRedis: Task[Int]
  
  /**
   * Инвалидировать кеш для IMEI
   */
  def invalidateCache(imei: String): Task[Unit]

object VehicleLookupService:
  
  // Accessor методы
  def getVehicleId(imei: String): ZIO[VehicleLookupService, RedisError, Option[Long]] =
    ZIO.serviceWithZIO(_.getVehicleId(imei))
  
  def syncAllToRedis: RIO[VehicleLookupService, Int] =
    ZIO.serviceWithZIO(_.syncAllToRedis)
  
  def invalidateCache(imei: String): RIO[VehicleLookupService, Unit] =
    ZIO.serviceWithZIO(_.invalidateCache(imei))
  
  /**
   * Live реализация с Redis + PostgreSQL fallback
   */
  final case class Live(
      redis: RedisClient,
      deviceRepo: DeviceRepository,
      cacheTtlSeconds: Long = 3600 // 1 час по умолчанию
  ) extends VehicleLookupService:
    
    override def getVehicleId(imei: String): IO[RedisError, Option[Long]] =
      // 1. Сначала пробуем Redis
      redis.getVehicleId(imei).flatMap {
        case Some(vehicleId) =>
          // Найдено в Redis — возвращаем
          ZIO.logDebug(s"VehicleId for IMEI=$imei found in Redis: $vehicleId") *>
          ZIO.succeed(Some(vehicleId))
        
        case None =>
          // Не найдено в Redis — fallback на PostgreSQL
          ZIO.logDebug(s"VehicleId for IMEI=$imei not in Redis, checking PostgreSQL") *>
          fallbackToPostgres(imei)
      }
    
    /**
     * Fallback на PostgreSQL при отсутствии данных в Redis
     * 
     * Реализует паттерн Cache-Aside:
     * 1. Запрос в PostgreSQL
     * 2. Если найдено И активно → кешируем в Redis с TTL
     * 3. Если найдено но отключено → возвращаем None (не кешируем)
     * 4. Если не найдено → возвращаем None
     * 
     * @param imei IMEI устройства
     * @return vehicleId если устройство активно, None иначе
     */
    private def fallbackToPostgres(imei: String): IO[RedisError, Option[Long]] =
      deviceRepo.findByImei(imei)
        .flatMap {
          case Some(vehicle) if vehicle.isActive =>
            // Найдено и активно — кешируем в Redis и возвращаем
            ZIO.logInfo(s"VehicleId for IMEI=$imei found in PostgreSQL: ${vehicle.id}, caching to Redis") *>
            redis.setVehicleId(imei, vehicle.id, cacheTtlSeconds) *>
            ZIO.succeed(Some(vehicle.id))
          
          case Some(vehicle) =>
            // Найдено но неактивно
            ZIO.logWarning(s"Device IMEI=$imei exists but is disabled") *>
            ZIO.succeed(None)
          
          case None =>
            // Не найдено нигде
            ZIO.logDebug(s"VehicleId for IMEI=$imei not found anywhere") *>
            ZIO.succeed(None)
        }
        .mapError(e => RedisError.OperationFailed(s"PostgreSQL fallback failed: ${e.getMessage}"))
    
    override def syncAllToRedis: Task[Int] =
      for
        _ <- ZIO.logInfo("Starting sync of all enabled devices to Redis...")
        devices <- deviceRepo.findAllEnabled
        
        _ <- ZIO.foreachParDiscard(devices) { device =>
          redis.setVehicleId(device.imei, device.id, cacheTtlSeconds)
            .catchAll(e => ZIO.logWarning(s"Failed to cache ${device.imei}: ${e.message}"))
        }
        
        _ <- ZIO.logInfo(s"Synced ${devices.size} devices to Redis")
      yield devices.size
    
    override def invalidateCache(imei: String): Task[Unit] =
      redis.del(s"vehicle:$imei") *>
      ZIO.logInfo(s"Cache invalidated for IMEI=$imei")
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[RedisClient & DeviceRepository, Nothing, VehicleLookupService] =
    ZLayer {
      for
        redis <- ZIO.service[RedisClient]
        deviceRepo <- ZIO.service[DeviceRepository]
      yield Live(redis, deviceRepo)
    }
  
  /**
   * Layer с кастомным TTL
   */
  def liveWithTtl(ttlSeconds: Long): ZLayer[RedisClient & DeviceRepository, Nothing, VehicleLookupService] =
    ZLayer {
      for
        redis <- ZIO.service[RedisClient]
        deviceRepo <- ZIO.service[DeviceRepository]
      yield Live(redis, deviceRepo, ttlSeconds)
    }
