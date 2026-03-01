package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.time.{ZonedDateTime, ZoneId}

/**
 * Парсер протокола Concox/GL06 (GT06, GT02, GT03 и аналоги)
 * 
 * Бинарный BE-протокол с фреймингом через стартовые/стоповые байты.
 * Трекеры: Concox GT06, GT06N, GL06, TR02, TR06, WeTrack и аналоги.
 * 
 * Формат фрейма (общий):
 *   [0x78][0x78][Length 1B][ProtocolNum 1B][Payload...][Serial 2B][CRC 2B (CRC16-X25)][0x0D][0x0A]
 * 
 * Login (0x01):
 *   [8B BCD-encoded IMEI] → декодируется в hex строку → dropWhile('0')
 * 
 * Location (0x12):
 *   [Year 1B][Month 1B][Day 1B][Hour 1B][Min 1B][Sec 1B]
 *   [Satellites 1B]
 *   [Lat uint32 BE / 30000 / 60][Lon uint32 BE / 30000 / 60]
 *   [Speed 1B km/h][Course uint16 BE & 0x03FF]
 *   [LBS: MCC 2B, MNC 1B, LAC 2B, CID 3B]
 *   [Serial 2B]
 * 
 * Status (0x13):
 *   [5B status info][Serial 2B]
 * 
 * ACK:
 *   [0x78][0x78][0x05][ProtocolNum 1B][Serial 2B][CRC16-X25 2B][0x0D][0x0A]
 * 
 * CRC16 считается по телу: [Length][ProtocolNum][Serial] (4 байта для login ACK)
 * 
 * Порт в legacy STELS: 9094
 */
