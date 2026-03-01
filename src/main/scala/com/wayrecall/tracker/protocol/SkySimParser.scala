package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола SkySim (SkyPatrol / SkyPatrol TT8750+)
 * 
 * Бинарный протокол, основан на Motorola modem AT-командах.
 * Трекеры: SkyPatrol TT8740, TT8750+, FL2000G и аналоги.
 * 
 * Формат IMEI-пакета (аутентификация):
 *   [Header 1B = 0xF0][Length 2B][MsgType 1B = 0x01][IMEI 15B ASCII][CRC 1B]
 * 
 * Формат пакета данных:
 *   [Header 1B = 0xF0][Length 2B][MsgType 1B = 0x02]
 *   [Lat 4B int32 * 10^6][Lon 4B int32 * 10^6][Alt 2B uint16]
 *   [Speed 1B uint8 km/h][Course 2B uint16][Satellites 1B]
 *   [Timestamp 4B uint32 unix][IO Status 2B][CRC 1B]
 * 
 * Координаты: int32 * 10^-6 (6 знаков после запятой)
 * Скорость: km/h (1 байт, 0-255)
 * CRC: XOR всех байтов от Header до последнего байта данных
 * 
 * Порт в legacy STELS: 9084
 */
object SkySimParser extends ProtocolParser:
  
  override val protocolName: String = "skysim"
  
  private val HEADER_BYTE: Int = 0xF0
  private val MSG_AUTH: Byte = 0x01
  private val MSG_DATA: Byte = 0x02
  private val MSG_ACK: Byte = 0x03
  private val COORD_DIVISOR = 1_000_000.0
  
  /**
   * Парсит IMEI из SkySim аутентификационного пакета
   * [0xF0][Length 2B][MsgType=0x01][IMEI 15B ASCII][CRC 1B]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 4 then
        throw new RuntimeException("Недостаточно данных для заголовка SkySim")
      
      val header = buffer.readUnsignedByte()
      if header != HEADER_BYTE then
        throw new RuntimeException(s"Неверный заголовок SkySim: 0x${header.toInt.toHexString}, ожидается 0xF0")
      
      val length = buffer.readUnsignedShort()
      val msgType = buffer.readByte()
      
      if msgType != MSG_AUTH then
        throw new RuntimeException(s"Ожидается AUTH (0x01), получен: 0x${(msgType & 0xFF).toHexString}")
      
      if buffer.readableBytes() < 16 then // 15 IMEI + 1 CRC
        throw new RuntimeException(s"Недостаточно данных для IMEI: нужно 16, доступно ${buffer.readableBytes()}")
      
      val imeiBytes = new Array[Byte](15)
      buffer.readBytes(imeiBytes)
      val imei = new String(imeiBytes, "ASCII").trim
      
      // CRC (1 byte) — пропускаем для простоты, проверяем только формат
      buffer.skipBytes(1)
      
      // Валидация IMEI
      if !imei.forall(_.isDigit) || imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI SkySim: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"SkySim IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из пакета SkySim
   * [0xF0][Length 2B][MsgType=0x02][Lat 4B][Lon 4B][Alt 2B][Speed 1B][Course 2B][Sat 1B][Timestamp 4B][IO 2B][CRC 1B]
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val points = scala.collection.mutable.ListBuffer[GpsRawPoint]()
      
      while buffer.readableBytes() >= 4 do
        val savedIndex = buffer.readerIndex()
        
        val header = buffer.readUnsignedByte()
        if header != HEADER_BYTE then
          // Не начало пакета — пропускаем этот байт
          buffer.readerIndex(savedIndex + 1)
        else
          val length = buffer.readUnsignedShort()
          val msgType = buffer.readByte()
          
          if msgType == MSG_DATA && buffer.readableBytes() >= 18 then
            // Координаты (int32 * 10^-6)
            val latRaw = buffer.readInt()
            val lonRaw = buffer.readInt()
            val latitude = latRaw / COORD_DIVISOR
            val longitude = lonRaw / COORD_DIVISOR
            
            // Высота (uint16)
            val altitude = buffer.readUnsignedShort()
            
            // Скорость (uint8 km/h)
            val speed = buffer.readUnsignedByte()
            
            // Курс (uint16)
            val course = buffer.readUnsignedShort()
            
            // Спутники (uint8)
            val satellites = buffer.readUnsignedByte()
            
            // Timestamp (uint32 unix seconds)
            val timestamp = buffer.readUnsignedInt() * 1000L
            
            // IO Status (2 bytes) + CRC (1 byte) — пропускаем
            if buffer.readableBytes() >= 3 then
              buffer.skipBytes(3)
            
            points += GpsRawPoint(
              imei = imei,
              latitude = latitude,
              longitude = longitude,
              altitude = altitude,
              speed = speed,
              angle = course,
              satellites = satellites,
              timestamp = timestamp
            )
          else
            // Неизвестный тип сообщения или недостаточно данных — пропускаем пакет
            val skip = math.min(length, buffer.readableBytes())
            if skip > 0 then buffer.skipBytes(skip)
      
      if points.isEmpty then
        throw new RuntimeException("SkySim: не удалось распарсить ни одну точку")
      
      points.toList
    }.mapError(e => ProtocolError.ParseError(s"SkySim data: ${e.getMessage}"))
  
  /**
   * ACK для SkySim
   * [0xF0][Length=0x0001][MsgType=0x03]
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(4)
    buf.writeByte(HEADER_BYTE)
    buf.writeShort(1)
    buf.writeByte(MSG_ACK)
    buf
  
  /**
   * IMEI ACK для SkySim
   * [0xF0][Length=0x0002][MsgType=0x03][Status: 0x01=ok, 0x00=reject]
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buf = Unpooled.buffer(5)
    buf.writeByte(HEADER_BYTE)
    buf.writeShort(2)
    buf.writeByte(MSG_ACK)
    buf.writeByte(if accepted then 0x01 else 0x00)
    buf
  
  /**
   * SkySim — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("skysim").encode(command)
