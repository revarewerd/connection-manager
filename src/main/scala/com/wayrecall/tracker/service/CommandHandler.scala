package com.wayrecall.tracker.service

import zio.*
import zio.stream.*
import zio.kafka.consumer.*
import zio.kafka.serde.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.network.{ConnectionRegistry, ConnectionEntry}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.config.AppConfig
import zio.json.*

/**
 * CommandHandler — обработчик команд с Kafka Static Partition Assignment
 * 
 * Архитектура:
 * 1. Kafka Consumer читает топик device-commands с assign() на свой partition
 *    - instanceId → partition mapping статический
 *    - teltonika (cm-instance-1) → partition 0
 *    - wialon (cm-instance-2) → partition 1  
 *    - ruptela (cm-instance-3) → partition 2
 *    - navtelecom (cm-instance-4) → partition 3
 * 
 * 2. Для каждой команды проверяется ConnectionRegistry:
 *    - Online: отправка немедленно через TCP (ctx.writeAndFlush)
 *    - Offline: в in-memory queue + Redis ZSET backup
 * 
 * 3. При подключении трекера вызывается processPendingCommands:
 *    - Мержит in-memory + Redis
 *    - Дедуплицирует по commandId
 *    - Отправляет все pending команды
 *    - Очищает обе очереди
 * 
 * ⚠️ Важно: НЕ используем Consumer Group, только kafkaConsumer.assign()
 */
trait CommandHandler:
  /**
   * Обрабатывает pending команды при подключении трекера
   * 
   * @param imei IMEI подключённого трекера
   */
  def processPendingCommands(imei: String): Task[Unit]
  
  /**
   * Запускает Kafka Consumer в фоне (background fiber)
   */
  def start: Task[Fiber[Throwable, Unit]]

