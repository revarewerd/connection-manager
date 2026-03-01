package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import java.nio.charset.StandardCharsets
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// RuptelaEncoder — кодирование команд для Ruptela GPS Plus/Pro
// ═══════════════════════════════════════════════════════════════════════════
//
// Ruptela поддерживает несколько типов TCP команд:
// - 0x65: Set Parameter (reboot, interval)
// - 0x66: Device Configuration (произвольный текст)
// - 0x67: Set Output (управление выходами)
//
// Общий формат бинарного пакета:
// ┌──────────┬──────────┬──────────┬──────────┐
// │ Length 2B│ Cmd 1B   │ Data NB  │ CRC16 2B │
// └──────────┴──────────┴──────────┴──────────┘
//
// CRC16 считается от Cmd до конца Data (без Length, без CRC).
//
// Из legacy ObjectsCommander:
// - SMS: "{password} coords" / "{password} reset" / "{password} setio {0,0|1,1}"
// - TCP: deviceConfigurationCommand (0x66) для произвольных конфигов
// - ВАЖНО: SMS setio ИНВЕРТИРОВАН! 0,0 = ВКЛ, 1,1 = ВЫКЛ
// ═══════════════════════════════════════════════════════════════════════════

object RuptelaEncoder extends CommandEncoder:
  
  override val protocolName: String = "ruptela"
  
  override val capabilities: CommandCapability = CommandCapability.Ruptela
  
  /** Команда: Set Parameter */
  private val CMD_SET_PARAM: Byte = 0x65.toByte
  
  /** Команда: Device Configuration (произвольный текстовый конфиг) */
  private val CMD_DEVICE_CONFIG: Byte = 0x66.toByte
  
  /** Команда: Set Output */
  private val CMD_SET_OUTPUT: Byte = 0x67.toByte
  
  override def supports(command: Command): Boolean = command match
    case _: RebootCommand          => true
    case _: SetIntervalCommand     => true
    case _: RequestPositionCommand => true
    case _: SetOutputCommand       => true
    case _: DeviceConfigCommand    => true
    case _: CustomCommand          => true
    case _                         => false
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      command match
        case _: RebootCommand =>
          // Ruptela: restart через Set Parameter (param 0x00)
          val buf = Unpooled.buffer()
          val cmdData = Array[Byte](CMD_SET_PARAM, 0x00)
          writePacket(buf, cmdData)
          buf
        
        case cmd: SetIntervalCommand =>
          // Set Parameter: param 0x01 (interval), value = 4 bytes BE
          val buf = Unpooled.buffer()
          val data = new Array[Byte](6)
          data(0) = CMD_SET_PARAM
          data(1) = 0x01 // param: interval
          // interval как 4 bytes Big-Endian
          data(2) = ((cmd.intervalSeconds >> 24) & 0xFF).toByte
          data(3) = ((cmd.intervalSeconds >> 16) & 0xFF).toByte
          data(4) = ((cmd.intervalSeconds >> 8) & 0xFF).toByte
          data(5) = (cmd.intervalSeconds & 0xFF).toByte
          writePacket(buf, data)
          buf
        
        case _: RequestPositionCommand =>
          // Get Position: command 0x66 без данных
          val buf = Unpooled.buffer()
          writePacket(buf, Array[Byte](CMD_DEVICE_CONFIG))
          buf
        
        case cmd: SetOutputCommand =>
          // Set Output: command 0x67, idx, value
          val buf = Unpooled.buffer()
          val data = Array[Byte](
            CMD_SET_OUTPUT,
            cmd.outputIndex.toByte,
            (if cmd.enabled then 1 else 0).toByte
          )
          writePacket(buf, data)
          buf
        
        case cmd: DeviceConfigCommand =>
          // Device Configuration: 0x66 + UTF-8 текст конфига
          val buf = Unpooled.buffer()
          val textBytes = cmd.configText.getBytes(StandardCharsets.UTF_8)
          val data = new Array[Byte](1 + textBytes.length)
          data(0) = CMD_DEVICE_CONFIG
          java.lang.System.arraycopy(textBytes, 0, data, 1, textBytes.length)
          writePacket(buf, data)
          buf
        
        case cmd: CustomCommand =>
          // Произвольная команда через Device Configuration
          val buf = Unpooled.buffer()
          val textBytes = cmd.commandText.getBytes(StandardCharsets.UTF_8)
          val data = new Array[Byte](1 + textBytes.length)
          data(0) = CMD_DEVICE_CONFIG
          java.lang.System.arraycopy(textBytes, 0, data, 1, textBytes.length)
          writePacket(buf, data)
          buf
        
        case other =>
          throw new IllegalArgumentException(
            s"Ruptela не поддерживает команду типа ${other.getClass.getSimpleName}"
          )
    }.mapError(e => ProtocolError.ParseError(s"Ошибка кодирования Ruptela команды: ${e.getMessage}"))
  
  /**
   * Записывает пакет Ruptela: [Length 2B][Data NB][CRC16 2B]
   *
   * @param buf целевой буфер
   * @param cmdData данные (cmd byte + params)
   */
  private def writePacket(buf: ByteBuf, cmdData: Array[Byte]): Unit =
    // Length = размер данных (без Length и CRC)
    buf.writeShort(cmdData.length)
    
    // Data
    buf.writeBytes(cmdData)
    
    // CRC16 от cmdData
    val crc = calculateCrc16(cmdData)
    buf.writeShort(crc)
  
  /**
   * CRC-16 для Ruptela
   *
   * Полином: 0x8005 (стандартный CRC-16)
   * Начальное значение: 0x0000
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
