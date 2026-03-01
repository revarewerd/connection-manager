package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// CommandEncoder — trait для кодирования команд в бинарный формат протокола
// ═══════════════════════════════════════════════════════════════════════════
//
// Каждый протокол, поддерживающий TCP команды, имеет свою реализацию.
// Протоколы без TCP команд (GoSafe, Concox, TK102, ADM, GTLT, MicroMayak, etc.)
// используют ReceiveOnlyEncoder, который всегда возвращает UnsupportedProtocol.
//
// Архитектура:
// CommandHandler → ConnectionEntry.parser.encodeCommand(cmd) → CommandEncoder.encode(cmd) → ByteBuf
//
// Энкодеры выделены из парсеров в отдельные файлы для:
// 1. Разделения ответственности (парсинг vs кодирование)
// 2. Упрощения тестирования (mock encoder отдельно от parser)
// 3. Поддержки сложных сценариев (NavTelecom auth sequence)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Базовый trait для кодирования команд в формат конкретного протокола
 */
trait CommandEncoder:
  
  /**
   * Название протокола (для логов и ошибок)
   */
  def protocolName: String
  
  /**
   * Кодирует команду в ByteBuf для отправки через TCP
   *
   * @param command Команда для кодирования
   * @return ByteBuf с закодированной командой или ProtocolError
   */
  def encode(command: Command): IO[ProtocolError, ByteBuf]
  
  /**
   * Проверяет, поддерживает ли этот энкодер данную команду
   */
  def supports(command: Command): Boolean
  
  /**
   * Возможности протокола по командам
   */
  def capabilities: CommandCapability

/**
 * Фабрика для получения энкодера по имени протокола
 */
object CommandEncoder:
  
  /**
   * Получить энкодер по имени протокола
   */
  def forProtocol(protocol: String): CommandEncoder =
    protocol.toLowerCase match
      case "teltonika"                  => TeltonikaEncoder
      case "navtelecom"                 => NavTelecomEncoder
      case "dtm"                        => DtmEncoder
      case "ruptela"                    => RuptelaEncoder
      case "wialon"                     => WialonEncoder
      case "wialon-binary"              => WialonEncoder
      case _                            => ReceiveOnlyEncoder(protocol)

/**
 * Заглушка для протоколов без TCP команд (receive-only)
 *
 * GoSafe, SkySim, AutophoneMayak, Concox, TK102, TK103,
 * ADM, GTLT, MicroMayak, Galileosky, Arnavi
 */
final case class ReceiveOnlyEncoder(protocolName: String) extends CommandEncoder:
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.fail(ProtocolError.UnsupportedProtocol(
      s"$protocolName: протокол не поддерживает TCP команды"
    ))
  
  override def supports(command: Command): Boolean = false
  
  override val capabilities: CommandCapability = CommandCapability.ReceiveOnly
