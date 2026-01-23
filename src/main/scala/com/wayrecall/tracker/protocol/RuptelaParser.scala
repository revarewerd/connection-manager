package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола Ruptela
 * 
 * Бинарный протокол:
 * [Packet Length 2B][IMEI 8B][Command ID 1B][Data]...[CRC 2B]
 * 
 * Command IDs:
 * - 0x01: Records (GPS data)
 * - 0x02: Extended records
 * - 0x03: Acknowledgement
 * 
 * Координаты: latitude/longitude * 10000000 (7 знаков после запятой)
 */
object RuptelaParser extends ProtocolParser:
  
  private val CMD_RECORDS: Byte = 0x01
  private val CMD_EXTENDED_RECORDS: Byte = 0x02
  private val CMD_ACK: Byte = 0x64
  
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 10 then
        throw new RuntimeException("Not enough bytes for IMEI packet")
      
      // Packet Length (2 bytes)
      val packetLength = buffer.readUnsignedShort()
      
      // IMEI (8 bytes as long)
      val imeiLong = buffer.readLong()
      val imei = imeiLong.toString.reverse.padTo(15, '0').reverse
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Ruptela IMEI: ${e.getMessage}"))
  
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException("Not enough bytes for data packet")
      
      // Packet Length (2 bytes)
      val packetLength = buffer.readUnsignedShort()
      
      // Skip IMEI (8 bytes) - уже знаем
      buffer.skipBytes(8)
      
      // Command ID (1 byte)
      val commandId = buffer.readByte()
      
      commandId match
        case CMD_RECORDS | CMD_EXTENDED_RECORDS =>
          parseRecords(buffer, imei, commandId == CMD_EXTENDED_RECORDS)
        case _ =>
          // Unknown or ACK command
          Nil
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Ruptela data: ${e.getMessage}"))
  
  /**
   * Парсит записи GPS данных
   */
  private def parseRecords(buffer: ByteBuf, imei: String, extended: Boolean): List[GpsRawPoint] =
    val recordCount = buffer.readUnsignedByte()
    
    (0 until recordCount).map { _ =>
      parseRecord(buffer, imei, extended)
    }.toList
  
  /**
   * Парсит одну запись
   * 
   * Record format:
   * - Timestamp: 4 bytes (Unix timestamp)
   * - Timestamp extension: 1 byte (milliseconds / 4)
   * - Priority/Flags: 1 byte
   * - Longitude: 4 bytes (signed, * 10^7)
   * - Latitude: 4 bytes (signed, * 10^7)
   * - Altitude: 2 bytes (signed, meters)
   * - Angle: 2 bytes (0-360)
   * - Satellites: 1 byte
   * - Speed: 2 bytes (km/h * 100)
   * - HDOP: 1 byte
   * - Event ID: 1 byte
   * - IO elements count: varies
   */
  private def parseRecord(buffer: ByteBuf, imei: String, extended: Boolean): GpsRawPoint =
    // Timestamp (4 bytes) + extension (1 byte)
    val timestamp = buffer.readUnsignedInt() * 1000L
    val timestampExt = buffer.readUnsignedByte() * 4 // миллисекунды
    val fullTimestamp = timestamp + timestampExt
    
    // Priority/Flags (1 byte)
    val flags = buffer.readByte()
    
    // Coordinates
    val longitude = buffer.readInt() / 10000000.0
    val latitude = buffer.readInt() / 10000000.0
    
    // Altitude (2 bytes, signed)
    val altitude = buffer.readShort().toInt
    
    // Angle (2 bytes)
    val angle = buffer.readUnsignedShort()
    
    // Satellites (1 byte)
    val satellites = buffer.readUnsignedByte()
    
    // Speed (2 bytes, km/h * 100)
    val speed = buffer.readUnsignedShort() / 100
    
    // HDOP (1 byte)
    val hdop = buffer.readUnsignedByte()
    
    // Event ID (1 byte)
    val eventId = buffer.readUnsignedByte()
    
    // IO elements - пропускаем для простоты
    skipIOElements(buffer, extended)
    
    GpsRawPoint(
      imei = imei,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude,
      speed = speed,
      angle = angle,
      satellites = satellites,
      timestamp = fullTimestamp
    )
  
  /**
   * Пропускает IO элементы в пакете
   */
  private def skipIOElements(buffer: ByteBuf, extended: Boolean): Unit =
    // IO element count (1 or 2 bytes depending on extended)
    val ioCount = if extended then buffer.readUnsignedShort() else buffer.readUnsignedByte()
    
    // 1-byte IO elements
    val io1Count = buffer.readUnsignedByte()
    (0 until io1Count).foreach { _ =>
      buffer.skipBytes(2) // ID (1) + Value (1)
    }
    
    // 2-byte IO elements
    val io2Count = buffer.readUnsignedByte()
    (0 until io2Count).foreach { _ =>
      buffer.skipBytes(3) // ID (1) + Value (2)
    }
    
    // 4-byte IO elements
    val io4Count = buffer.readUnsignedByte()
    (0 until io4Count).foreach { _ =>
      buffer.skipBytes(5) // ID (1) + Value (4)
    }
    
    // 8-byte IO elements
    val io8Count = buffer.readUnsignedByte()
    (0 until io8Count).foreach { _ =>
      buffer.skipBytes(9) // ID (1) + Value (8)
    }
  
  /**
   * ACK для данных
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(7)
    buf.writeShort(3)           // Packet length
    buf.writeByte(CMD_ACK)      // Command ID = ACK
    buf.writeShort(recordCount) // Records accepted
    buf.writeShort(calculateCrc16(buf, 0, 5))
    buf
  
  /**
   * ACK для IMEI
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buf = Unpooled.buffer(5)
    buf.writeShort(1)           // Packet length
    buf.writeByte(if accepted then 0x01 else 0x00)
    buf.writeShort(calculateCrc16(buf, 0, 3))
    buf
  
  /**
   * Кодирование команды для отправки
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      import com.wayrecall.tracker.domain.*
      
      val buf = Unpooled.buffer()
      
      command match
        case _: RebootCommand =>
          // Ruptela: restart command
          buf.writeShort(2)     // Length
          buf.writeByte(0x65)   // Command: Set parameter
          buf.writeByte(0x00)   // Param: restart
          
        case SetIntervalCommand(_, _, _, interval) =>
          // Set interval parameter
          buf.writeShort(6)
          buf.writeByte(0x65)   // Command: Set parameter
          buf.writeByte(0x01)   // Param: interval
          buf.writeInt(interval)
          
        case _: RequestPositionCommand =>
          // Request current position
          buf.writeShort(1)
          buf.writeByte(0x66)   // Command: Get position
          
        case SetOutputCommand(_, _, _, idx, enabled) =>
          buf.writeShort(3)
          buf.writeByte(0x67)   // Command: Set output
          buf.writeByte(idx.toByte)
          buf.writeByte(if enabled then 1 else 0)
          
        case CustomCommand(_, _, _, text) =>
          val bytes = text.getBytes("UTF-8")
          buf.writeShort(1 + bytes.length)
          buf.writeByte(0x68)   // Command: Custom
          buf.writeBytes(bytes)
      
      // Add CRC
      val crc = calculateCrc16(buf, 0, buf.readableBytes())
      buf.writeShort(crc)
      
      buf
    }.mapError(e => ProtocolError.ParseError(s"Failed to encode Ruptela command: ${e.getMessage}"))
  
  /**
   * Вычисление CRC-16
   */
  private def calculateCrc16(buffer: ByteBuf, offset: Int, length: Int): Int =
    var crc = 0xFFFF
    var i = offset
    while i < offset + length do
      val b = buffer.getByte(i) & 0xFF
      crc ^= b
      var j = 0
      while j < 8 do
        if (crc & 1) != 0 then
          crc = (crc >> 1) ^ 0xA001
        else
          crc >>= 1
        j += 1
      i += 1
    crc & 0xFFFF
