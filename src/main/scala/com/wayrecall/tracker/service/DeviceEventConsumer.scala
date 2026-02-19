package com.wayrecall.tracker.service

import zio.*
import zio.stream.*
import zio.kafka.consumer.*
import zio.kafka.serde.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import com.wayrecall.tracker.domain.{VehicleConfig, DeviceEvent}
import com.wayrecall.tracker.storage.RedisClient
import com.wayrecall.tracker.config.AppConfig
import zio.json.*

/**
 * DeviceEventConsumer — Kafka consumer для топика device-events
 * 
 * Получает события изменения конфигурации устройств из Device Manager:
 * - Обновление конфигурации маршрутизации (геозоны, ретрансляция)
 * - Привязка/отвязка правил
 * - Изменение целей ретрансляции
 * 
 * При получении события:
 * 1. HMSET device:{imei} — обновляет context-поля в ЕДИНОМ Redis HASH
 *    (hasGeozones, hasSpeedRules, hasRetranslation, retranslationTargets)
 * 2. Все активные соединения подхватят обновлённые флаги при следующем
 *    HGETALL в processDataPacket (fresh context на каждый пакет!)
 * 
 * Использует Consumer Group (все инстансы CM в одной группе) —
 * каждое событие обрабатывается ровно одним инстансом.
 * Redis HASH общий, поэтому обновление видно всем инстансам CM.
 */
trait DeviceEventConsumer:
  /**
   * Запускает Kafka Consumer в фоне (background fiber)
   */
  def start: Task[Fiber[Throwable, Unit]]

object DeviceEventConsumer:
  
  // Accessor method
  def start: RIO[DeviceEventConsumer, Fiber[Throwable, Unit]] =
    ZIO.serviceWithZIO(_.start)
  
  /**
   * Live реализация с Kafka Consumer Group
   */
  final case class Live(
      config: AppConfig,
      redis: RedisClient
  ) extends DeviceEventConsumer:
    
    override def start: Task[Fiber[Throwable, Unit]] =
      val consumerSettings = ConsumerSettings(List(config.kafka.bootstrapServers))
        .withGroupId(s"${config.kafka.consumer.groupId}-device-events")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50")
      
      val subscription = Subscription.topics(config.kafka.topics.deviceEvents)
      
      // Consumer как ZLayer — Scope управляет жизненным циклом
      val consumerLayer = ZLayer.scoped(Consumer.make(consumerSettings))
      
      val stream = Consumer
        .plainStream(subscription, Serde.string, Serde.string)
        .mapZIO { record =>
          for
            _ <- ZIO.logDebug(s"[DEVICE-EVENT] Получено событие: key=${record.key}, offset=${record.offset}")
            
            // Парсим событие
            eventResult = record.value.fromJson[DeviceEvent]
            
            _ <- eventResult match
              case Left(error) =>
                ZIO.logError(s"[DEVICE-EVENT] Ошибка парсинга события: $error")
              case Right(event) =>
                handleEvent(event)
            
            // Commit offset
            _ <- record.offset.commit
          yield ()
        }
        .runDrain
      
      ZIO.logInfo(s"[DEVICE-EVENT] Запуск Kafka Consumer: topic=${config.kafka.topics.deviceEvents}") *>
      stream.provideLayer(consumerLayer).forkDaemon
    
    /**
     * Обрабатывает событие изменения конфигурации устройства
     * 
     * Обновляет context-поля в ЕДИНОМ Redis HASH device:{imei}.
     * При следующем HGETALL в processDataPacket активное соединение
     * получит актуальные флаги маршрутизации.
     */
    private def handleEvent(event: DeviceEvent): Task[Unit] =
      event.eventType match
        case "config_updated" =>
          handleConfigUpdated(event)
        case "config_deleted" =>
          handleConfigDeleted(event)
        case other =>
          ZIO.logDebug(s"[DEVICE-EVENT] Пропускаем событие типа: $other для IMEI=${event.imei}")
    
    /**
     * HMSET device:{imei} — обновляет context-поля (маршрутизационные флаги)
     * 
     * Обновляем только поля из VehicleConfig, не трогаем position/connection поля:
     * - hasGeozones, hasSpeedRules, hasRetranslation, retranslationTargets
     * - organizationId, name (могли измениться)
     */
    private def handleConfigUpdated(event: DeviceEvent): Task[Unit] =
      event.vehicleConfig match
        case Some(config) =>
          val fields = Map(
            "organizationId" -> config.organizationId.toString,
            "name" -> config.name,
            "hasGeozones" -> config.hasGeozones.toString,
            "hasSpeedRules" -> config.hasSpeedRules.toString,
            "hasRetranslation" -> config.hasRetranslation.toString,
            "retranslationTargets" -> config.retranslationTargets.mkString(",")
          )
          for
            // HMSET device:{imei} — обновляем context-поля в едином HASH
            _ <- redis.hset(s"device:${event.imei}", fields)
                   .tapError(e => ZIO.logError(s"[DEVICE-EVENT] Ошибка HMSET device:${event.imei}: ${e.getMessage}"))
            
            // Legacy: обновляем старый ключ vehicle:config:{imei}
            // TODO: убрать после полной миграции на device:{imei}
            _ <- redis.hset(s"vehicle:config:${event.imei}", Map("data" -> config.toJson))
                   .tapError(e => ZIO.logError(s"[DEVICE-EVENT] Ошибка legacy vehicle:config: ${e.getMessage}"))
            
            _ <- ZIO.logInfo(
              s"[DEVICE-EVENT] ✓ Конфигурация → device:${event.imei}: " +
              s"geozones=${config.hasGeozones}, retrans=${config.hasRetranslation}, " +
              s"speedRules=${config.hasSpeedRules}"
            )
          yield ()
        case None =>
          ZIO.logWarning(s"[DEVICE-EVENT] Событие config_updated без vehicleConfig для IMEI=${event.imei}")
    
    /**
     * Удаляет маршрутизационные флаги из единого HASH (но не весь ключ!)
     * 
     * Сбрасываем флаги в "false" вместо удаления — device:{imei} содержит
     * и другие данные (position, connection), которые должны остаться.
     */
    private def handleConfigDeleted(event: DeviceEvent): Task[Unit] =
      val resetFields = Map(
        "hasGeozones" -> "false",
        "hasSpeedRules" -> "false",
        "hasRetranslation" -> "false",
        "retranslationTargets" -> ""
      )
      for
        // HMSET device:{imei} — сбрасываем флаги (но HASH остаётся)
        _ <- redis.hset(s"device:${event.imei}", resetFields)
               .tapError(e => ZIO.logError(s"[DEVICE-EVENT] Ошибка сброса флагов device:${event.imei}: ${e.getMessage}"))
        
        // Legacy: удаляем старый ключ vehicle:config:{imei}
        _ <- redis.del(s"vehicle:config:${event.imei}")
               .tapError(e => ZIO.logError(s"[DEVICE-EVENT] Ошибка legacy удаления: ${e.getMessage}"))
        
        _ <- ZIO.logInfo(s"[DEVICE-EVENT] Флаги маршрутизации сброшены для IMEI=${event.imei}")
      yield ()
  
  /**
   * ZIO Layer для DeviceEventConsumer
   */
  val live: ZLayer[AppConfig & RedisClient, Nothing, DeviceEventConsumer] =
    ZLayer {
      for
        config <- ZIO.service[AppConfig]
        redis <- ZIO.service[RedisClient]
      yield Live(config, redis)
    }