object ConcoxParser extends ProtocolParser:
  
  override val protocolName: String = "concox"
  
  // Последний serial для формирования ACK
  @volatile private var lastSerial: Short = 1
  
  /**
   * CRC16-X25 (CCITT) — для расчёта контрольной суммы ACK
   * Полином: 0x1021, init: 0xFFFF, Final XOR: 0xFFFF
   */
  private def crc16X25(data: Array[Byte]): Int =
    var crc = 0xFFFF
    for b <- data do
      crc ^= (b & 0xFF)
      for _ <- 0 until 8 do
        if (crc & 1) != 0 then
          crc = (crc >> 1) ^ 0x8408
        else
          crc >>= 1
    crc ^ 0xFFFF

  /**
   * Парсит IMEI из Login пакета Concox
   * Фрейм: [0x78][0x78][Length][0x01][IMEI 8B BCD]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для фрейма Concox")
      
      val b0 = buffer.readByte()
      val b1 = buffer.readByte()
      if b0 != 0x78 || b1 != 0x78 then
        throw new RuntimeException(s"Неверные стартовые байты Concox: 0x${(b0 & 0xFF).toHexString}${(b1 & 0xFF).toHexString}")
      
      val length = buffer.readUnsignedByte()
      val protocolNum = buffer.readByte()
      
      if protocolNum != 0x01 then
        throw new RuntimeException(s"Ожидается Login (0x01), получен: 0x${(protocolNum & 0xFF).toHexString}")
      
      if buffer.readableBytes() < 8 then
        throw new RuntimeException(s"Недостаточно данных для IMEI BCD: ${buffer.readableBytes()}")
      
      // IMEI: 8 байт BCD → hex строка → убрать ведущие нули
      val imeiBytes = new Array[Byte](8)
      buffer.readBytes(imeiBytes)
      val imeiHex = imeiBytes.map(b => f"${b & 0xFF}%02X").mkString
      val imei = imeiHex.dropWhile(_ == '0')
      
      // Пропускаем остальное + serial
      val remaining = length - 1 - 8 // protocolNum уже прочитан + IMEI
      if buffer.readableBytes() >= remaining then buffer.skipBytes(remaining)
      
      // Читаем serial для ACK (последние 2 байта перед CRC+stop)
      // Но serial уже внутри body, считан выше как часть remaining
      lastSerial = 0x01 // Для login ACK serial = 0x0001
      
      // Пропускаем CRC + stop bytes
      if buffer.readableBytes() >= 4 then buffer.skipBytes(4) // CRC(2) + 0x0D + 0x0A
      
      if imei.isEmpty || imei.length < 8 then
        throw new RuntimeException(s"Невалидный IMEI Concox: $imei (hex=$imeiHex)")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Concox IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из Location пакета Concox (0x12)
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для фрейма Concox")
      
      // Стартовые байты
      val b0 = buffer.readByte()
      val b1 = buffer.readByte()
      if b0 != 0x78 || b1 != 0x78 then
        throw new RuntimeException(s"Неверные стартовые байты: 0x${(b0 & 0xFF).toHexString}${(b1 & 0xFF).toHexString}")
      
      val length = buffer.readUnsignedByte()
      val protocolNum = buffer.readByte()
      
      protocolNum match
        case 0x12 => // Location packet
          // Datetime: Year(+2000), Month, Day, Hour, Min, Sec
          val year = buffer.readUnsignedByte() + 2000
          val month = buffer.readUnsignedByte()
          val day = buffer.readUnsignedByte()
          val hour = buffer.readUnsignedByte()
          val minute = buffer.readUnsignedByte()
          val second = buffer.readUnsignedByte()
          val dateTime = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.of("UTC"))
          val timestamp = dateTime.toInstant.toEpochMilli
          
          // Satellites
          val satellites = buffer.readUnsignedByte()
          
          // Координаты: uint32 / 30000 / 60 (градусы)
          val lat = buffer.readUnsignedInt().toDouble / 30000.0 / 60.0
          val lon = buffer.readUnsignedInt().toDouble / 30000.0 / 60.0
          
          // Speed (1 byte km/h)
          val speed = buffer.readUnsignedByte()
          
          // Course (uint16, lower 10 bits = course in degrees)
          val courseFlags = buffer.readUnsignedShort()
          val course = courseFlags & 0x03FF
          
          // Пропускаем LBS: MCC(2) + MNC(1) + LAC(2) + CID(3) = 8 байт
          if buffer.readableBytes() >= 8 then buffer.skipBytes(8)
          
          // Serial (2B) — для ACK
          if buffer.readableBytes() >= 2 then lastSerial = buffer.readShort()
          
          // Пропускаем CRC + stop bytes
          if buffer.readableBytes() >= 4 then buffer.skipBytes(4)
          
          if lat == 0.0 && lon == 0.0 then
            throw new RuntimeException("Concox: нулевые координаты (GPS fix отсутствует)")
          
          List(GpsRawPoint(
            imei = imei,
            latitude = lat,
            longitude = lon,
            altitude = 0, // Concox не передаёт высоту в стандартном пакете
            speed = speed,
            angle = course,
            satellites = satellites,
            timestamp = timestamp
          ))
          
        case 0x13 => // Status packet — пропускаем
          if buffer.readableBytes() >= 5 then buffer.skipBytes(5)
          if buffer.readableBytes() >= 2 then lastSerial = buffer.readShort()
          if buffer.readableBytes() >= 4 then buffer.skipBytes(4)
          throw new RuntimeException("Concox: получен status-пакет (0x13), без GPS данных")
          
        case other =>
          throw new RuntimeException(s"Concox: неизвестный протокол 0x${(other & 0xFF).toHexString}")
          
    }.mapError(e => ProtocolError.ParseError(s"Concox data: ${e.getMessage}"))

  /**
   * ACK для Concox — отправляется на Location и Status пакеты
   * [0x78][0x78][Length=0x05][ProtocolNum][Serial 2B][CRC16 2B][0x0D][0x0A]
   */
  override def ack(recordCount: Int): ByteBuf =
    makeResponse(0x12, lastSerial)

  /**
   * IMEI ACK для Concox (Login response)
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    makeResponse(0x01, 0x01)

  /**
   * Формирует ответ Concox с CRC16-X25
   */
  private def makeResponse(protocolNum: Int, serial: Int): ByteBuf =
    // Тело для CRC: [length][protocolNum][serial 2B]
    val bodyForCrc = Array[Byte](
      0x05.toByte,
      protocolNum.toByte,
      ((serial >> 8) & 0xFF).toByte,
      (serial & 0xFF).toByte
    )
    val crc = crc16X25(bodyForCrc)
    
    val buf = Unpooled.buffer(10)
    buf.writeByte(0x78)
    buf.writeByte(0x78)
    buf.writeByte(0x05) // length
    buf.writeByte(protocolNum)
    buf.writeShort(serial)
    buf.writeShort(crc)
    buf.writeByte(0x0D) // stop bit
    buf.writeByte(0x0A) // stop bit
    buf

  /**
   * Concox — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("concox").encode(command)
