package com.wayrecall.tracker.storage

import zio.*
import zio.json.*
import io.lettuce.core.{RedisClient => LettuceClient, RedisURI}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.{RedisPubSubAdapter, StatefulRedisPubSubConnection}
import com.wayrecall.tracker.domain.{GpsPoint, ConnectionInfo, RedisError, VehicleConfig, DeviceData}
import com.wayrecall.tracker.config.RedisConfig
import java.util.concurrent.CompletionStage
import scala.jdk.FutureConverters.*
import scala.jdk.CollectionConverters.*

/**
 * Клиент для работы с Redis - чисто функциональный интерфейс
 * 
 * ✅ IMEI → VehicleId маппинг
 * ✅ Кеширование позиций
 * ✅ Pub/Sub для команд
 * ✅ Fallback на PostgreSQL (опционально)
 */
trait RedisClient:
  // === Unified HASH device:{imei} (ОСНОВНОЙ ПАТТЕРН по документации) ===
  /** HGETALL device:{imei} — все данные за 1 запрос */
  def getDeviceData(imei: String): IO[RedisError, Option[DeviceData]]
  /** HMSET device:{imei} — обновление position полей */
  def updateDevicePosition(imei: String, fields: Map[String, String]): IO[RedisError, Unit]
  /** HMSET device:{imei} — запись connection полей при подключении */
  def setDeviceConnectionFields(imei: String, fields: Map[String, String]): IO[RedisError, Unit]
  /** HDEL device:{imei} — очистка connection полей при отключении */
  def clearDeviceConnectionFields(imei: String): IO[RedisError, Unit]

  // === Legacy методы (обратная совместимость) ===
  def getVehicleId(imei: String): IO[RedisError, Option[Long]]
  def getVehicleConfig(imei: String): IO[RedisError, Option[VehicleConfig]]
  def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]]
  def setPosition(point: GpsPoint): IO[RedisError, Unit]
  def registerConnection(info: ConnectionInfo): IO[RedisError, Unit]
  def unregisterConnection(imei: String): IO[RedisError, Unit]
  
  // Pub/Sub для команд и конфигурации
  def subscribe(channel: String)(handler: String => Task[Unit]): Task[Unit]
  def publish(channel: String, message: String): Task[Unit]
  def psubscribe(pattern: String)(handler: (String, String) => Task[Unit]): Task[Unit]
  
  // Hash operations для конфигурации
  def hset(key: String, values: Map[String, String]): Task[Unit]
  def hgetall(key: String): Task[Map[String, String]]
  def get(key: String): Task[Option[String]]
  
  // Sorted Set операции для pending commands
  def zadd(key: String, score: Double, member: String): Task[Unit]
  def zrange(key: String, start: Long, stop: Long): Task[List[String]]
  def expire(key: String, seconds: Long): Task[Unit]
  
  // Дополнительные методы для кеширования
  def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long = 3600): IO[RedisError, Unit]
  def del(key: String): Task[Unit]

