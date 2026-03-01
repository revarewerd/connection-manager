package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import java.nio.charset.StandardCharsets
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// TeltonikaEncoder — кодирование команд для Teltonika (Codec 12)
// ═══════════════════════════════════════════════════════════════════════════
//
// Формат Teltonika Codec 12 для TCP команд:
// ┌─────────────┬──────────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐
// │ Preamble 4B │ Data Len 4B  │ Codec 1B │ Qty 1B   │ Type 1B  │ Size 4B  │ Data NB  │ CRC 4B   │
// │ 0x00000000  │ (content)    │ 0x0C     │ 0x01     │ 0x05     │ len(txt) │ UTF-8    │ CRC16    │
// └─────────────┴──────────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘
//
// Поддерживаемые команды:
// - RebootCommand       → "cpureset"
// - SetIntervalCommand  → "setparam 1001:{interval}"
// - RequestPositionCommand → "getrecord"
// - SetOutputCommand    → "setdigout {bits}" (2 бита: out1+out2)
// - SetParameterCommand → "setparam {paramId}:{paramValue}"
// - CustomCommand       → command text as-is
// ═══════════════════════════════════════════════════════════════════════════

object TeltonikaEncoder extends CommandEncoder:
  
  override val protocolName: String = "teltonika"
  
  override val capabilities: CommandCapability = CommandCapability.Teltonika
  
  override def supports(command: Command): Boolean = command match
    case _: RebootCommand          => true
    case _: SetIntervalCommand     => true
    case _: RequestPositionCommand => true
    case _: SetOutputCommand       => true
    case _: SetParameterCommand    => true
    case _: CustomCommand          => true
    case _                         => false
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      // Определяем текст команды по типу
      val commandText = command match
        case _: RebootCommand =>
          "cpureset"
        case cmd: SetIntervalCommand =>
          s"setparam 1001:${cmd.intervalSeconds}"
        case _: RequestPositionCommand =>
          "getrecord"
        case cmd: SetOutputCommand =>
          // Teltonika: setdigout с бинарными битами для двух выходов
          // out0=1,out1=0 → "setdigout 10"
          // out0=0,out1=1 → "setdigout 01"
          // Для совместимости с legacy: setdigout {value}
          val value = if cmd.enabled then "1" else "0"
          s"setdigout $value"
        case cmd: SetParameterCommand =>
          s"setparam ${cmd.paramId}:${cmd.paramValue}"
        case cmd: CustomCommand =>
          cmd.commandText
        case other =>
          throw new IllegalArgumentException(
            s"Teltonika не поддерживает команду типа ${other.getClass.getSimpleName}"
          )
      
      encodeCodec12(commandText)
    }.mapError(e => ProtocolError.ParseError(s"Ошибка кодирования Teltonika команды: ${e.getMessage}"))
  
  /**
   * Кодирует текст команды в формат Teltonika Codec 12
   */
  private def encodeCodec12(commandText: String): ByteBuf =
    val commandBytes = commandText.getBytes(StandardCharsets.UTF_8)
    
    // Размер данных: codec(1) + qty(1) + type(1) + size(4) + data + qty(1)
    val dataLength = 1 + 1 + 1 + 4 + commandBytes.length + 1
    val buffer = Unpooled.buffer(4 + 4 + dataLength + 4)
    
    // Preamble (4 bytes нулей)
    buffer.writeInt(0)
    
    // Data length
    buffer.writeInt(dataLength)
    
    // Codec ID = 0x0C (Codec 12)
    buffer.writeByte(0x0C)
    
    // Command quantity = 1
    buffer.writeByte(1)
    
    // Command type = 0x05 (server → device command)
    buffer.writeByte(0x05)
    
    // Command size
    buffer.writeInt(commandBytes.length)
    
    // Command data (UTF-8 text)
    buffer.writeBytes(commandBytes)
    
    // Command quantity (повтор для валидации)
    buffer.writeByte(1)
    
    // Вычисляем CRC16 для data portion (от codec до второго quantity)
    val dataForCrc = new Array[Byte](dataLength)
    buffer.getBytes(8, dataForCrc) // skip preamble(4) + length(4)
    val crc = calculateCrc16(dataForCrc)
    
    // CRC (4 bytes)
    buffer.writeInt(crc)
    
    buffer

  /**
   * CRC-16-IBM для Teltonika
   *
   * Полином: 0xA001 (reflected 0x8005)
   * Используется для Codec 8/8E/12 CRC
   */
  private def calculateCrc16(data: Array[Byte]): Int =
    var crc = 0x0000
    for b <- data do
      crc ^= (b & 0xFF)
      for _ <- 0 until 8 do
        if (crc & 1) != 0 then
          crc = (crc >> 1) ^ 0xA001
        else
          crc >>= 1
    crc & 0xFFFF
