package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.charset.StandardCharsets
import java.nio.ByteOrder

/**
 * Парсер БИНАРНОГО протокола Wialon (как в старом Stels)
 * 
 * Структура пакета:
 * [Size 4B Little-Endian][IMEI null-terminated][Timestamp 4B][Flags 4B]
 * [Block1: type:2B size:4B hidden:1B dataType:1B name:* nullTerm][...data...]
 * 
 * Координатный блок "posinfo":
 * [Longitude 8B Double][Latitude 8B Double][Height 8B Double]
 * [Speed 2B Short][Course 2B Short][Satellites 1B]
 * 
 * Это ПРЯМАЯ копия формата из ru.sosgps.wayrecall.wialonparser.WialonParser (Java)
 */
object WialonBinaryParser extends ProtocolParser:
  
  override val protocolName: String = "wialon-binary"
  
  /**
   * Парсит IMEI из первого пакета
   * Формат: [Size 4B][IMEI null-terminated][...остальное...]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      // Перепроверяем, что это бинарный формат (первые 4 байта = длина в little-endian)
      if buffer.readableBytes() < 4 then
        throw new Exception("Недостаточно данных для размера пакета")
      
      // Читаем размер (little-endian, как в Java WialonParser)
      val sizeBytes = new Array[Byte](4)
      buffer.getBytes(buffer.readerIndex(), sizeBytes)
      
      val size = (sizeBytes(3) & 0xFF) << 24
                | (sizeBytes(2) & 0xFF) << 16
                | (sizeBytes(1) & 0xFF) << 8
                | (sizeBytes(0) & 0xFF)
      
      if buffer.readableBytes() < size + 4 then
        throw new Exception(s"Недостаточно данных: размер=$size, доступно=${buffer.readableBytes() - 4}")
      
      // Пропускаем размер (4 байта)
      buffer.skipBytes(4)
      
      // Читаем IMEI до null-terminator
      val imeiBytes = scala.collection.mutable.ArrayBuffer[Byte]()
      var byte = buffer.readByte()
      while byte != 0 do
        imeiBytes += byte
        byte = buffer.readByte()
      
      val imei = new String(imeiBytes.toArray, StandardCharsets.US_ASCII)
      
      if !imei.forall(_.isDigit) || imei.length != 15 then
        throw new Exception(s"Невалидный IMEI: $imei (должно быть 15 цифр)")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Wialon binary IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из бинарного пакета
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      // Структура уже сдвинута на позицию после IMEI
      // Читаем timestamp (4B, little-endian) и flags (4B)
      val timestamp = readInt32LE(buffer)
      val flags = readInt32LE(buffer)
      
      // Парсим блоки (пока не закончится буфер или blockType == 0)
      val points = scala.collection.mutable.ArrayBuffer[GpsRawPoint]()
      var continue = true
      
      while buffer.readableBytes() >= 2 && continue do
        val blockType = readInt16LE(buffer)
        if blockType == 0 then
          // Конец пакета
          continue = false
        else
          val blockSize = readInt32LE(buffer)
          val hidden = buffer.readByte() == 1
          val dataType = buffer.readByte()
          
          // Читаем имя блока (null-terminated string)
          val nameBytes = scala.collection.mutable.ArrayBuffer[Byte]()
          var byte = buffer.readByte()
          while byte != 0 do
            nameBytes += byte
            byte = buffer.readByte()
          val blockName = new String(nameBytes.toArray, StandardCharsets.US_ASCII)
          
          // Обрабатываем координатный блок
          if blockName == "posinfo" && dataType == 0x02 then
            val lon = readDouble64LE(buffer)
            val lat = readDouble64LE(buffer)
            val height = readDouble64LE(buffer)
            val speed = readInt16LE(buffer)
            var course = readInt16LE(buffer)
            
            // Нормализуем курс (как в старом коде)
            while course < 0 do course = (course + 360).toShort
            
            val satellites = buffer.readByte()
            
            val point = GpsRawPoint(
              imei = imei,
              latitude = lat,
              longitude = lon,
              altitude = height.toInt,
              speed = speed,
              angle = course,
              satellites = satellites,
              timestamp = java.lang.System.currentTimeMillis() // TODO: может нужно использовать timestamp из заголовка?
            )
            points += point
          else
            // Пропускаем неизвестные блоки
            val dataSize = blockSize - 2 - dataType.toInt // вычитаем заголовок
            if buffer.readableBytes() >= dataSize then
              buffer.skipBytes(dataSize)
      
      points.toList
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Wialon binary data: ${e.getMessage}"))
  
  /**
   * Создает ACK для подтверждения приема
   */
  override def ack(recordCount: Int): ByteBuf =
    // Wialon binary обычно не требует ACK, pero для совместимости возвращаем пустой
    Unpooled.buffer(0)
  
  /**
   * Создает ACK для подтверждения IMEI
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    Unpooled.buffer(0)
  
  /**
   * Кодирование команды для отправки на трекер
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.fail(ProtocolError.ParseError("Wialon binary commands не реализованы"))
  
  // ============ Вспомогательные функции для чтения little-endian ============
  
  private def readInt16LE(buf: ByteBuf): Short =
    val b0 = buf.readByte() & 0xFF
    val b1 = buf.readByte() & 0xFF
    ((b1 << 8) | b0).toShort
  
  private def readInt32LE(buf: ByteBuf): Int =
    val b0 = buf.readByte() & 0xFF
    val b1 = buf.readByte() & 0xFF
    val b2 = buf.readByte() & 0xFF
    val b3 = buf.readByte() & 0xFF
    (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
  
  private def readDouble64LE(buf: ByteBuf): Double =
    val longVal = readInt64LE(buf)
    java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(longVal))
  
  private def readInt64LE(buf: ByteBuf): Long =
    val b0 = buf.readByte() & 0xFFL
    val b1 = buf.readByte() & 0xFFL
    val b2 = buf.readByte() & 0xFFL
    val b3 = buf.readByte() & 0xFFL
    val b4 = buf.readByte() & 0xFFL
    val b5 = buf.readByte() & 0xFFL
    val b6 = buf.readByte() & 0xFFL
    val b7 = buf.readByte() & 0xFFL
    (b7 << 56) | (b6 << 48) | (b5 << 40) | (b4 << 32) | (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
