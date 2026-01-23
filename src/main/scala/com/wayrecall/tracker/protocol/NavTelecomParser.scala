package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола NavTelecom FLEX
 * 
 * Бинарный протокол:
 * [Signature 2B "*>"][Length 2B][MessageId 2B][Data]...[CRC 2B]
 * 
 * Signature: 0x2A 0x3E ("*>")
 * MessageId: 0xF100 (FLEX данные)
 * CRC: CRC-16-CCITT
 * Координаты: degrees * 10^7
 */
object NavTelecomParser extends ProtocolParser:
  
  private val SIGNATURE: Short = 0x2A3E  // "*>"
  private val MSG_FLEX: Short = 0xF100.toShort
  private val MSG_IDENT: Short = 0x0100.toShort
  private val MSG_ACK: Short = 0x0200.toShort
  
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 6 then
        throw new RuntimeException("Not enough bytes for header")
      
      // Signature (2 bytes)
      val signature = buffer.readShort()
      if signature != SIGNATURE then
        throw new RuntimeException(s"Invalid signature: ${Integer.toHexString(signature.toInt & 0xFFFF)}, expected: ${Integer.toHexString(SIGNATURE.toInt & 0xFFFF)}")
      
      // Length (2 bytes, little-endian)
      val length = buffer.readShortLE()
      
      // Message ID (2 bytes, little-endian)
      val messageId = buffer.readShortLE()
      
      if messageId != MSG_IDENT then
        throw new RuntimeException(s"Expected IDENT message, got: ${Integer.toHexString(messageId.toInt & 0xFFFF)}")
      
      // IMEI (15 bytes ASCII)
      val imeiBytes = new Array[Byte](15)
      buffer.readBytes(imeiBytes)
      val imei = new String(imeiBytes, "ASCII").trim
      
      // Skip CRC
      buffer.skipBytes(2)
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse NavTelecom IMEI: ${e.getMessage}"))
  
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 6 then
        throw new RuntimeException("Not enough bytes for data packet")
      
      // Signature (2 bytes)
      val signature = buffer.readShort()
      if signature != SIGNATURE then
        throw new RuntimeException(s"Invalid signature: ${Integer.toHexString(signature.toInt & 0xFFFF)}")
      
      // Length (2 bytes, little-endian)
      val length = buffer.readShortLE()
      
      // Message ID (2 bytes, little-endian)
      val messageId = buffer.readShortLE()
      
      messageId match
        case MSG_FLEX =>
          parseFlexData(buffer, imei, length - 2) // -2 for messageId
        case _ =>
          // Unknown message type
          buffer.skipBytes(length - 2 + 2) // Skip data + CRC
          Nil
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse NavTelecom data: ${e.getMessage}"))
  
  /**
   * Парсит FLEX данные
   * 
   * FLEX record format:
   * - Record ID: 2 bytes
   * - Timestamp: 4 bytes (Unix timestamp)
   * - Flags: 1 byte
   * - Latitude: 4 bytes (degrees * 10^7, signed)
   * - Longitude: 4 bytes (degrees * 10^7, signed)
   * - Altitude: 2 bytes (meters, signed)
   * - Speed: 2 bytes (km/h * 10)
   * - Course: 2 bytes (degrees * 10)
   * - Satellites: 1 byte
   * - HDOP: 1 byte (* 10)
   * - IO data: variable
   */
  private def parseFlexData(buffer: ByteBuf, imei: String, dataLength: Int): List[GpsRawPoint] =
    val startIndex = buffer.readerIndex()
    val points = scala.collection.mutable.ListBuffer[GpsRawPoint]()
    
    while buffer.readerIndex() - startIndex < dataLength - 2 do // -2 for CRC
      try
        val point = parseFlexRecord(buffer, imei)
        points += point
      catch
        case _: Exception =>
          // Прерываем парсинг если что-то пошло не так
          val remaining = dataLength - 2 - (buffer.readerIndex() - startIndex)
          if remaining > 0 then buffer.skipBytes(remaining)
    
    // Skip CRC
    buffer.skipBytes(2)
    
    points.toList
  
  /**
   * Парсит одну FLEX запись
   */
  private def parseFlexRecord(buffer: ByteBuf, imei: String): GpsRawPoint =
    // Record ID (2 bytes)
    val recordId = buffer.readShortLE()
    
    // Timestamp (4 bytes)
    val timestamp = buffer.readUnsignedIntLE() * 1000L
    
    // Flags (1 byte)
    val flags = buffer.readByte()
    val hasGps = (flags & 0x01) != 0
    
    // Coordinates
    val latitude = buffer.readIntLE() / 10000000.0
    val longitude = buffer.readIntLE() / 10000000.0
    
    // Altitude (2 bytes)
    val altitude = buffer.readShortLE().toInt
    
    // Speed (2 bytes, km/h * 10)
    val speed = buffer.readUnsignedShortLE() / 10
    
    // Course (2 bytes, degrees * 10)
    val course = buffer.readUnsignedShortLE() / 10
    
    // Satellites (1 byte)
    val satellites = buffer.readUnsignedByte()
    
    // HDOP (1 byte)
    val hdop = buffer.readUnsignedByte()
    
    // IO elements count (1 byte)
    val ioCount = buffer.readUnsignedByte()
    
    // Skip IO elements
    (0 until ioCount).foreach { _ =>
      val ioId = buffer.readUnsignedByte()
      val ioType = buffer.readUnsignedByte() // 1=byte, 2=short, 4=int, 8=long
      ioType match
        case 1 => buffer.skipBytes(1)
        case 2 => buffer.skipBytes(2)
        case 4 => buffer.skipBytes(4)
        case 8 => buffer.skipBytes(8)
        case _ => // Unknown type
    }
    
    GpsRawPoint(
      imei = imei,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude,
      speed = speed,
      angle = course,
      satellites = satellites,
      timestamp = timestamp
    )
  
  /**
   * ACK для данных
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(10)
    
    // Signature
    buf.writeShort(SIGNATURE)
    
    // Length (4 bytes: msgId + recordCount)
    buf.writeShortLE(4)
    
    // Message ID (ACK)
    buf.writeShortLE(MSG_ACK)
    
    // Record count
    buf.writeShortLE(recordCount.toShort)
    
    // CRC
    buf.writeShortLE(calculateCrc16Ccitt(buf, 2, 6))
    
    buf
  
  /**
   * ACK для IMEI
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buf = Unpooled.buffer(8)
    
    // Signature
    buf.writeShort(SIGNATURE)
    
    // Length (3 bytes: msgId + result)
    buf.writeShortLE(3)
    
    // Message ID (ACK)
    buf.writeShortLE(MSG_ACK)
    
    // Result (1 byte)
    buf.writeByte(if accepted then 0x01 else 0x00)
    
    // CRC
    buf.writeShortLE(calculateCrc16Ccitt(buf, 2, 5))
    
    buf
  
  /**
   * Кодирование команды
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      import com.wayrecall.tracker.domain.*
      
      val buf = Unpooled.buffer()
      
      // Signature
      buf.writeShort(SIGNATURE)
      
      val cmdCode: Byte = command match
        case _: RebootCommand => 0x01
        case _: SetIntervalCommand => 0x02
        case _: RequestPositionCommand => 0x03
        case _: SetOutputCommand => 0x04
        case _: CustomCommand => 0x05
      
      // Placeholder for length
      val lengthIdx = buf.writerIndex()
      buf.writeShortLE(0)
      
      // Message ID (command)
      buf.writeShortLE(0x0300.toShort)
      
      // Command code
      buf.writeByte(cmdCode)
      
      // Command-specific data
      command match
        case SetIntervalCommand(_, _, _, interval) =>
          buf.writeIntLE(interval)
          
        case SetOutputCommand(_, _, _, idx, enabled) =>
          buf.writeByte(idx.toByte)
          buf.writeByte(if enabled then 1 else 0)
          
        case CustomCommand(_, _, _, text) =>
          val bytes = text.getBytes("UTF-8")
          buf.writeBytes(bytes)
          
        case _ => // No additional data
      
      // Update length
      val dataLength = buf.writerIndex() - lengthIdx - 2
      buf.setShortLE(lengthIdx, dataLength)
      
      // CRC
      buf.writeShortLE(calculateCrc16Ccitt(buf, 2, buf.readableBytes() - 2))
      
      buf
    }.mapError(e => ProtocolError.ParseError(s"Failed to encode NavTelecom command: ${e.getMessage}"))
  
  /**
   * CRC-16-CCITT calculation
   */
  private def calculateCrc16Ccitt(buffer: ByteBuf, offset: Int, length: Int): Int =
    var crc = 0xFFFF
    var i = offset
    while i < offset + length do
      val b = buffer.getByte(i) & 0xFF
      crc ^= (b << 8)
      var j = 0
      while j < 8 do
        if (crc & 0x8000) != 0 then
          crc = (crc << 1) ^ 0x1021
        else
          crc <<= 1
        crc &= 0xFFFF
        j += 1
      i += 1
    crc
