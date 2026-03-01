package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import java.nio.charset.StandardCharsets
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// NavTelecomEncoder — кодирование команд для NavTelecom FLEX (NTCB)
// ═══════════════════════════════════════════════════════════════════════════
//
// NavTelecom использует NTCB бинарный протокол для команд.
// КРИТИЧЕСКАЯ особенность: IOSwitch требует предварительной аутентификации!
//
// Сценарий блокировки двигателя (из legacy ObjectsCommander):
// ┌─────────────────────────────────────────────────────────────────┐
// │ 1. CM → Трекер: ">PASS:{password}"  (PasswordCommand)         │
// │ 2. Трекер → CM: "PASS"              (auth success)            │
// │ 3. CM → Трекер: "!{n}Y"             (SetOutputCommand ON)     │
// │ 4. Трекер → CM: подтверждение в следующем GPS-пакете (OUT=1)  │
// └─────────────────────────────────────────────────────────────────┘
//
// Формат NTCB FLEX пакета:
// ┌─────────────┬──────────────┬──────────────┬─────────┬──────────┐
// │ Signature   │ Length 2B LE │ MsgID 2B LE  │ Data NB │ CRC 2B   │
// │ "*>"=0x2A3E │ (content)    │ 0x0300       │ varies  │ CRC16    │
// └─────────────┴──────────────┴──────────────┴─────────┴──────────┘
//
// Поддерживаемые команды:
// - PasswordCommand    → ">PASS:{password}" (текстовый формат)
// - SetOutputCommand   → "!{n}Y" / "!{n}N" (текстовый формат, после auth)
// - CustomCommand      → произвольный текст через NTCB
// ═══════════════════════════════════════════════════════════════════════════

object NavTelecomEncoder extends CommandEncoder:
  
  override val protocolName: String = "navtelecom"
  
  override val capabilities: CommandCapability = CommandCapability.NavTelecom
  
  /** Сигнатура NTCB пакета: "*>" */
  private val SIGNATURE: Short = 0x2A3E.toShort
  
  /** Message ID для команд сервера */
  private val CMD_MSG_ID: Short = 0x0300.toShort
  
  override def supports(command: Command): Boolean = command match
    case _: PasswordCommand  => true
    case _: SetOutputCommand => true
    case _: CustomCommand    => true
    case _                   => false
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      command match
        case cmd: PasswordCommand =>
          // Аутентификация: ">PASS:{password}"
          val text = s">PASS:${cmd.password}"
          encodeNtcbText(text)
        
        case cmd: SetOutputCommand =>
          // IOSwitch: "!{n}Y" (включить) или "!{n}N" (выключить)
          // outputIndex: 1-based в протоколе NavTelecom
          val outNum = cmd.outputIndex + 1 // 0-based → 1-based
          val action = if cmd.enabled then "Y" else "N"
          val text = s"!${outNum}$action"
          encodeNtcbText(text)
        
        case cmd: CustomCommand =>
          encodeNtcbText(cmd.commandText)
        
        case other =>
          throw new IllegalArgumentException(
            s"NavTelecom не поддерживает команду типа ${other.getClass.getSimpleName}"
          )
    }.mapError(e => ProtocolError.ParseError(s"Ошибка кодирования NavTelecom команды: ${e.getMessage}"))
  
  /**
   * Кодирует текстовую команду в NTCB FLEX формат
   *
   * Структура пакета:
   * [Signature 2B][Length 2B LE][MsgID 2B LE][CmdCode 1B][Data NB][CRC 2B LE]
   */
  private def encodeNtcbText(text: String): ByteBuf =
    val textBytes = text.getBytes(StandardCharsets.UTF_8)
    val buf = Unpooled.buffer()
    
    // Signature: "*>" (0x2A, 0x3E)
    buf.writeShort(SIGNATURE)
    
    // Placeholder для Length (заполним после записи данных)
    val lengthIdx = buf.writerIndex()
    buf.writeShortLE(0)
    
    // Message ID: 0x0300 (команда от сервера)
    buf.writeShortLE(CMD_MSG_ID)
    
    // Command code: 0x01 (текстовая команда)
    buf.writeByte(0x01)
    
    // Command data (UTF-8 текст)
    buf.writeBytes(textBytes)
    
    // Обновляем длину: от MsgID до конца данных (перед CRC)
    val dataLength = buf.writerIndex() - lengthIdx - 2
    buf.setShortLE(lengthIdx, dataLength)
    
    // Вычисляем CRC16-CCITT для данных (от MsgID до конца данных)
    val crcData = new Array[Byte](dataLength)
    buf.getBytes(lengthIdx + 2, crcData)
    val crc = calculateCrc16Ccitt(crcData)
    
    // CRC (2 bytes LE)
    buf.writeShortLE(crc)
    
    buf
  
  /**
   * CRC-16-CCITT для NavTelecom FLEX
   *
   * Полином: 0x1021 (CCITT)
   * Начальное значение: 0xFFFF
   */
  private def calculateCrc16Ccitt(data: Array[Byte]): Int =
    var crc = 0xFFFF
    for b <- data do
      crc ^= (b & 0xFF) << 8
      for _ <- 0 until 8 do
        if (crc & 0x8000) != 0 then
          crc = (crc << 1) ^ 0x1021
        else
          crc <<= 1
    crc & 0xFFFF
