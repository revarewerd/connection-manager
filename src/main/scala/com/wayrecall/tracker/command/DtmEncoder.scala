package com.wayrecall.tracker.command

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*

// ═══════════════════════════════════════════════════════════════════════════
// DtmEncoder — кодирование команд для ДТМ (DTM) трекеров
// ═══════════════════════════════════════════════════════════════════════════
//
// DTM использует бинарный протокол для IOSwitch команд.
// Из legacy DTMServer.java: команда IOSwitch через TCP.
//
// Формат пакета IOSwitch:
// ┌────────┬────────┬────────┬──────────┬────────────┬───────┬────────┐
// │ Start  │ Length │ 0xFF   │ Checksum │ IOSwitch   │ Value │ End    │
// │ 0x7B   │ 0x02   │ marker │ XOR      │ 0x09+ion   │ 0/1   │ 0x7D   │
// └────────┴────────┴────────┴──────────┴────────────┴───────┴────────┘
//
// Checksum = XOR всех байтов данных (от 0xFF маркера до Value включительно)
//
// Пример: включить выход 0 → [0x7B, 0x02, 0xFF, checksum, 0x09, 0x01, 0x7D]
// Пример: выключить выход 1 → [0x7B, 0x02, 0xFF, checksum, 0x0A, 0x00, 0x7D]
//
// ionum: 0x09 = output 0, 0x0A = output 1
// value: 0x01 = ON, 0x00 = OFF
// ═══════════════════════════════════════════════════════════════════════════

object DtmEncoder extends CommandEncoder:
  
  override val protocolName: String = "dtm"
  
  override val capabilities: CommandCapability = CommandCapability.Dtm
  
  /** Стартовый байт пакета */
  private val START_BYTE: Byte = 0x7B.toByte
  
  /** Конечный байт пакета */
  private val END_BYTE: Byte = 0x7D.toByte
  
  /** Маркер данных */
  private val DATA_MARKER: Byte = 0xFF.toByte
  
  /** Базовый номер IOSwitch (output 0 = 0x09) */
  private val IO_SWITCH_BASE: Int = 0x09
  
  override def supports(command: Command): Boolean = command match
    case _: SetOutputCommand => true
    case _                   => false
  
  override def encode(command: Command): IO[ProtocolError, ByteBuf] =
    command match
      case cmd: SetOutputCommand =>
        encodeIoSwitch(cmd.outputIndex, cmd.enabled)
      case other =>
        ZIO.fail(ProtocolError.UnsupportedProtocol(
          s"DTM: команда ${other.getClass.getSimpleName} не поддерживается, только SetOutput"
        ))
  
  /**
   * Кодирует IOSwitch команду в бинарный формат DTM
   *
   * @param outputIndex Номер выхода (0-based)
   * @param enabled true = включить, false = выключить
   */
  private def encodeIoSwitch(outputIndex: Int, enabled: Boolean): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      // Номер IOSwitch: 0x09 + outputIndex
      val ioSwitchByte = (IO_SWITCH_BASE + outputIndex).toByte
      val valueByte: Byte = if enabled then 0x01 else 0x00
      
      // Данные для XOR контрольной суммы: [0xFF, ioSwitch, value]
      val dataBytes = Array[Byte](DATA_MARKER, ioSwitchByte, valueByte)
      
      // XOR checksum всех байтов данных
      val checksum = dataBytes.foldLeft(0.toByte)((acc, b) => (acc ^ b).toByte)
      
      val buf = Unpooled.buffer(7)
      
      // [Start][Length][0xFF][Checksum][IOSwitch][Value][End]
      buf.writeByte(START_BYTE)     // 0x7B - начало пакета
      buf.writeByte(0x02)           // Длина данных (ioSwitch + value = 2 байта)
      buf.writeByte(DATA_MARKER)    // 0xFF - маркер
      buf.writeByte(checksum)       // XOR checksum
      buf.writeByte(ioSwitchByte)   // 0x09 + outputIndex
      buf.writeByte(valueByte)      // 0x01 (ON) или 0x00 (OFF)
      buf.writeByte(END_BYTE)       // 0x7D - конец пакета
      
      buf
    }.mapError(e => ProtocolError.ParseError(s"Ошибка кодирования DTM IOSwitch: ${e.getMessage}"))