object RedisClient:
  
  // Accessor методы для ZIO service pattern
  
  // === Unified HASH ===
  def getDeviceData(imei: String): ZIO[RedisClient, RedisError, Option[DeviceData]] =
    ZIO.serviceWithZIO(_.getDeviceData(imei))
  
  def updateDevicePosition(imei: String, fields: Map[String, String]): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.updateDevicePosition(imei, fields))
  
  def setDeviceConnectionFields(imei: String, fields: Map[String, String]): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.setDeviceConnectionFields(imei, fields))
  
  def clearDeviceConnectionFields(imei: String): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.clearDeviceConnectionFields(imei))

  // === Legacy ===
  def getVehicleId(imei: String): ZIO[RedisClient, RedisError, Option[Long]] =
    ZIO.serviceWithZIO(_.getVehicleId(imei))
  
  def getVehicleConfig(imei: String): ZIO[RedisClient, RedisError, Option[VehicleConfig]] =
    ZIO.serviceWithZIO(_.getVehicleConfig(imei))
  
  def getPosition(vehicleId: Long): ZIO[RedisClient, RedisError, Option[GpsPoint]] =
    ZIO.serviceWithZIO(_.getPosition(vehicleId))
  
  def setPosition(point: GpsPoint): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.setPosition(point))
  
  def registerConnection(info: ConnectionInfo): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.registerConnection(info))
  
  def unregisterConnection(imei: String): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.unregisterConnection(imei))
  
  def subscribe(channel: String)(handler: String => Task[Unit]): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.subscribe(channel)(handler))
  
  def publish(channel: String, message: String): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.publish(channel, message))
  
  def psubscribe(pattern: String)(handler: (String, String) => Task[Unit]): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.psubscribe(pattern)(handler))
  
  def hset(key: String, values: Map[String, String]): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.hset(key, values))
  
  def hgetall(key: String): RIO[RedisClient, Map[String, String]] =
    ZIO.serviceWithZIO(_.hgetall(key))
  
  def get(key: String): RIO[RedisClient, Option[String]] =
    ZIO.serviceWithZIO(_.get(key))
  
  def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long = 3600): ZIO[RedisClient, RedisError, Unit] =
    ZIO.serviceWithZIO(_.setVehicleId(imei, vehicleId, ttlSeconds))
  
  def del(key: String): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.del(key))
  
  def zadd(key: String, score: Double, member: String): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.zadd(key, score, member))
  
  def zrange(key: String, start: Long, stop: Long): RIO[RedisClient, List[String]] =
    ZIO.serviceWithZIO(_.zrange(key, start, stop))
  
  def expire(key: String, seconds: Long): RIO[RedisClient, Unit] =
    ZIO.serviceWithZIO(_.expire(key, seconds))
  
  /**
   * Live реализация с Lettuce
   */
  final case class Live(
      commands: RedisAsyncCommands[String, String],
      pubSubConnection: StatefulRedisPubSubConnection[String, String],
      runtime: Runtime[Any],
      config: RedisConfig
  ) extends RedisClient:
    
    // Ключи Redis - чистые функции
    /** Единый HASH ключ с ВСЕМИ данными об устройстве */
    private def deviceKey(imei: String): String = s"device:$imei"
    // Legacy ключи (оставлены для обратной совместимости)
    private def vehicleKey(imei: String): String = s"vehicle:$imei"
    private def positionKey(vehicleId: Long): String = s"position:$vehicleId"
    private def connectionKey(imei: String): String = s"connection:$imei"
    
    // ═══════════════════════════════════════════════════════════════
    // Unified HASH device:{imei} — HGETALL / HMSET / HDEL
    // ═══════════════════════════════════════════════════════════════
    
    override def getDeviceData(imei: String): IO[RedisError, Option[DeviceData]] =
      fromCompletionStage(commands.hgetall(deviceKey(imei)))
        .map { javaMap =>
          val hash = javaMap.asScala.toMap
          if hash.isEmpty then None
          else DeviceData.fromRedisHash(hash)
        }
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def updateDevicePosition(imei: String, fields: Map[String, String]): IO[RedisError, Unit] =
      fromCompletionStage(commands.hset(deviceKey(imei), fields.asJava))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def setDeviceConnectionFields(imei: String, fields: Map[String, String]): IO[RedisError, Unit] =
      fromCompletionStage(commands.hset(deviceKey(imei), fields.asJava))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def clearDeviceConnectionFields(imei: String): IO[RedisError, Unit] =
      fromCompletionStage(commands.hdel(deviceKey(imei), DeviceData.connectionFieldNames*))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    // ═══════════════════════════════════════════════════════════════
    // Legacy методы (vehicle:{imei}, position:{vehicleId}, connection:{imei})
    // ═══════════════════════════════════════════════════════════════
    
    override def getVehicleId(imei: String): IO[RedisError, Option[Long]] =
      fromCompletionStage(commands.get(vehicleKey(imei)))
        .map(Option(_).flatMap(_.toLongOption))
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    /** Загружает конфигурацию маршрутизации по IMEI из Redis (vehicle:config:{imei}) */
    override def getVehicleConfig(imei: String): IO[RedisError, Option[VehicleConfig]] =
      fromCompletionStage(commands.get(s"vehicle:config:$imei"))
        .map { value =>
          Option(value).flatMap(_.fromJson[VehicleConfig].toOption)
        }
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def getPosition(vehicleId: Long): IO[RedisError, Option[GpsPoint]] =
      fromCompletionStage(commands.get(positionKey(vehicleId)))
        .map { value =>
          Option(value).flatMap(_.fromJson[GpsPoint].toOption)
        }
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def setPosition(point: GpsPoint): IO[RedisError, Unit] =
      val key = positionKey(point.vehicleId)
      val value = point.toJson
      val ttlSeconds = config.positionTtlSeconds
      
      fromCompletionStage(commands.setex(key, ttlSeconds, value))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def registerConnection(info: ConnectionInfo): IO[RedisError, Unit] =
      fromCompletionStage(commands.set(connectionKey(info.imei), info.toJson))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def unregisterConnection(imei: String): IO[RedisError, Unit] =
      fromCompletionStage(commands.del(connectionKey(imei)))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    // Pub/Sub операции
    override def subscribe(channel: String)(handler: String => Task[Unit]): Task[Unit] =
      ZIO.attempt {
        val listener = new RedisPubSubAdapter[String, String] {
          override def message(ch: String, message: String): Unit =
            if ch == channel then
              Unsafe.unsafe { implicit unsafe =>
                runtime.unsafe.run(handler(message).catchAll(e => ZIO.logError(s"Handler error: $e"))).getOrThrowFiberFailure()
              }
        }
        pubSubConnection.addListener(listener)
        pubSubConnection.sync().subscribe(channel)
      }
    
    override def psubscribe(pattern: String)(handler: (String, String) => Task[Unit]): Task[Unit] =
      ZIO.attempt {
        val listener = new RedisPubSubAdapter[String, String] {
          override def message(pattern: String, channel: String, message: String): Unit =
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.run(handler(channel, message).catchAll(e => ZIO.logError(s"Handler error: $e"))).getOrThrowFiberFailure()
            }
        }
        pubSubConnection.addListener(listener)
        pubSubConnection.sync().psubscribe(pattern)
      }
    
    override def publish(channel: String, message: String): Task[Unit] =
      fromCompletionStage(commands.publish(channel, message)).unit
    
    // Hash операции для конфигурации
    override def hset(key: String, values: Map[String, String]): Task[Unit] =
      fromCompletionStage(commands.hset(key, values.asJava)).unit
    
    override def hgetall(key: String): Task[Map[String, String]] =
      fromCompletionStage(commands.hgetall(key)).map(_.asScala.toMap)
    
    override def get(key: String): Task[Option[String]] =
      fromCompletionStage(commands.get(key)).map(Option(_))
    
    override def setVehicleId(imei: String, vehicleId: Long, ttlSeconds: Long): IO[RedisError, Unit] =
      fromCompletionStage(commands.setex(vehicleKey(imei), ttlSeconds, vehicleId.toString))
        .unit
        .mapError(e => RedisError.OperationFailed(e.getMessage))
    
    override def del(key: String): Task[Unit] =
      fromCompletionStage(commands.del(key)).unit
    
    override def zadd(key: String, score: Double, member: String): Task[Unit] =
      fromCompletionStage(commands.zadd(key, score, member)).unit
    
    override def zrange(key: String, start: Long, stop: Long): Task[List[String]] =
      fromCompletionStage(commands.zrange(key, start, stop)).map(_.asScala.toList)
    
    override def expire(key: String, seconds: Long): Task[Unit] =
      fromCompletionStage(commands.expire(key, seconds)).unit
    
    /**
     * Конвертирует Java CompletionStage в ZIO эффект
     */
    private def fromCompletionStage[A](cs: => CompletionStage[A]): Task[A] =
      ZIO.fromFuture(_ => cs.asScala)
  
  /**
   * ZIO Layer с управлением ресурсами
   */
  val live: ZLayer[RedisConfig, Throwable, RedisClient] =
    ZLayer.scoped {
      for
        config <- ZIO.service[RedisConfig]
        runtime <- ZIO.runtime[Any]
        
        // Создаем URI для подключения
        uri = {
          val builder = RedisURI.builder()
            .withHost(config.host)
            .withPort(config.port)
            .withDatabase(config.database)
          config.password.foreach(pwd => builder.withPassword(pwd.toCharArray))
          builder.build()
        }
        
        // Создаем клиент с автоматическим закрытием
        client <- ZIO.acquireRelease(
          ZIO.attempt(LettuceClient.create(uri))
            .tap(_ => ZIO.logInfo(s"Redis клиент создан: ${config.host}:${config.port}"))
        )(client => 
          ZIO.attempt(client.shutdown()).orDie
            .tap(_ => ZIO.logInfo("Redis клиент закрыт"))
        )
        
        // Создаем основное соединение для команд
        connection <- ZIO.acquireRelease(
          ZIO.attempt(client.connect())
            .tap(_ => ZIO.logInfo("Redis соединение установлено"))
        )(conn => 
          ZIO.attempt(conn.close()).orDie
            .tap(_ => ZIO.logInfo("Redis соединение закрыто"))
        )
        
        // Создаем отдельное соединение для Pub/Sub
        pubSubConnection <- ZIO.acquireRelease(
          ZIO.attempt(client.connectPubSub())
            .tap(_ => ZIO.logInfo("Redis Pub/Sub соединение установлено"))
        )(conn => 
          ZIO.attempt(conn.close()).orDie
            .tap(_ => ZIO.logInfo("Redis Pub/Sub соединение закрыто"))
        )
        
        commands = connection.async()
      yield Live(commands, pubSubConnection, runtime, config)
    }
