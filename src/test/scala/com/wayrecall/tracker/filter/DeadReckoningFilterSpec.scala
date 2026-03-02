package com.wayrecall.tracker.filter

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.{GpsRawPoint, GpsPoint, FilterError}
import com.wayrecall.tracker.config.{DynamicConfigService, FilterConfig}
import java.lang.System as JSystem

/**
 * Тесты для DeadReckoningFilter
 * 
 * Проверяем:
 * 1. Фильтрация по максимальной скорости
 * 2. Валидация координат
 * 3. Валидация timestamp (не из будущего)
 * 4. Детектирование телепортации
 */
object DeadReckoningFilterSpec extends ZIOSpecDefault:
  
  // Тестовая конфигурация
  private val testConfig = FilterConfig(
    deadReckoningMaxSpeedKmh = 300,
    deadReckoningMaxJumpMeters = 1000,
    deadReckoningMaxJumpSeconds = 1,
    stationaryMinDistanceMeters = 20,
    stationaryMinSpeedKmh = 2
  )
  
  // Mock DynamicConfigService
  private val mockConfigService: UIO[DynamicConfigService] = 
    Ref.make(testConfig).map { ref =>
      new DynamicConfigService:
        def getFilterConfig: UIO[FilterConfig] = ref.get
        def updateFilterConfig(config: FilterConfig): Task[Unit] = ref.set(config)
        def subscribeToChanges: Task[Unit] = ZIO.unit
    }
  
  // Фиксированное время для тестов (чтобы не зависеть от реальных часов и совпадать с ZIO TestClock)
  private val fixedNow = 1772280000000L // 2026-03-01 12:00:00 UTC в миллисекундах

  // Устанавливаем TestClock на fixedNow перед каждым тестом
  private val adjustClock = TestClock.adjust(Duration.fromMillis(fixedNow))

  // Создаём тестовую точку
  private def makePoint(
    lat: Double = 55.7558,
    lon: Double = 37.6173,
    speed: Int = 60,
    timestamp: Long = fixedNow
  ): GpsRawPoint = GpsRawPoint(
    imei = "123456789012345",
    latitude = lat,
    longitude = lon,
    altitude = 100,
    speed = speed,
    angle = 0,
    satellites = 12,
    timestamp = timestamp
  )
  
  private def makeGpsPoint(
    lat: Double = 55.7558,
    lon: Double = 37.6173,
    timestamp: Long = fixedNow
  ): GpsPoint = GpsPoint(
    vehicleId = 1L,
    latitude = lat,
    longitude = lon,
    altitude = 100,
    speed = 60,
    angle = 0,
    satellites = 12,
    timestamp = timestamp
  )
  
  def spec = suite("DeadReckoningFilter")(
    
    suite("validate - скорость")(
      
      test("пропускает точку с нормальной скоростью") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(speed = 100)).either
        yield assertTrue(result.isRight)
      },
      
      test("отклоняет точку с превышением скорости") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(speed = 350)).either
        yield assertTrue(result.isLeft)
      },
      
      test("пропускает точку на границе лимита") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(speed = 300)).either
        yield assertTrue(result.isRight)
      }
    ),
    
    suite("validate - координаты")(
      
      test("пропускает валидные координаты") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(lat = 55.0, lon = 37.0)).either
        yield assertTrue(result.isRight)
      },
      
      test("отклоняет невалидную широту (> 90)") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(lat = 95.0)).either
        yield assertTrue(result.isLeft)
      },
      
      test("отклоняет невалидную широту (< -90)") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(lat = -95.0)).either
        yield assertTrue(result.isLeft)
      },
      
      test("отклоняет невалидную долготу (> 180)") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(lon = 185.0)).either
        yield assertTrue(result.isLeft)
      },
      
      test("пропускает крайние допустимые координаты") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(lat = 90.0, lon = 180.0)).either
        yield assertTrue(result.isRight)
      }
    ),
    
    suite("validate - timestamp")(
      
      test("пропускает текущий timestamp") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(timestamp = fixedNow)).either
        yield assertTrue(result.isRight)
      },
      
      test("пропускает прошлый timestamp") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validate(makePoint(timestamp = fixedNow - 3600000)).either
        yield assertTrue(result.isRight)
      },
      
      test("отклоняет timestamp из далёкого будущего (>5 минут)") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          futureTime = fixedNow + 10 * 60 * 1000  // +10 минут
          result <- filter.validate(makePoint(timestamp = futureTime)).either
        yield assertTrue(result.isLeft)
      }
    ),
    
    suite("validateWithPrev - телепортация")(
      
      test("пропускает последовательные близкие точки") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          prev = makeGpsPoint(lat = 55.7558, lon = 37.6173, timestamp = fixedNow - 1000)
          current = makePoint(lat = 55.7559, lon = 37.6174, timestamp = fixedNow)  // ~100м за 1с
          result <- filter.validateWithPrev(current, Some(prev)).either
        yield assertTrue(result.isRight)
      },
      
      test("отклоняет телепортацию (слишком далеко за 1 секунду)") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          prev = makeGpsPoint(lat = 55.7558, lon = 37.6173, timestamp = fixedNow - 1000)
          current = makePoint(lat = 56.0, lon = 38.0, timestamp = fixedNow)  // ~50км за 1с!
          result <- filter.validateWithPrev(current, Some(prev)).either
        yield assertTrue(result.isLeft)
      },
      
      test("работает без предыдущей точки") {
        for
          _ <- adjustClock
          configService <- mockConfigService
          filter = DeadReckoningFilter.Live(configService)
          result <- filter.validateWithPrev(makePoint(), None).either
        yield assertTrue(result.isRight)
      }
    )
  )
