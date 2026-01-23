package com.wayrecall.tracker.filter

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.GpsPoint
import com.wayrecall.tracker.config.{FilterConfig, DynamicConfigService}

/**
 * Тесты для StationaryFilter
 */
object StationaryFilterSpec extends ZIOSpecDefault:
  
  // Тестовая конфигурация
  val testFilterConfig = FilterConfig(
    deadReckoningMaxSpeedKmh = 300,
    deadReckoningMaxJumpMeters = 1000,
    deadReckoningMaxJumpSeconds = 1,
    stationaryMinDistanceMeters = 20,
    stationaryMinSpeedKmh = 2
  )
  
  // Mock DynamicConfigService для тестов
  val testConfigServiceLayer: ULayer[DynamicConfigService] =
    ZLayer.succeed(new DynamicConfigService:
      def getFilterConfig: UIO[FilterConfig] = ZIO.succeed(testFilterConfig)
      def updateFilterConfig(config: FilterConfig): Task[Unit] = ZIO.unit
      def subscribeToChanges: Task[Unit] = ZIO.unit
    )
  
  val filterLayer = testConfigServiceLayer >>> StationaryFilter.live
  
  // Базовая точка для тестов (Москва)
  def basePoint(vehicleId: Long = 1L, speed: Int = 0): GpsPoint = GpsPoint(
    vehicleId = vehicleId,
    latitude = 55.7539,
    longitude = 37.6208,
    altitude = 150,
    speed = speed,
    angle = 0,
    satellites = 10,
    timestamp = java.lang.System.currentTimeMillis()
  )
  
  def spec = suite("StationaryFilter")(
    
    test("публикует первую точку когда prev = None") {
      val point = basePoint()
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, None)
      yield assertTrue(result)
    },
    
    test("не публикует если стоим на месте (малое расстояние + низкая скорость)") {
      val prev = basePoint()
      // ~10м от предыдущей точки
      val point = prev.copy(
        latitude = prev.latitude + 0.00009,
        speed = 0
      )
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, Some(prev))
      yield assertTrue(!result)
    },
    
    test("публикует если расстояние >= минимального") {
      val prev = basePoint()
      // ~50м от предыдущей точки
      val point = prev.copy(
        latitude = prev.latitude + 0.00045,
        speed = 0
      )
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, Some(prev))
      yield assertTrue(result)
    },
    
    test("публикует если скорость >= минимальной") {
      val prev = basePoint()
      // Та же позиция, но скорость > 2 км/ч
      val point = prev.copy(speed = 5)
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, Some(prev))
      yield assertTrue(result)
    },
    
    test("не публикует при малом расстоянии И низкой скорости") {
      val prev = basePoint(speed = 1)
      // ~5м от предыдущей, скорость 1 км/ч
      val point = prev.copy(
        latitude = prev.latitude + 0.00005,
        speed = 1
      )
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, Some(prev))
      yield assertTrue(!result)
    },
    
    test("публикует при большом расстоянии даже с нулевой скоростью") {
      val prev = basePoint()
      // ~100м от предыдущей
      val point = prev.copy(
        latitude = prev.latitude + 0.0009,
        speed = 0
      )
      for
        filter <- ZIO.service[StationaryFilter]
        result <- filter.shouldPublish(point, Some(prev))
      yield assertTrue(result)
    }
  ).provideLayerShared(filterLayer)
