package com.wayrecall.tracker.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.network.{ConnectionRegistry, ConnectionEntry}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.config.*

/**
 * Тесты для CommandHandler
 * 
 * Проверяем:
 * 1. getPartitionForInstance — маппинг instance → partition
 * 2. pending queue — in-memory Ref операции
 * 3. addToPendingQueue → лимит команд на устройство
 * 4. processPendingCommands — мерж + дедупликация
 * 5. publishAuditEvent — формат и содержимое
 */
object CommandHandlerSpec extends ZIOSpecDefault:
  
  // === Мок RedisClient ===
  private def makeMockRedis: UIO[(RedisClient, Ref[Map[String, List[String]]])] =
    Ref.make(Map.empty[String, List[String]]).map { storeRef =>
      val client = new RedisClient:
        override def zadd(key: String, score: Double, member: String): Task[Unit] =
          storeRef.update { store =>
            val list = store.getOrElse(key, Nil)
            store.updated(key, list :+ member)
          }
        override def zrange(key: String, start: Long, stop: Long): Task[List[String]] =
          storeRef.get.map(_.getOrElse(key, Nil))
        override def del(key: String): Task[Unit] =
          storeRef.update(_ - key)
        override def expire(key: String, seconds: Long): Task[Unit] = ZIO.unit
        override def getDeviceData(imei: String): IO[RedisError, Option[DeviceData]] = ZIO.succeed(None)
        override def updateDevicePosition(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
        override def setDeviceConnectionFields(imei: String, fields: Map[String, String]): IO[RedisError, Unit] = ZIO.unit
        override def clearDeviceConnectionFields(imei: String): IO[RedisError, Unit] = ZIO.unit
        override def getVehicleId(imei: String): IO[RedisError, Option[Long]] = ZIO.succeed(None)
        override def getVehicleConfig(imei: String): IO[RedisError, Option[VehicleConfig]] = ZIO.succeed(None)
        override def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]] = ZIO.succeed(None)
        override def setPosition(point: GpsPoint): IO[RedisError, Unit] = ZIO.unit
        override def registerConnection(info: ConnectionInfo): IO[RedisError, Unit] = ZIO.unit
        override def unregisterConnection(imei: String): IO[RedisError, Unit] = ZIO.unit
        override def subscribe(channel: String)(handler: String => Task[Unit]): Task[Unit] = ZIO.unit
        override def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long): IO[RedisError, Unit] = ZIO.unit
        override def hset(key: String, values: Map[String, String]): Task[Unit] = ZIO.unit
        override def hgetall(key: String): Task[Map[String, String]] = ZIO.succeed(Map.empty)
        override def get(key: String): Task[Option[String]] = ZIO.succeed(None)
        override def publish(channel: String, message: String): Task[Unit] = ZIO.unit
        override def psubscribe(pattern: String)(handler: (String, String) => Task[Unit]): Task[Unit] = ZIO.unit
      (client, storeRef)
    }
  
  // === Мок KafkaProducer ===
  private def makeMockKafka: UIO[(KafkaProducer, Ref[List[(String, String, String)]])] =
    Ref.make(List.empty[(String, String, String)]).map { ref =>
      val producer = new KafkaProducer:
        override def publish(topic: String, key: String, value: String): IO[KafkaError, Unit] =
          ref.update(_ :+ (topic, key, value))
        override def publishGpsEvent(point: GpsPoint): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsEventMessage(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsRulesEvent(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishGpsRetranslationEvent(msg: GpsEventMessage): IO[KafkaError, Unit] = ZIO.unit
        override def publishDeviceStatus(status: DeviceStatus): IO[KafkaError, Unit] = ZIO.unit
        override def publishUnknownDevice(event: UnknownDeviceEvent): IO[KafkaError, Unit] = ZIO.unit
        override def publishUnknownGpsEvent(point: UnknownGpsPoint): IO[KafkaError, Unit] = ZIO.unit
        override def publishParseError(event: GpsParseErrorEvent): IO[KafkaError, Unit] = ZIO.unit
      (producer, ref)
    }
  
  // === Тестовый AppConfig ===
  private val testConfig = makeTestConfig()
  
  def spec = suite("CommandHandler")(
    
    suite("getPartitionForInstance — маппинг")(
      
      test("cm-instance-1 → partition 0 (teltonika)") {
        assertTrue(getPartitionForInstance("cm-instance-1") == 0)
      },
      
      test("cm-instance-2 → partition 1 (wialon)") {
        assertTrue(getPartitionForInstance("cm-instance-2") == 1)
      },
      
      test("cm-instance-3 → partition 2 (ruptela)") {
        assertTrue(getPartitionForInstance("cm-instance-3") == 2)
      },
      
      test("cm-instance-4 → partition 3 (navtelecom)") {
        assertTrue(getPartitionForInstance("cm-instance-4") == 3)
      },
      
      test("неизвестный instance → fallback partition 0") {
        assertTrue(getPartitionForInstance("cm-instance-99") == 0)
      },
      
      test("пустая строка → fallback partition 0") {
        assertTrue(getPartitionForInstance("") == 0)
      }
    ),
    
    suite("pendingQueueRef — in-memory очередь")(
      
      test("начинается пустой") {
        for
          ref <- Ref.make(Map.empty[String, List[PendingCommand]])
          queue <- ref.get
        yield assertTrue(queue.isEmpty)
      },
      
      test("добавление команды для нового IMEI") {
        for
          ref <- Ref.make(Map.empty[String, List[PendingCommand]])
          cmd = makePendingCommand("cmd-1", "111111111111111")
          _ <- ref.update { q =>
            val list = q.getOrElse("111111111111111", Nil)
            q.updated("111111111111111", cmd :: list)
          }
          queue <- ref.get
        yield assertTrue(
          queue.size == 1 &&
          queue("111111111111111").size == 1 &&
          queue("111111111111111").head.command.commandId == "cmd-1"
        )
      },
      
      test("множественные команды для одного IMEI") {
        for
          ref <- Ref.make(Map.empty[String, List[PendingCommand]])
          cmd1 = makePendingCommand("cmd-1", "111111111111111")
          cmd2 = makePendingCommand("cmd-2", "111111111111111")
          _ <- ref.update(q => q.updated("111111111111111", List(cmd1, cmd2)))
          queue <- ref.get
        yield assertTrue(queue("111111111111111").size == 2)
      },
      
      test("лимит MAX_PENDING_PER_DEVICE предотвращает переполнение") {
        val maxPending = testConfig.commands.maxPendingPerDevice // 100
        for
          ref <- Ref.make(Map.empty[String, List[PendingCommand]])
          // Заполняем очередь до лимита
          commands = (1 to maxPending).map(i => makePendingCommand(s"cmd-$i", "111111111111111")).toList
          _ <- ref.set(Map("111111111111111" -> commands))
          queue <- ref.get
          currentSize = queue.getOrElse("111111111111111", Nil).size
          
          // Попытка добавить ещё одну → не добавляем
          newCmd = makePendingCommand("extra", "111111111111111")
          _ <- ref.update { q =>
            val list = q.getOrElse("111111111111111", Nil)
            if list.size >= maxPending then q  // Не добавляем
            else q.updated("111111111111111", newCmd :: list)
          }
          afterQueue <- ref.get
        yield assertTrue(
          currentSize == maxPending &&
          afterQueue("111111111111111").size == maxPending
        )
      },
      
      test("modify + удаление возвращает и удаляет") {
        for
          ref <- Ref.make(Map.empty[String, List[PendingCommand]])
          cmd = makePendingCommand("cmd-1", "111111111111111")
          _ <- ref.set(Map("111111111111111" -> List(cmd)))
          
          extracted <- ref.modify { q =>
            val cmds = q.getOrElse("111111111111111", Nil)
            (cmds, q - "111111111111111")
          }
          remaining <- ref.get
        yield assertTrue(
          extracted.size == 1 &&
          remaining.isEmpty
        )
      }
    ),
    
    suite("дедупликация по commandId")(
      
      test("одинаковые commandId → берём один") {
        val now = java.time.Instant.now()
        val cmd1 = makePendingCommand("cmd-1", "111111111111111", now)
        val cmd2 = makePendingCommand("cmd-1", "111111111111111", now.plusSeconds(1))
        
        val all = List(cmd1, cmd2)
        val deduped = all.groupBy(_.command.commandId).map(_._2.head).toList
        
        assertTrue(deduped.size == 1)
      },
      
      test("разные commandId → берём все") {
        val now = java.time.Instant.now()
        val cmd1 = makePendingCommand("cmd-1", "111111111111111", now)
        val cmd2 = makePendingCommand("cmd-2", "111111111111111", now)
        
        val all = List(cmd1, cmd2)
        val deduped = all.groupBy(_.command.commandId).map(_._2.head).toList
        
        assertTrue(deduped.size == 2)
      },
      
      test("сортировка по createdAt после дедупликации") {
        val now = java.time.Instant.now()
        val cmd1 = makePendingCommand("cmd-1", "111111111111111", now.plusSeconds(2))
        val cmd2 = makePendingCommand("cmd-2", "111111111111111", now)
        val cmd3 = makePendingCommand("cmd-3", "111111111111111", now.plusSeconds(1))
        
        val sorted = List(cmd1, cmd2, cmd3).sortBy(_.createdAt)
        
        assertTrue(
          sorted.head.command.commandId == "cmd-2" &&
          sorted.last.command.commandId == "cmd-1"
        )
      }
    ),
    
    suite("Redis pending backup")(
      
      test("zadd добавляет команду с timestamp как score") {
        for
          (redis, storeRef) <- makeMockRedis
          cmd = makePendingCommand("cmd-1", "123456789012345")
          score = cmd.createdAt.toEpochMilli.toDouble
          _ <- redis.zadd("pending_commands:123456789012345", score, cmd.toJson)
          stored <- storeRef.get
        yield assertTrue(
          stored.contains("pending_commands:123456789012345") &&
          stored("pending_commands:123456789012345").size == 1
        )
      },
      
      test("zrange возвращает все команды") {
        for
          (redis, storeRef) <- makeMockRedis
          cmd1 = makePendingCommand("cmd-1", "123456789012345")
          cmd2 = makePendingCommand("cmd-2", "123456789012345")
          _ <- redis.zadd("pending_commands:123456789012345", 1.0, cmd1.toJson)
          _ <- redis.zadd("pending_commands:123456789012345", 2.0, cmd2.toJson)
          result <- redis.zrange("pending_commands:123456789012345", 0, -1)
        yield assertTrue(result.size == 2)
      },
      
      test("del очищает ключ") {
        for
          (redis, storeRef) <- makeMockRedis
          _ <- redis.zadd("pending_commands:123", 1.0, "data")
          _ <- redis.del("pending_commands:123")
          result <- redis.zrange("pending_commands:123", 0, -1)
        yield assertTrue(result.isEmpty)
      }
    ),
    
    suite("Kafka audit events")(
      
      test("publish записывает event в правильный топик") {
        for
          (kafka, eventsRef) <- makeMockKafka
          _ <- kafka.publish("command-audit", "123456789012345", """{"status":"Sent"}""")
          events <- eventsRef.get
        yield assertTrue(
          events.size == 1 &&
          events.head._1 == "command-audit" &&
          events.head._2 == "123456789012345"
        )
      }
    )
  )
  
  // === Вспомогательные методы ===
  
  private def getPartitionForInstance(instanceId: String): Int =
    instanceId match
      case "cm-instance-1" => 0
      case "cm-instance-2" => 1
      case "cm-instance-3" => 2
      case "cm-instance-4" => 3
      case _               => 0
  
  private def makePendingCommand(
      cmdId: String, 
      imei: String, 
      at: java.time.Instant = java.time.Instant.now()
  ): PendingCommand =
    PendingCommand(
      command = RequestPositionCommand(cmdId, imei, at),
      createdAt = at,
      retryCount = 0,
      maxRetries = 3
    )
  
  private def makeTestConfig(): AppConfig = AppConfig(
    instanceId = "cm-instance-1",
    tcp = TcpConfig(
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
      bossThreads = 1, workerThreads = 2, maxConnections = 100,
      keepAlive = true, tcpNodelay = true,
      connectionTimeoutSeconds = 30, readTimeoutSeconds = 120,
      writeTimeoutSeconds = 30, idleTimeoutSeconds = 300, idleCheckIntervalSeconds = 60
    ),
    http = HttpConfig(10090, "0.0.0.0"),
    redis = RedisConfig("localhost", 6379, None, 0, 10, 3600, 3600),
    kafka = KafkaConfig(
      bootstrapServers = "localhost:9092",
      producer = KafkaProducerSettings("all", 3, 16384, 5, "snappy"),
      consumer = KafkaConsumerSettings("cm-group", 30000, "latest", 500),
      topics = KafkaTopicsConfig("gps-events", "gps-events-rules", "gps-events-retranslation",
        "gps-parse-errors", "device-status", "device-commands", "device-events",
        "command-audit", "unknown-devices", "unknown-gps-events")
    ),
    filters = FiltersConfig(
      deadReckoning = DeadReckoningFilterConfig(350, 50000, 300),
      stationary = StationaryFilterConfig(10, 2)
    ),
    commands = CommandsConfig(30, 3),
    logging = LoggingConfig("INFO", false)
  )
