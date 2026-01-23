package com.wayrecall.tracker.filter

import zio.*
import com.wayrecall.tracker.domain.{GpsRawPoint, GpsPoint, GeoMath, FilterError}
import com.wayrecall.tracker.config.{DeadReckoningFilterConfig, DynamicConfigService, FilterConfig}

/**
 * Фильтр Dead Reckoning - валидация GPS координат
 * Чисто функциональный интерфейс
 * 
 * ⚡ Использует DynamicConfigService для получения конфигурации
 * Чтение конфига: ~10ns (Ref.get, in-memory)
 */
trait DeadReckoningFilter:
  def validate(point: GpsRawPoint): IO[FilterError, Unit]
  def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): IO[FilterError, Unit]

object DeadReckoningFilter:
  
  // Accessor методы
  def validate(point: GpsRawPoint): ZIO[DeadReckoningFilter, FilterError, Unit] =
    ZIO.serviceWithZIO(_.validate(point))
  
  def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): ZIO[DeadReckoningFilter, FilterError, Unit] =
    ZIO.serviceWithZIO(_.validateWithPrev(point, prev))
  
  /**
   * Live реализация с динамической конфигурацией
   */
  final case class Live(configService: DynamicConfigService) extends DeadReckoningFilter:
    
    // Максимальное время из будущего (5 минут в миллисекундах)
    private val maxFutureMs = 5L * 60L * 1000L
    
    override def validate(point: GpsRawPoint): IO[FilterError, Unit] =
      for
        // ⚡ КРИТИЧНО: ~10ns чтение из Ref (in-memory), НЕ Redis!
        config <- configService.getFilterConfig
        _ <- validateSpeed(point, config)
        _ <- validateCoordinates(point)
        _ <- validateTimestamp(point)
      yield ()
    
    override def validateWithPrev(point: GpsRawPoint, prev: Option[GpsPoint]): IO[FilterError, Unit] =
      for
        config <- configService.getFilterConfig
        _ <- validateSpeed(point, config)
        _ <- validateCoordinates(point)
        _ <- validateTimestamp(point)
        _ <- ZIO.foreach(prev)(validateNoTeleportation(point, _, config)).unit
      yield ()
    
    private def validateSpeed(point: GpsRawPoint, config: FilterConfig): IO[FilterError, Unit] =
      ZIO.fail(FilterError.ExcessiveSpeed(point.speed, config.deadReckoningMaxSpeedKmh))
        .when(point.speed > config.deadReckoningMaxSpeedKmh)
        .unit
    
    private def validateCoordinates(point: GpsRawPoint): IO[FilterError, Unit] =
      val validLat = point.latitude >= -90 && point.latitude <= 90
      val validLon = point.longitude >= -180 && point.longitude <= 180
      
      ZIO.fail(FilterError.InvalidCoordinates(point.latitude, point.longitude))
        .unless(validLat && validLon)
        .unit
    
    private def validateTimestamp(point: GpsRawPoint): IO[FilterError, Unit] =
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
        ZIO.fail(FilterError.FutureTimestamp(point.timestamp, now))
          .when(point.timestamp > now + maxFutureMs)
          .unit
      }
    
    private def validateNoTeleportation(point: GpsRawPoint, prev: GpsPoint, config: FilterConfig): IO[FilterError, Unit] =
      // Используем GeoMath.haversineDistance вместо дублирования
      val distance = GeoMath.haversineDistance(
        point.latitude, point.longitude,
        prev.latitude, prev.longitude
      )
      val timeDiff = Math.abs(point.timestamp - prev.timestamp) / 1000.0
      val effectiveTime = if timeDiff < 1 then 1.0 else timeDiff
      val maxDistance = config.deadReckoningMaxJumpMeters * effectiveTime / config.deadReckoningMaxJumpSeconds
      
      ZIO.fail(FilterError.Teleportation(distance, maxDistance))
        .when(distance > maxDistance)
        .unit
  
  /**
   * ZIO Layer с динамической конфигурацией
   */
  val live: ZLayer[DynamicConfigService, Nothing, DeadReckoningFilter] =
    ZLayer.fromFunction(Live(_))
  
  /**
   * ZIO Layer со статической конфигурацией (для обратной совместимости и тестов)
   */
  val staticLive: ZLayer[DeadReckoningFilterConfig, Nothing, DeadReckoningFilter] =
    ZLayer {
      for
        staticConfig <- ZIO.service[DeadReckoningFilterConfig]
        configRef <- Ref.make(FilterConfig(
          deadReckoningMaxSpeedKmh = staticConfig.maxSpeedKmh,
          deadReckoningMaxJumpMeters = staticConfig.maxJumpMeters,
          deadReckoningMaxJumpSeconds = staticConfig.maxJumpSeconds
        ))
        // Создаем mock DynamicConfigService для статической конфигурации
        mockConfigService = new DynamicConfigService:
          def getFilterConfig: UIO[FilterConfig] = configRef.get
          def updateFilterConfig(config: FilterConfig): Task[Unit] = configRef.set(config)
          def subscribeToChanges: Task[Unit] = ZIO.unit
      yield Live(mockConfigService)
    }
