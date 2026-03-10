package com.wayrecall.tracker.storage

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.*

/**
 * Тесты для VehicleLookupService
 * 
 * Проверяем Cache-Aside паттерн:
 * 1. Redis hit → возвращаем сразу
 * 2. Redis miss → fallback PostgreSQL → кешируем в Redis
 * 3. Устройство неактивно → None (не кешируем)
 * 4. Устройство не найдено нигде → None
 * 5. syncAllToRedis — массовая загрузка
 * 6. invalidateCache — удаление ключа
 */
object VehicleLookupServiceSpec extends ZIOSpecDefault:
  
  // === Мок Redis ===
  private case class MockRedisClient(
      cacheRef: Ref[Map[String, Long]],
      deletedRef: Ref[List[String]]
  ) extends RedisClient:
    override def getVehicleId(imei: String): IO[RedisError, Option[Long]] =
      cacheRef.get.map(_.get(imei))
    
    override def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long): IO[RedisError, Unit] =
      cacheRef.update(_ + (imei -> vehicleId))
    
    override def del(key: String): Task[Unit] =
      deletedRef.update(_ :+ key)
    
    // Остальные методы не используются в VehicleLookupService
    override def getDeviceData(imei: String): IO[RedisError, Option[DeviceData]] = ZIO.succeed(None)
    override def updateDevicePosition(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
    override def setDeviceConnectionFields(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
    override def clearDeviceConnectionFields(imei: String): IO[RedisError, Unit] = ZIO.unit
    override def getVehicleConfig(imei: String): IO[RedisError, Option[VehicleConfig]] = ZIO.succeed(None)
    override def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]] = ZIO.succeed(None)
    override def setPosition(point: GpsPoint): IO[RedisError, Unit] = ZIO.unit
    override def registerConnection(info: ConnectionInfo): IO[RedisError, Unit] = ZIO.unit
    override def unregisterConnection(imei: String): IO[RedisError, Unit] = ZIO.unit
    override def subscribe(channel: String)(handler: String => Task[Unit]): Task[Unit] = ZIO.unit
    override def publish(channel: String, message: String): Task[Unit] = ZIO.unit
    override def psubscribe(pattern: String)(handler: (String, String) => Task[Unit]): Task[Unit] = ZIO.unit
    override def hset(key: String, values: Map[String, String]): Task[Unit] = ZIO.unit
    override def hgetall(key: String): Task[Map[String, String]] = ZIO.succeed(Map.empty)
    override def get(key: String): Task[Option[String]] = ZIO.succeed(None)
    override def zadd(key: String, score: Double, member: String): Task[Unit] = ZIO.unit
    override def zrange(key: String, start: Long, stop: Long): Task[List[String]] = ZIO.succeed(Nil)
    override def expire(key: String, seconds: Long): Task[Unit] = ZIO.unit
  
  private def makeMockRedis: UIO[MockRedisClient] =
    for
      cacheRef <- Ref.make(Map.empty[String, Long])
      deletedRef <- Ref.make(List.empty[String])
    yield MockRedisClient(cacheRef, deletedRef)
  
  private def makeMockRedisWithData(data: Map[String, Long]): UIO[MockRedisClient] =
    for
      cacheRef <- Ref.make(data)
      deletedRef <- Ref.make(List.empty[String])
    yield MockRedisClient(cacheRef, deletedRef)
  
  // === Мок DeviceRepository ===
  private case class MockDeviceRepo(
      devices: Map[String, VehicleInfo]
  ) extends DeviceRepository:
    override def findByImei(imei: String): Task[Option[VehicleInfo]] =
      ZIO.succeed(devices.get(imei))
    
    override def findAllEnabled: Task[List[VehicleInfo]] =
      ZIO.succeed(devices.values.filter(_.isActive).toList)
    
    override def isEnabled(imei: String): Task[Boolean] =
      ZIO.succeed(devices.get(imei).exists(_.isActive))
  
  // === Тестовые данные ===
  private val activeVehicle = VehicleInfo(
    id = 42, imei = "123456789012345", name = Some("Газель АА123"),
    deviceType = "teltonika", isActive = true
  )
  
  private val inactiveVehicle = VehicleInfo(
    id = 99, imei = "999999999999999", name = Some("Отключённый"),
    deviceType = "wialon", isActive = false
  )
  
  def spec = suite("VehicleLookupService")(
    
    suite("getVehicleId — Cache-Aside")(
      
      test("Redis hit → возвращает vehicleId без обращения к PostgreSQL") {
        for
          redis <- makeMockRedisWithData(Map("123456789012345" -> 42L))
          repo = MockDeviceRepo(Map.empty) // Пустой репозиторий — к нему не должны обратиться
          service = VehicleLookupService.Live(redis, repo)
          result <- service.getVehicleId("123456789012345")
        yield assertTrue(result == Some(42L))
      },
      
      test("Redis miss + PostgreSQL hit (активное) → кешируем и возвращаем") {
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map("123456789012345" -> activeVehicle))
          service = VehicleLookupService.Live(redis, repo)
          result <- service.getVehicleId("123456789012345")
          // Проверяем что закешировалось
          cached <- redis.cacheRef.get
        yield assertTrue(
          result == Some(42L),
          cached.contains("123456789012345"),
          cached("123456789012345") == 42L
        )
      },
      
      test("Redis miss + PostgreSQL hit (неактивное) → None, НЕ кешируем") {
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map("999999999999999" -> inactiveVehicle))
          service = VehicleLookupService.Live(redis, repo)
          result <- service.getVehicleId("999999999999999")
          cached <- redis.cacheRef.get
        yield assertTrue(
          result == None,
          !cached.contains("999999999999999")
        )
      },
      
      test("Redis miss + PostgreSQL miss → None") {
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map.empty)
          service = VehicleLookupService.Live(redis, repo)
          result <- service.getVehicleId("000000000000000")
        yield assertTrue(result == None)
      },
      
      test("кастомный TTL применяется") {
        val customTtl = 7200L // 2 часа
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map("123456789012345" -> activeVehicle))
          service = VehicleLookupService.Live(redis, repo, customTtl)
          _ <- service.getVehicleId("123456789012345")
          cached <- redis.cacheRef.get
        yield assertTrue(
          cached.contains("123456789012345"),
          service.cacheTtlSeconds == customTtl
        )
      }
    ),
    
    suite("syncAllToRedis — массовая загрузка")(
      
      test("загружает все активные устройства в Redis") {
        val vehicle2 = VehicleInfo(100, "222222222222222", Some("КамАЗ"), "ruptela", true)
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map(
            "123456789012345" -> activeVehicle,
            "222222222222222" -> vehicle2,
            "999999999999999" -> inactiveVehicle // Неактивное — не должно попасть
          ))
          service = VehicleLookupService.Live(redis, repo)
          count <- service.syncAllToRedis
          cached <- redis.cacheRef.get
        yield assertTrue(
          count == 2, // Только активные
          cached.size == 2,
          cached.contains("123456789012345"),
          cached.contains("222222222222222"),
          !cached.contains("999999999999999")
        )
      },
      
      test("при пустой БД → 0 устройств") {
        for
          redis <- makeMockRedis
          repo = MockDeviceRepo(Map.empty)
          service = VehicleLookupService.Live(redis, repo)
          count <- service.syncAllToRedis
          cached <- redis.cacheRef.get
        yield assertTrue(count == 0, cached.isEmpty)
      }
    ),
    
    suite("invalidateCache — инвалидация")(
      
      test("удаляет ключ из Redis") {
        for
          redis <- makeMockRedisWithData(Map("123456789012345" -> 42L))
          repo = MockDeviceRepo(Map.empty)
          service = VehicleLookupService.Live(redis, repo)
          _ <- service.invalidateCache("123456789012345")
          deleted <- redis.deletedRef.get
        yield assertTrue(deleted.contains("vehicle:123456789012345"))
      }
    ),
    
    suite("VehicleInfo — модель данных")(
      
      test("isActive = true для активного устройства") {
        assertTrue(activeVehicle.isActive == true)
      },
      
      test("isActive = false для отключённого") {
        assertTrue(inactiveVehicle.isActive == false)
      },
      
      test("все поля заполнены") {
        assertTrue(
          activeVehicle.id == 42L,
          activeVehicle.imei == "123456789012345",
          activeVehicle.name == Some("Газель АА123"),
          activeVehicle.deviceType == "teltonika"
        )
      }
    ),
    
    suite("accessor methods")(
      
      test("companion object предоставляет ZIO accessor") {
        // Проверяем что accessor создаёт корректный ZIO эффект
        val effect: ZIO[VehicleLookupService, RedisError, Option[Long]] =
          VehicleLookupService.getVehicleId("test")
        // Тип корректен — это проверка компиляции
        assertTrue(true)
      }
    )
  )
