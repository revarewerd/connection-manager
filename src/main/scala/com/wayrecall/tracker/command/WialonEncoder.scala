package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import java.nio.charset.StandardCharsets
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// WialonEncoder — кодирование команд для Wialon IPS
// ═══════════════════════════════════════════════════════════════════════════
//
// Wialon IPS поддерживает только произвольные текстовые команды.
// Формат: #M#<текст команды>\r\n
//
// Wialon — «receive-only» в смысле управления трекером,
// но позволяет отправить сообщение через TCP канал.
// ═══════════════════════════════════════════════════════════════════════════

object WialonEncoder extends CommandEncoder:
  
  override val protocolName: String = "wialon"
  
  override val capabilities: CommandCapability = CommandCapability.Wialon
  
  override def supports(command: Command): Boolean = command match
    case _: CustomCommand => true
    case _                => false
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    command match
      case cmd: CustomCommand =>
        ZIO.attempt {
          // Формат Wialon IPS: #M#<текст>\r\n
          val text = s"#M#${cmd.commandText}\r\n"
          val bytes = text.getBytes(StandardCharsets.UTF_8)
          Unpooled.wrappedBuffer(bytes)
        }.mapError(e => ProtocolError.ParseError(s"Ошибка кодирования Wialon команды: ${e.getMessage}"))
      
      case other =>
        ZIO.fail(ProtocolError.UnsupportedProtocol(
          s"Wialon: поддерживает только CustomCommand, получено ${other.getClass.getSimpleName}"
        ))
