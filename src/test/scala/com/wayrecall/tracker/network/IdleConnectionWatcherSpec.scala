package com.wayrecall.tracker.network

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.config.{TcpConfig, TcpProtocolConfig, MultiProtocolPortConfig, FilterConfig}
import com.wayrecall.tracker.config.DynamicConfigService
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import java.util.concurrent.ConcurrentHashMap

/**
 * Тесты для IdleConnectionWatcher
 * 
 * Проверяем:
 * 1. Обнаружение idle соединений
 * 2. Отключение неактивных соединений
 * 3. Корректный подсчёт disconnected
 * 4. Работу с пустым реестром
 */
object IdleConnectionWatcherSpec extends ZIOSpecDefault:
  
  // === Мок DynamicConfigService ===
  private def makeMockConfigService: UIO[DynamicConfigService] =
    Ref.make(FilterConfig()).map { ref =>
      new DynamicConfigService:
        override def getFilterConfig: UIO[FilterConfig] = ref.get
        override def updateFilterConfig(config: FilterConfig): Task[Unit] = ref.set(config)
        override def subscribeToChanges: Task[Unit] = ZIO.unit
    }
  
  // === Мок KafkaProducer ===
  private def makeMockKafkaProducer: UIO[(KafkaProducer, Ref[List[DeviceStatus]])] =
    Ref.make(List.empty[DeviceStatus]).map { ref =>
      val producer = new KafkaProducer:
        override def publish(topic: String, key: String, value: String): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsEvent(point: GpsPoint): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsEventMessage(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsRulesEvent(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsRetranslationEvent(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishDeviceStatus(status: DeviceStatus): IO[KafkaError, Unit] =
          ref.update(_ :+ status)
        override def publishUnknownDevice(event: UnknownDeviceEvent): IO[KafkaError, Unit] = ZIO.unit
        override def publishUnknownGpsEvent(point: UnknownGpsPoint): IO[KafkaError, Unit] = ZIO.unit
        override def publishParseError(event: GpsParseErrorEvent): IO[KafkaError, Unit] = ZIO.unit
      (producer, ref)
    }
  
  // === Мок RedisClient ===
  private def makeMockRedis: UIO[RedisClient] = ZIO.succeed {
    new RedisClient:
      override def getDeviceData(imei: String): IO[RedisError, Option[DeviceData]] = ZIO.succeed(None)
      override def updateDevicePosition(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
      override def setDeviceConnectionFields(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
      override def clearDeviceConnectionFields(imei: String): IO[RedisError, Unit] = ZIO.unit
      override def getVehicleId(imei: String): IO[RedisError, Option[Long]] = ZIO.succeed(Some(42L))
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
      override def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long): IO[RedisError, Unit] = ZIO.unit
      override def del(key: String): Task[Unit] = ZIO.unit
  }
  
  // === Минимальный TcpConfig ===
  private val testTcpConfig = TcpConfig(
    teltonika = TcpProtocolConfig(5001, true),
    wialon = TcpProtocolConfig(5002, true),
    ruptela = TcpProtocolConfig(5003, true),
    navtelecom = TcpProtocolConfig(5004, true),
    gosafe = TcpProtocolConfig(5005, false),
    skysim = TcpProtocolConfig(5006, false),
    autophoneMayak = TcpProtocolConfig(5007, false),
    dtm = TcpProtocolConfig(5008, false),
    galileosky = TcpProtocolConfig(5009, false),
    concox = TcpProtocolConfig(5010, false),
    tk102 = TcpProtocolConfig(5011, false),
    tk103 = TcpProtocolConfig(5012, false),
    arnavi = TcpProtocolConfig(5013, false),
    adm = TcpProtocolConfig(5014, false),
    gtlt = TcpProtocolConfig(5015, false),
    microMayak = TcpProtocolConfig(5016, false),
    multi = MultiProtocolPortConfig(5000, false),
    bossThreads = 1,
    workerThreads = 2,
    maxConnections = 100,
    keepAlive = true,
    tcpNodelay = true,
    connectionTimeoutSeconds = 30,
    readTimeoutSeconds = 120,
    writeTimeoutSeconds = 30,
    idleTimeoutSeconds = 300,    // 5 минут idle timeout
    idleCheckIntervalSeconds = 60 // Проверка каждую минуту
  )
  
  def spec = suite("IdleConnectionWatcher")(
    
    suite("checkAndDisconnectIdle")(
      
      test("возвращает 0 для пустого реестра") {
        for
          registry <- ZIO.succeed(ConnectionRegistry.Live(new ConcurrentHashMap[String, MutableConnectionEntry]()))
          config   <- makeMockConfigService
          (kafka, _) <- makeMockKafkaProducer
          redis    <- makeMockRedis
          
          watcher = IdleConnectionWatcher.Live(registry, config, kafka, redis, testTcpConfig)
          count <- watcher.checkAndDisconnectIdle
        yield assertTrue(count == 0)
      },
      
      test("обнаруживает idle соединения по idle timeout из конфига") {
        // idleTimeoutSeconds = 300 (5 минут) в testTcpConfig
        // Если соединение было 6 минут назад → должно быть idle
        for
          registry <- ZIO.succeed(ConnectionRegistry.Live(new ConcurrentHashMap[String, MutableConnectionEntry]()))
          config   <- makeMockConfigService
          (kafka, statusRef) <- makeMockKafkaProducer
          redis    <- makeMockRedis
          
          // Регистрируем соединение
          _ <- registry.register("123456789012345", null, null)
          // Много помечаем время назад (эмуляция через TestClock для getIdleConnections)
          _ <- TestClock.adjust(6.minutes) // Прибавляем 6 минут
          
          watcher = IdleConnectionWatcher.Live(registry, config, kafka, redis, testTcpConfig)
          // checkAndDisconnectIdle вызывает registry.getIdleConnections(300000)
          // Соединение: lastActivityAt = 0мс, now = 360000мс, idleTimeout = 300000мс → idle!
          count <- watcher.checkAndDisconnectIdle
        yield assertTrue(count == 1)
      },
      
      test("не трогает активные соединения") {
        for
          registry <- ZIO.succeed(ConnectionRegistry.Live(new ConcurrentHashMap[String, MutableConnectionEntry]()))
          config   <- makeMockConfigService
          (kafka, _) <- makeMockKafkaProducer
          redis    <- makeMockRedis
          
          // Регистрируем соединение (lastActivityAt = now)
          _ <- registry.register("123456789012345", null, null)
          // НЕ двигаем clock → соединение "только что" создано
          
          watcher = IdleConnectionWatcher.Live(registry, config, kafka, redis, testTcpConfig)
          count <- watcher.checkAndDisconnectIdle
        yield assertTrue(count == 0)
      }
    ),
    
    suite("TcpConfig — idle параметры")(
      
      test("idleTimeoutSeconds конвертируется в миллисекунды") {
        val timeoutMs = testTcpConfig.idleTimeoutSeconds.toLong * 1000
        assertTrue(timeoutMs == 300000L)
      },
      
      test("idleCheckIntervalSeconds задаёт частоту проверки") {
        val intervalMs = testTcpConfig.idleCheckIntervalSeconds.toLong * 1000
        assertTrue(intervalMs == 60000L)
      }
    ),
    
    suite("DisconnectReason")(
      
      test("IdleTimeout имеет правильное строковое представление") {
        val reason = DisconnectReason.IdleTimeout
        assertTrue(reason.toString.contains("IdleTimeout"))
      }
    )
  )
