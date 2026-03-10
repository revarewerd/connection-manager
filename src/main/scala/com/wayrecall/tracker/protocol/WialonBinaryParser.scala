package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.charset.StandardCharsets
import java.nio.ByteOrder

/**
 * Парсер БИНАРНОГО протокола Wialon Retranslator (как в старом Stels)
 * 
 * Порядок байт (ВАЖНО — проверено по legacy Java WialonParser):
 *   - Размер пакета (4B): Little-Endian (ручная сборка байт)
 *   - Timestamp, Flags, BlockType, BlockSize, Speed, Course: Big-Endian (Java DataInput)
 *   - Doubles (lon, lat, height): Little-Endian (Java readLong BE → reverseBytes)
 * 
 * Структура пакета:
 * [Size 4B LE][IMEI null-terminated][Timestamp 4B BE][Flags 4B BE]
 * [Block: type:2B BE | size:4B BE | hidden:1B | dataType:1B | name:*\0 | data...]
 * 
 * Координатный блок "posinfo" (dataType=0x02):
 * [Longitude 8B LE Double][Latitude 8B LE Double][Height 8B LE Double]
 * [Speed 2B BE Short][Course 2B BE Short][Satellites 1B]
 */
object WialonBinaryParser extends ProtocolParser:
  
  override val protocolName: String = "wialon-binary"
  
  /**
   * Парсит IMEI из первого пакета
   * Формат: [Size 4B LE][IMEI null-terminated][...Body...]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 4 then
        throw new Exception("Недостаточно данных для размера пакета")
      
      // Размер пакета — Little-Endian (единственное LE поле!)
      val size = readInt32LE(buffer)
      
      if buffer.readableBytes() < size then
        throw new Exception(s"Недостаточно данных: размер=$size, доступно=${buffer.readableBytes()}")
      
      // Читаем IMEI до null-terminator
      val imei = readNullTerminatedString(buffer)
      
      if !imei.forall(_.isDigit) || imei.length != 15 then
        throw new Exception(s"Невалидный IMEI: $imei (должно быть 15 цифр)")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Wialon binary IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из бинарного пакета Wialon Retranslator
   * 
   * Каждый TCP-пакет после аутентификации содержит полный фрейм:
   * [Size 4B LE][IMEI\0][Timestamp 4B BE][Flags 4B BE][Blocks...]
   * Нужно пропустить заголовок Size+IMEI перед чтением данных.
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    if buffer.readableBytes() < 4 then ZIO.succeed(Nil)
    else ZIO.attempt {
      val allPoints = scala.collection.mutable.ArrayBuffer[GpsRawPoint]()
      
      // TCP может склеить несколько Wialon-пакетов в одном буфере — обрабатываем все
      while buffer.readableBytes() >= 4 do
        val sizePos = buffer.readerIndex()
        val packetSize = readInt32LE(buffer)
        
        if packetSize <= 0 || buffer.readableBytes() < packetSize then
          // Неполный/невалидный пакет — откатываем и выходим
          buffer.readerIndex(sizePos)
          buffer.skipBytes(buffer.readableBytes()) // пропускаем остаток, чтобы не зациклиться
        else
          // Сохраняем позицию начала тела пакета для корректного пропуска
          val bodyStart = buffer.readerIndex()
          
          // Пропускаем IMEI (null-terminated) — он уже известен
          skipNullTerminated(buffer)
          
          // Timestamp (4B Big-Endian) — Unix seconds
          val timestampSec = readInt32BE(buffer)
          val timestampMs = timestampSec.toLong * 1000L
          
          // Flags (4B Big-Endian) 
          val flags = readInt32BE(buffer)
          
          // Парсим блоки данных (координаты, датчики и т.д.)
          while buffer.readerIndex() < bodyStart + packetSize do
            if buffer.readableBytes() < 2 then
              // Недостаточно данных для blockType — выходим
              buffer.readerIndex(bodyStart + packetSize)
            else
              val blockType = readInt16BE(buffer)
              if blockType == 0 then
                // Конец данных — переходим в конец пакета
                buffer.readerIndex(bodyStart + packetSize)
              else
                val blockSize = readInt32BE(buffer)
                // Запоминаем позицию после blockSize — блок занимает ровно blockSize байт
                val blockBodyStart = buffer.readerIndex()
                
                val hidden = buffer.readByte() == 1
                val dataType = buffer.readByte()
                val blockName = readNullTerminatedString(buffer)
                
                // Координатный блок "posinfo"
                if blockName == "posinfo" then
                  val lon = readDoubleBytesLE(buffer)
                  val lat = readDoubleBytesLE(buffer)
                  val height = readDoubleBytesLE(buffer)
                  val speed = readInt16BE(buffer)
                  var course = readInt16BE(buffer).toInt
                  
                  // Нормализуем курс (как в legacy коде)
                  while course < 0 do course += 360
                  
                  val satellites = buffer.readByte()
                  
                  // Используем timestamp из заголовка пакета
                  val point = GpsRawPoint(
                    imei = imei,
                    latitude = lat,
                    longitude = lon,
                    altitude = height.toInt,
                    speed = speed,
                    angle = course.toShort,
                    satellites = satellites,
                    timestamp = timestampMs
                  )
                  allPoints += point
                
                // Безопасный переход к следующему блоку (blockSize байт от начала тела блока)
                buffer.readerIndex(blockBodyStart + blockSize)
          
          // Безопасный переход к концу текущего пакета
          buffer.readerIndex(bodyStart + packetSize)
      
      allPoints.toList
    }.mapError(e => ProtocolError.ParseError(s"Wialon binary data: ${e.getMessage}"))
  
  /** ACK не требуется для Wialon Retranslator */
  override def ack(recordCount: Int): ByteBuf =
    Unpooled.buffer(0)
  
  /** IMEI ACK не требуется для Wialon Retranslator */
  override def imeiAck(accepted: Boolean): ByteBuf =
    Unpooled.buffer(0)
  
  /** Кодирование команды — делегирует в WialonEncoder (текстовый формат) */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.WialonEncoder.encode(command)
  
  // ============ Чтение строк ============
  
  /** Читает null-terminated строку из буфера */
  private def readNullTerminatedString(buf: ByteBuf): String =
    val bytes = scala.collection.mutable.ArrayBuffer[Byte]()
    var b = buf.readByte()
    while b != 0 do
      bytes += b
      b = buf.readByte()
    new String(bytes.toArray, StandardCharsets.US_ASCII)
  
  /** Пропускает null-terminated строку в буфере */
  private def skipNullTerminated(buf: ByteBuf): Unit =
    while buf.readByte() != 0 do ()
  
  // ============ Little-Endian (только для Size пакета) ============
  
  private def readInt32LE(buf: ByteBuf): Int =
    val b0 = buf.readByte() & 0xFF
    val b1 = buf.readByte() & 0xFF
    val b2 = buf.readByte() & 0xFF
    val b3 = buf.readByte() & 0xFF
    (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
  
  // ============ Big-Endian (все остальные числовые поля) ============
  
  private def readInt16BE(buf: ByteBuf): Short =
    val b0 = buf.readByte() & 0xFF
    val b1 = buf.readByte() & 0xFF
    ((b0 << 8) | b1).toShort
  
  private def readInt32BE(buf: ByteBuf): Int =
    val b0 = buf.readByte() & 0xFF
    val b1 = buf.readByte() & 0xFF
    val b2 = buf.readByte() & 0xFF
    val b3 = buf.readByte() & 0xFF
    (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
  
  // ============ Double как LE bytes (как в legacy: readLong BE → reverseBytes) ============
  
  /** 
   * Читает Double в формате LE bytes.
   * Эквивалент Java: Double.longBitsToDouble(Long.reverseBytes(dataInput.readLong()))
   * readLong() BE → reverseBytes = по сути читаем 8 байт как LE long → longBitsToDouble
   */
  private def readDoubleBytesLE(buf: ByteBuf): Double =
    val b0 = buf.readByte() & 0xFFL
    val b1 = buf.readByte() & 0xFFL
    val b2 = buf.readByte() & 0xFFL
    val b3 = buf.readByte() & 0xFFL
    val b4 = buf.readByte() & 0xFFL
    val b5 = buf.readByte() & 0xFFL
    val b6 = buf.readByte() & 0xFFL
    val b7 = buf.readByte() & 0xFFL
    // LE: первый байт = младший
    val longVal = (b7 << 56) | (b6 << 48) | (b5 << 40) | (b4 << 32) |
                  (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
    java.lang.Double.longBitsToDouble(longVal)
