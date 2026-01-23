package com.wayrecall.tracker.network

import zio.*
import zio.json.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}

/**
 * Pending command with its promise
 */
private[network] final case class PendingCommand(
    command: Command,
    promise: Promise[Throwable, CommandResult],
    createdAt: Long
)

/**
 * Сервис для управления командами на GPS трекеры
 * 
 * ✅ Чисто функциональный: использует ZIO Ref вместо ConcurrentHashMap
 * ✅ Нет var, while, mutable state
 * 
 * Workflow:
 * 1. Backend API → Redis PUBLISH commands:{imei}
 * 2. CommandService (слушает Redis) → находит соединение → отправляет на трекер
 * 3. Трекер отвечает → CommandService → Redis PUBLISH command-results:{imei}
 */
trait CommandService:
  /**
   * Отправить команду на трекер
   */
  def sendCommand(command: Command): Task[CommandResult]
  
  /**
   * Запустить слушатель команд из Redis
   */
  def startCommandListener: Task[Unit]
  
  /**
   * Обработать ответ от трекера
   */
  def handleCommandResponse(imei: String, response: Array[Byte]): Task[Unit]

object CommandService:
  
  // Accessor methods
  def sendCommand(command: Command): RIO[CommandService, CommandResult] =
    ZIO.serviceWithZIO(_.sendCommand(command))
  
  def startCommandListener: RIO[CommandService, Unit] =
    ZIO.serviceWithZIO(_.startCommandListener)
  
  def handleCommandResponse(imei: String, response: Array[Byte]): RIO[CommandService, Unit] =
    ZIO.serviceWithZIO(_.handleCommandResponse(imei, response))
  
  /**
   * Live реализация - чисто функциональная!
   */
  final case class Live(
      redisClient: RedisClient,
      connectionRegistry: ConnectionRegistry,
      pendingCommandsRef: Ref[Map[String, PendingCommand]],
      commandTimeout: Duration
  ) extends CommandService:
    
    private val COMMANDS_CHANNEL = "commands:*"
    private val RESULTS_CHANNEL_PREFIX = "command-results:"
    
    override def sendCommand(command: Command): Task[CommandResult] =
      for
        // 1. Найти соединение для этого IMEI
        entryOpt <- connectionRegistry.findByImei(command.imei)
        entry <- ZIO.fromOption(entryOpt)
                   .mapError(_ => new RuntimeException(s"Tracker ${command.imei} not connected"))
        
        // 2. Кодировать команду
        buffer <- entry.parser.encodeCommand(command)
        
        // 3. Создать Promise для ожидания ответа
        promise <- Promise.make[Throwable, CommandResult]
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        pendingCmd = PendingCommand(command, promise, now)
        _ <- pendingCommandsRef.update(_ + (command.commandId -> pendingCmd))
        
        // 4. Отправить на трекер
        _ <- ZIO.attempt(entry.ctx.writeAndFlush(buffer))
        
        // 5. Опубликовать статус "Sent"
        sentResult <- createResult(command, CommandStatus.Sent, None)
        _ <- redisClient.publish(
          s"$RESULTS_CHANNEL_PREFIX${command.imei}",
          sentResult.toJson
        )
        
        _ <- ZIO.logInfo(s"Command ${command.commandId} sent to ${command.imei}")
        
        // 6. Ждать ответа с таймаутом
        result <- promise.await
          .timeout(commandTimeout)
          .flatMap {
            case Some(r) => ZIO.succeed(r)
            case None =>
              for
                timeoutResult <- createResult(
                  command, 
                  CommandStatus.Timeout,
                  Some(s"Tracker did not respond within ${commandTimeout.toSeconds}s")
                )
                // Публикуем результат таймаута
                _ <- redisClient.publish(
                  s"$RESULTS_CHANNEL_PREFIX${command.imei}",
                  timeoutResult.toJson
                )
              yield timeoutResult
          }
          .ensuring(pendingCommandsRef.update(_ - command.commandId))
        
      yield result
    
    override def startCommandListener: Task[Unit] =
      redisClient.psubscribe(COMMANDS_CHANNEL) { (channel, message) =>
        val imei = channel.stripPrefix("commands:")
        
        (for
          command <- ZIO.fromEither(message.fromJson[Command])
                       .mapError(e => new RuntimeException(s"Failed to parse command: $e"))
          
          _ <- ZIO.logInfo(s"Received command ${command.commandId} for $imei from Redis")
          
          // Проверяем, подключен ли трекер
          connected <- connectionRegistry.isConnected(command.imei)
          
          _ <- (if connected then
                 sendCommand(command)
                   .catchAll(e => ZIO.logError(s"Failed to send command: ${e.getMessage}"))
               else
                 // Трекер не подключен - публикуем ошибку
                 for
                   failedResult <- createResult(
                     command,
                     CommandStatus.Failed,
                     Some(s"Tracker ${command.imei} is not connected")
                   )
                   _ <- redisClient.publish(
                     s"$RESULTS_CHANNEL_PREFIX${command.imei}",
                     failedResult.toJson
                   )
                   _ <- ZIO.logWarning(s"Tracker ${command.imei} not connected, command ${command.commandId} failed")
                 yield ())
        yield ()).catchAll(e => ZIO.logError(s"Error processing command: ${e.getMessage}"))
      }
    
    override def handleCommandResponse(imei: String, response: Array[Byte]): Task[Unit] =
      for
        // Находим pending команду для этого IMEI (чисто функционально!)
        pending <- pendingCommandsRef.get
        
        // Ищем первую pending команду для этого IMEI
        maybeEntry = pending.values
          .filter(_.command.imei == imei)
          .toList
          .sortBy(_.createdAt)
          .headOption
        
        _ <- maybeEntry match
          case Some(pendingCmd) =>
            for
              result <- createResult(
                pendingCmd.command,
                CommandStatus.Acked,
                Some(new String(response, "UTF-8").take(100))
              )
              // Завершаем Promise
              _ <- pendingCmd.promise.succeed(result)
              // Публикуем результат
              _ <- redisClient.publish(s"$RESULTS_CHANNEL_PREFIX$imei", result.toJson)
            yield ()
          case None =>
            ZIO.logDebug(s"No pending command found for IMEI: $imei")
      yield ()
    
    /**
     * Создает CommandResult с использованием ZIO Clock
     */
    private def createResult(
        command: Command, 
        status: CommandStatus, 
        message: Option[String]
    ): UIO[CommandResult] =
      Clock.instant.map { now =>
        CommandResult(
          commandId = command.commandId,
          imei = command.imei,
          status = status,
          message = message,
          timestamp = now
        )
      }
  
  /**
   * ZIO Layer - чисто функциональный
   */
  val live: ZLayer[RedisClient & ConnectionRegistry, Nothing, CommandService] =
    ZLayer {
      for
        redis <- ZIO.service[RedisClient]
        registry <- ZIO.service[ConnectionRegistry]
        pendingRef <- Ref.make(Map.empty[String, PendingCommand])
      yield Live(redis, registry, pendingRef, 30.seconds)
    }
