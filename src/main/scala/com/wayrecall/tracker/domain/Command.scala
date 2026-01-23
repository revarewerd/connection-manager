package com.wayrecall.tracker.domain

import zio.json.*
import java.time.Instant

/**
 * Базовый trait для команд, отправляемых на GPS трекеры
 */
sealed trait Command:
  def commandId: String
  def imei: String
  def timestamp: Instant

object Command:
  given JsonCodec[Command] = DeriveJsonCodec.gen[Command]

/**
 * Команда перезагрузки трекера
 */
final case class RebootCommand(
    commandId: String,
    imei: String,
    timestamp: Instant
) extends Command derives JsonCodec

/**
 * Команда установки интервала отправки данных
 */
final case class SetIntervalCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    intervalSeconds: Int
) extends Command derives JsonCodec

/**
 * Команда запроса текущей позиции
 */
final case class RequestPositionCommand(
    commandId: String,
    imei: String,
    timestamp: Instant
) extends Command derives JsonCodec

/**
 * Команда управления реле (выходами)
 */
final case class SetOutputCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    outputIndex: Int,
    enabled: Boolean
) extends Command derives JsonCodec

/**
 * Произвольная текстовая команда
 */
final case class CustomCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    commandText: String
) extends Command derives JsonCodec

/**
 * Статус выполнения команды
 */
enum CommandStatus derives JsonCodec:
  case Pending   // Команда в очереди
  case Sent      // Отправлено на трекер
  case Acked     // Трекер подтвердил получение
  case Executed  // Трекер выполнил команду
  case Failed    // Ошибка выполнения
  case Timeout   // Трекер не ответил

/**
 * Результат выполнения команды
 */
final case class CommandResult(
    commandId: String,
    imei: String,
    status: CommandStatus,
    message: Option[String],
    timestamp: Instant
) derives JsonCodec

/**
 * Информация о pending команде (для Redis storage)
 */
final case class PendingCommand(
    command: Command,
    createdAt: Instant,
    retryCount: Int = 0,
    maxRetries: Int = 3
) derives JsonCodec
