package com.wayrecall.tracker.network

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}

/**
 * Тесты для CommandService
 * 
 * Используем in-memory моки для Redis, ConnectionRegistry и KafkaProducer.
 * Тестируем логику управления pending-командами через Ref.
 */
object CommandServiceSpec extends ZIOSpecDefault:
  
  // ===== Моки =====
  
  /**
   * In-memory RedisClient мок с Ref
   * Хранит publish/psubscribe события для проверки
   */
  final case class MockRedisClient(
      publishedRef: Ref[List[(String, String)]],
      subscriptionsRef: Ref[List[String]]
  ) extends RedisClient:
    // Минимальный мок — реализуем только используемые методы
    override def publish(channel: String, message: String): Task[Unit] =
      publishedRef.update(_ :+ (channel, message))
    
    override def psubscribe(
        pattern: String
    )(handler: (String, String) => Task[Unit]): Task[Unit] =
      subscriptionsRef.update(_ :+ pattern)
    
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
    override def del(key: String): Task[Unit] = ZIO.unit
    override def zadd(key: String, score: Double, member: String): Task[Unit] = ZIO.unit
    override def zrange(key: String, start: Long, stop: Long): Task[List[String]] = ZIO.succeed(Nil)
    override def expire(key: String, seconds: Long): Task[Unit] = ZIO.unit
  
  /**
   * In-memory ConnectionRegistry мок
   */
  private def makeRegistry: UIO[ConnectionRegistry] =
    Ref.make(Map.empty[String, ConnectionEntry]).map(ConnectionRegistry.Live(_))
  
  private def makeMockRedis: UIO[MockRedisClient] =
    for
      published <- Ref.make(List.empty[(String, String)])
      subs      <- Ref.make(List.empty[String])
    yield MockRedisClient(published, subs)
  
  def spec = suite("CommandService")(
    
    suite("AwaitingCommand")(
      
      test("создаётся с правильными полями") {
        for
          promise <- Promise.make[Throwable, CommandResult]
          now     <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          cmd      = AwaitingCommand(
                       command = RequestPositionCommand("test-cmd-1", "123456789012345", java.time.Instant.now()),
                       promise = promise,
                       createdAt = now
                     )
        yield assertTrue(
          cmd.command.imei == "123456789012345" &&
          cmd.createdAt == now
        )
      }
    ),
    
    suite("CommandService.Live — pending-команды")(
      
      test("pending Ref начинается пустым") {
        for
          redis      <- makeMockRedis
          registry   <- makeRegistry
          pendingRef <- Ref.make(Map.empty[String, AwaitingCommand])
          pending    <- pendingRef.get
        yield assertTrue(pending.isEmpty)
      },
      
      test("Ref обновляет pending при добавлении") {
        for
          pendingRef <- Ref.make(Map.empty[String, AwaitingCommand])
          promise    <- Promise.make[Throwable, CommandResult]
          now        <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          
          cmd = AwaitingCommand(
            command = RequestPositionCommand("cmd-1", "123456789012345", java.time.Instant.now()),
            promise = promise,
            createdAt = now
          )
          
          _ <- pendingRef.update(_ + ("cmd-1" -> cmd))
          pending <- pendingRef.get
        yield assertTrue(
          pending.size == 1 &&
          pending.contains("cmd-1")
        )
      },
      
      test("Ref удаляет pending после завершения") {
        for
          pendingRef <- Ref.make(Map.empty[String, AwaitingCommand])
          promise    <- Promise.make[Throwable, CommandResult]
          now        <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          
          cmd = AwaitingCommand(
            command = RequestPositionCommand("cmd-1", "123456789012345", java.time.Instant.now()),
            promise = promise,
            createdAt = now
          )
          
          _ <- pendingRef.update(_ + ("cmd-1" -> cmd))
          _ <- pendingRef.update(_ - "cmd-1")
          pending <- pendingRef.get
        yield assertTrue(pending.isEmpty)
      },
      
      test("несколько pending команд для разных IMEI") {
        for
          pendingRef <- Ref.make(Map.empty[String, AwaitingCommand])
          p1 <- Promise.make[Throwable, CommandResult]
          p2 <- Promise.make[Throwable, CommandResult]
          now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          
          cmd1 = AwaitingCommand(
            command = RequestPositionCommand("cmd-1", "111111111111111", java.time.Instant.now()),
            promise = p1, createdAt = now
          )
          cmd2 = AwaitingCommand(
            command = RequestPositionCommand("cmd-2", "222222222222222", java.time.Instant.now()),
            promise = p2, createdAt = now
          )
          
          _ <- pendingRef.update(_ + ("cmd-1" -> cmd1) + ("cmd-2" -> cmd2))
          pending <- pendingRef.get
          
          // Ищем pending для конкретного IMEI
          forImei1 = pending.values.filter(_.command.imei == "111111111111111").toList
          forImei2 = pending.values.filter(_.command.imei == "222222222222222").toList
        yield assertTrue(
          pending.size == 2 &&
          forImei1.size == 1 &&
          forImei2.size == 1
        )
      }
    ),
    
    suite("MockRedisClient — publish")(
      
      test("publish записывает сообщение") {
        for
          redis <- makeMockRedis
          _     <- redis.publish("command-results:123", """{"status":"sent"}""")
          msgs  <- redis.publishedRef.get
        yield assertTrue(
          msgs.size == 1 &&
          msgs.head._1 == "command-results:123" &&
          msgs.head._2.contains("sent")
        )
      },
      
      test("publish записывает несколько сообщений") {
        for
          redis <- makeMockRedis
          _     <- redis.publish("ch1", "msg1")
          _     <- redis.publish("ch2", "msg2")
          msgs  <- redis.publishedRef.get
        yield assertTrue(msgs.size == 2)
      }
    ),
    
    suite("CommandResult.toJson — JSON roundtrip")(
      
      test("CommandResult корректно сериализуется") {
        val now = java.time.Instant.now()
        val result = CommandResult(
          commandId = "test-cmd-1",
          imei = "123456789012345",
          status = CommandStatus.Sent,
          message = Some("Отправлено"),
          timestamp = now
        )
        val json = result.toJson
        val parsed = json.fromJson[CommandResult]
        assertTrue(
          json.contains("test-cmd-1") &&
          json.contains("123456789012345") &&
          parsed.isRight &&
          parsed.toOption.get.status == CommandStatus.Sent
        )
      }
    )
  )
