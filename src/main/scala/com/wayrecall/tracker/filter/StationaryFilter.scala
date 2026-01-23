package com.wayrecall.tracker.filter

import zio.*
import com.wayrecall.tracker.domain.GpsPoint
import com.wayrecall.tracker.config.{StationaryFilterConfig, DynamicConfigService, FilterConfig}

/**
 * Фильтр стоянок - определяет, нужно ли публиковать точку в Kafka
 * 
 * ⚡ Использует DynamicConfigService для получения конфигурации
 * Чтение конфига: ~10ns (Ref.get, in-memory)
 */
trait StationaryFilter:
  /**
   * Определяет, нужно ли публиковать точку
   * true = движение (публикуем), false = стоянка (не публикуем)
   */
  def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): UIO[Boolean]

object StationaryFilter:
  
  // Accessor метод
  def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): URIO[StationaryFilter, Boolean] =
    ZIO.serviceWithZIO(_.shouldPublish(point, prev))
  
  /**
   * Live реализация с динамической конфигурацией
   */
  final case class Live(configService: DynamicConfigService) extends StationaryFilter:
    
    override def shouldPublish(point: GpsPoint, prev: Option[GpsPoint]): UIO[Boolean] =
      for
        // ⚡ КРИТИЧНО: ~10ns чтение из Ref (in-memory), НЕ Redis!
        config <- configService.getFilterConfig
        result = prev match
          case None => 
            // Первая точка - всегда публикуем
            true
          case Some(prevPoint) =>
            val distance = point.distanceTo(prevPoint)
            val isMoving = distance >= config.stationaryMinDistanceMeters || 
                          point.speed >= config.stationaryMinSpeedKmh
            isMoving
      yield result
  
  /**
   * ZIO Layer с динамической конфигурацией
   */
  val live: ZLayer[DynamicConfigService, Nothing, StationaryFilter] =
    ZLayer.fromFunction(Live(_))
  
  /**
   * ZIO Layer со статической конфигурацией (для обратной совместимости и тестов)
   */
  val staticLive: ZLayer[StationaryFilterConfig, Nothing, StationaryFilter] =
    ZLayer {
      for
        staticConfig <- ZIO.service[StationaryFilterConfig]
        configRef <- Ref.make(FilterConfig(
          stationaryMinDistanceMeters = staticConfig.minDistanceMeters,
          stationaryMinSpeedKmh = staticConfig.minSpeedKmh
        ))
        // Mock DynamicConfigService
        mockConfigService = new DynamicConfigService:
          def getFilterConfig: UIO[FilterConfig] = configRef.get
          def updateFilterConfig(config: FilterConfig): Task[Unit] = configRef.set(config)
          def subscribeToChanges: Task[Unit] = ZIO.unit
      yield Live(mockConfigService)
    }