object CommandHandler:
  
  /**
   * Live реализация с Kafka Static Partition Assignment
   */
  final case class Live(
      config: AppConfig,
      connectionRegistry: ConnectionRegistry,
      redis: RedisClient,
      kafkaProducer: KafkaProducer,
      // In-memory очередь: imei → List[PendingCommand]
      pendingQueueRef: Ref[Map[String, List[PendingCommand]]]
  ) extends CommandHandler:
    
    private val REDIS_PENDING_KEY_PREFIX = "pending_commands:"
    private val MAX_PENDING_PER_DEVICE = config.commands.maxPendingPerDevice
    
    /**
     * Обрабатывает команду из Kafka
     */
    private def handleCommand(command: Command): Task[Unit] =
      for
        _ <- ZIO.logDebug(s"[COMMAND] Получена команда ${command.commandId} для IMEI ${command.imei}")
        
        // Проверяем - онлайн ли трекер
        connectionOpt <- connectionRegistry.findByImei(command.imei)
        
        result <- connectionOpt match
          case Some(conn) =>
            // Трекер онлайн - отправляем немедленно
            sendCommandToTracker(command, conn).either.flatMap {
              case Right(_) =>
                publishAuditEvent(command.commandId, command.imei, CommandStatus.Sent, None)
              case Left(error) =>
                ZIO.logError(s"[COMMAND] Ошибка отправки команды ${command.commandId}: ${error.getMessage}") *>
                addToPendingQueue(command) *>
                publishAuditEvent(command.commandId, command.imei, CommandStatus.Pending, Some(s"Ошибка отправки: ${error.getMessage}"))
            }
          
          case None =>
            // Трекер оффлайн - в очередь
            ZIO.logInfo(s"[COMMAND] Трекер ${command.imei} оффлайн, команда ${command.commandId} → pending") *>
            addToPendingQueue(command) *>
            publishAuditEvent(command.commandId, command.imei, CommandStatus.Pending, Some("Трекер оффлайн"))
      yield ()
    
    /**
     * Отправляет команду на трекер через TCP
     */
    private def sendCommandToTracker(command: Command, conn: ConnectionEntry): Task[Unit] =
      ZIO.attempt {
        // Формируем команду в протоколе трекера
        val commandBytes = formatCommandForProtocol(command, conn.parser.protocolName)
        val buffer = Unpooled.wrappedBuffer(commandBytes)
        
        // Отправляем через Netty channel
        conn.ctx.writeAndFlush(buffer)
        
        ZIO.logInfo(s"[COMMAND] ✓ Команда ${command.commandId} отправлена на трекер ${command.imei}")
      }.flatten
    
    /**
     * Форматирует команду в протоколе трекера
     * 
     * TODO: Реализовать форматирование для каждого протокола
     * Teltonika, Wialon, Ruptela, NavTelecom имеют разные форматы команд
     */
    private def formatCommandForProtocol(command: Command, protocol: String): Array[Byte] =
      // Временная заглушка - возвращаем JSON (для тестов)
      command.toJson.getBytes(StandardCharsets.UTF_8)
    
    /**
     * Добавляет команду в in-memory очередь + Redis backup
     */
    private def addToPendingQueue(command: Command): Task[Unit] =
      val pending = PendingCommand(
        command = command,
        createdAt = command.timestamp,
        retryCount = 0,
        maxRetries = config.commands.maxRetries
      )
      
      for
        // In-memory очередь
        _ <- pendingQueueRef.update { queue =>
          val currentList = queue.getOrElse(command.imei, List.empty)
          
          // Проверка лимита команд на устройство
          if currentList.size >= MAX_PENDING_PER_DEVICE then
            queue // Не добавляем, очередь переполнена
          else
            queue.updated(command.imei, pending :: currentList)
        }
        
        // Redis backup (ZSET с score = timestamp)
        redisKey = REDIS_PENDING_KEY_PREFIX + command.imei
        score = command.timestamp.toEpochMilli.toDouble
        _ <- redis.zadd(redisKey, score, pending.toJson)
        
        // Устанавливаем TTL
        ttlSeconds = config.commands.pendingCommandsTtlHours * 3600
        _ <- redis.expire(redisKey, ttlSeconds)
        
        _ <- ZIO.logDebug(s"[COMMAND] Команда ${command.commandId} добавлена в pending queue для ${command.imei}")
      yield ()
    
    /**
     * Обрабатывает все pending команды при подключении трекера
     */
    override def processPendingCommands(imei: String): Task[Unit] =
      for
        _ <- ZIO.logInfo(s"[COMMAND] Обработка pending команд для подключённого трекера $imei")
        
        // 1. Читаем из in-memory queue
        memoryCommands <- pendingQueueRef.modify { queue =>
          val commands = queue.getOrElse(imei, List.empty)
          (commands, queue - imei) // Удаляем из очереди
        }
        
        // 2. Читаем из Redis backup
        redisKey = REDIS_PENDING_KEY_PREFIX + imei
        redisCommandsJson <- redis.zrange(redisKey, 0, -1)
        redisCommands = redisCommandsJson.flatMap { json =>
          json.fromJson[PendingCommand].toOption
        }
        
        // 3. Мержим и дедуплицируем по commandId
        allCommands = (memoryCommands ++ redisCommands)
          .groupBy(_.command.commandId)
          .map(_._2.head) // Берём первую (самую раннюю)
          .toList
          .sortBy(_.createdAt) // Сортируем по времени создания
        
        _ <- ZIO.logInfo(s"[COMMAND] Найдено ${allCommands.size} pending команд для $imei (memory: ${memoryCommands.size}, redis: ${redisCommands.size})")
        
        // 4. Отправляем все команды
        connectionOpt <- connectionRegistry.findByImei(imei)
        _ <- connectionOpt match
          case Some(conn) =>
            ZIO.foreachDiscard(allCommands) { pending =>
              sendCommandToTracker(pending.command, conn).either.flatMap {
                case Right(_) =>
                  publishAuditEvent(
                    pending.command.commandId,
                    pending.command.imei,
                    CommandStatus.Sent,
                    Some(s"Отправлена после подключения (pending ${java.time.Duration.between(pending.createdAt, java.time.Instant.now()).toMinutes} мин)")
                  )
                case Left(error) =>
                  ZIO.logError(s"[COMMAND] Ошибка отправки pending команды ${pending.command.commandId}: ${error.getMessage}")
              }
            }
          case None =>
            ZIO.logWarning(s"[COMMAND] Трекер $imei отключился до отправки pending команд")
        
        // 5. Очищаем Redis backup
        _ <- redis.del(redisKey)
        
        _ <- ZIO.logInfo(s"[COMMAND] ✓ Обработка pending команд для $imei завершена")
      yield ()
    
    /**
     * Публикует событие в command-audit топик
     */
    private def publishAuditEvent(
        commandId: String,
        imei: String,
        status: CommandStatus,
        message: Option[String]
    ): Task[Unit] =
      val event = CommandResult(
        commandId = commandId,
        imei = imei,
        status = status,
        message = message,
        timestamp = java.time.Instant.now()
      )
      
      kafkaProducer.publish(
        config.kafka.topics.commandAudit,
        imei, // key = imei для ordering
        event.toJson
      )
    
    /**
     * Запускает Kafka Consumer с Static Partition Assignment
     */
    override def start: Task[Fiber[Throwable, Unit]] =
      val consumerSettings = ConsumerSettings(List(config.kafka.bootstrapServers))
        .withGroupId(config.kafka.consumer.groupId)
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.kafka.consumer.autoOffsetReset)
        .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.kafka.consumer.maxPollRecords.toString)
      
      // Определяем partition по instanceId
      val partition = getPartitionForInstance(config.instanceId)
      val subscription = Subscription.manual(config.kafka.topics.deviceCommands, partition)
      
      // Consumer как ZLayer — Scope управляет жизненным циклом
      val consumerLayer = ZLayer.scoped(Consumer.make(consumerSettings))
      
      val stream = Consumer
        .plainStream(subscription, Serde.string, Serde.string)
        .mapZIO { record =>
          for
            _ <- ZIO.logDebug(s"[COMMAND] Получено сообщение из Kafka: key=${record.key}, offset=${record.offset}")
            
            // Парсим команду из JSON
            commandResult = record.value.fromJson[Command]
            
            _ <- commandResult match
              case Left(error) =>
                ZIO.logError(s"[COMMAND] Ошибка парсинга команды: $error")
              case Right(command) =>
                handleCommand(command)
            
            // Commit offset
            _ <- record.offset.commit
          yield ()
        }
        .runDrain
      
      ZIO.logInfo(s"[COMMAND] Запуск Kafka Consumer: instance=${config.instanceId}, partition=$partition") *>
      stream.provideLayer(consumerLayer).forkDaemon
    
    /**
     * Мапинг instanceId → partition (статический)
     */
    private def getPartitionForInstance(instanceId: String): Int =
      instanceId match
        case "cm-instance-1" => 0  // teltonika
        case "cm-instance-2" => 1  // wialon
        case "cm-instance-3" => 2  // ruptela
        case "cm-instance-4" => 3  // navtelecom
        case _ => 0 // fallback на partition 0
  
  /**
   * ZIO Layer для CommandHandler
   */
  val live: ZLayer[AppConfig & ConnectionRegistry & RedisClient & KafkaProducer, Nothing, CommandHandler] =
    ZLayer {
      for
        config <- ZIO.service[AppConfig]
        registry <- ZIO.service[ConnectionRegistry]
        redis <- ZIO.service[RedisClient]
        kafka <- ZIO.service[KafkaProducer]
        pendingQueueRef <- Ref.make(Map.empty[String, List[PendingCommand]])
      yield Live(config, registry, redis, kafka, pendingQueueRef)
    }

end CommandHandler
