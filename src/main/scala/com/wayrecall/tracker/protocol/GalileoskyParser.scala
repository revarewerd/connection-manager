package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.ByteOrder

/**
 * Парсер протокола Galileosky
 * 
 * Бинарный LE-протокол с тегами. Трекеры: Galileosky Base Block, v5.0, v7.0.
 * Каждый пакет содержит набор тегов — порядок и наличие тегов произвольные.
 * Один пакет может содержать несколько GPS-записей (разделяются тегом 0x20 — datetime).
 * 
 * Формат фрейма:
 *   [Header 1B = 0x01][Size 2B LE, & 0x7FFF][Payload (теги)][CRC 2B LE]
 *   Полная длина фрейма = (Size & 0x7FFF) + 5 байт (header + size + CRC)
 * 
 * Тег IMEI: 0x03 → 15 байт ASCII
 * Тег datetime: 0x20 → uint32 LE unix seconds (маркер новой GPS-записи)
 * Тег coords: 0x30 → [correctness:4bit][sat:4bit][lat:int32 LE / 1_000_000][lon:int32 LE / 1_000_000]
 * Тег speed+course: 0x33 → [speed:uint16 LE × 0.1 km/h][course:uint16 LE × 0.1°]
 * Тег altitude: 0x34 → int16 LE (метры)
 * 
 * ACK: [0x02][CRC echo 2B LE] — эхо CRC из принятого пакетa
 * 
 * Порт в legacy STELS: 9097
 */
object GalileoskyParser extends ProtocolParser:
  
  override val protocolName: String = "galileosky"

  // Размеры данных для каждого тега (для пропуска неизвестных)
  private val tagSizes: Map[Int, Int] = Map(
    0x01 -> 1,  // Device type
    0x02 -> 1,  // Firmware
    0x03 -> 15, // IMEI (ASCII) — обрабатывается отдельно
    0x04 -> 2,  // Device num
    0x05 -> 1,  // Unknown
    0x10 -> 2,  // Archive num
    0x20 -> 4,  // Datetime — обрабатывается отдельно
    0x30 -> 9,  // Coords (1 + 4 + 4) — обрабатывается отдельно
    0x33 -> 4,  // Speed + Course — обрабатывается отдельно
    0x34 -> 2,  // Altitude — обрабатывается отдельно
    0x35 -> 1,  // HDOP
    0x40 -> 2,  // Status
    0x41 -> 2,  // Power
    0x42 -> 2,  // Battery/Acc
    0x43 -> 1,  // Temp
    0x44 -> 4,  // Accelerometer
    0x45 -> 2,  // Outputs
    0x46 -> 2,  // Inputs
    0x47 -> 4,  // EcoDrive
    0x48 -> 2,  // Extended status
    0x50 -> 2, 0x51 -> 2, 0x52 -> 2, 0x53 -> 2, // Analog inputs 0-3
    0x54 -> 2, 0x55 -> 2, 0x56 -> 2, 0x57 -> 2, // Analog inputs 4-7
    0x58 -> 2, 0x59 -> 2, // RS232 0, 1
    0x60 -> 2, 0x61 -> 2, 0x62 -> 2, // Fuel sensors 0-2 (2B)
    0x90 -> 4,  // iButton
    0xD3 -> 4,  // iButton 2
    0xD4 -> 4,  // Total mileage
    0xD5 -> 1   // iButton state
  )

  /**
   * Определяет размер данных тега с учётом диапазонных тегов
   */
  private def getTagSize(tag: Int): Option[Int] =
    tagSizes.get(tag).orElse {
      if tag >= 0x70 && tag <= 0x77 then Some(2)       // Temp sensors
      else if tag >= 0x63 && tag <= 0x6F then Some(3)   // Fuel sensors
      else if tag >= 0x80 && tag <= 0x87 then Some(3)   // DS1923
      else if tag >= 0x88 && tag <= 0xAF then Some(1)   // RS232 extended
      else if tag >= 0xB0 && tag <= 0xB9 then Some(2)   // Reserved 2B
      else if tag >= 0xC0 && tag <= 0xC3 then Some(4)   // CAN 32bit
      else if tag >= 0xC4 && tag <= 0xD2 then Some(1)   // CAN 8bit
      else if tag >= 0xD6 && tag <= 0xDA then Some(2)   // CAN 16bit
      else if tag >= 0xDB && tag <= 0xDF then Some(4)   // CAN 32bit range
      else if tag >= 0xE2 && tag <= 0xE9 then Some(4)   // User data
      else if tag >= 0xF0 && tag <= 0xF9 then Some(4)   // Reserved 4B
      else None
    }

  private var lastCrc: Short = 0 // Для ACK — кэшируем CRC последнего пакета

  /**
   * Парсит IMEI из пакета Galileosky
   * Ищет тег 0x03 (IMEI) среди тегов пакета
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
      
      if buf.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для фрейма Galileosky")
      
      val header = buf.readUnsignedByte()
      if header != 0x01 then
        throw new RuntimeException(s"Неверный заголовок Galileosky: 0x${header.toInt.toHexString}, ожидается 0x01")
      
      buf.skipBytes(2) // Пропуск size
      
      var imei: String = null
      
      // Перебираем теги, ищем IMEI (тег 0x03)
      while buf.readableBytes() > 2 && imei == null do
        val tag = buf.readUnsignedByte()
        
        if tag == 0x03 then
          // Тег IMEI — 15 байт ASCII
          if buf.readableBytes() < 15 then
            throw new RuntimeException(s"Недостаточно данных для IMEI: нужно 15, доступно ${buf.readableBytes()}")
          val imeiBytes = new Array[Byte](15)
          buf.readBytes(imeiBytes)
          imei = new String(imeiBytes, "ASCII").trim
        else
          // Пропускаем неизвестный или нерелевантный тег
          getTagSize(tag) match
            case Some(size) =>
              if buf.readableBytes() >= size then buf.skipBytes(size)
              else throw new RuntimeException(s"Недостаточно данных для тега 0x${tag.toInt.toHexString}: нужно $size, доступно ${buf.readableBytes()}")
            case None =>
              throw new RuntimeException(s"Неизвестный тег Galileosky: 0x${tag.toInt.toHexString}")
      
      // CRC (2 байта LE) — сохраняем для ACK
      if buf.readableBytes() >= 2 then
        lastCrc = buf.readShort()
      
      if imei == null then
        throw new RuntimeException("Тег IMEI (0x03) не найден в пакете Galileosky")
      
      if !imei.forall(_.isDigit) || imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI Galileosky: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Galileosky IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из пакета Galileosky
   * Перебирает теги и собирает GPS-записи (каждый тег 0x20 начинает новую запись)
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
      
      if buf.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для фрейма данных Galileosky")
      
      val header = buf.readUnsignedByte()
      if header != 0x01 then
        throw new RuntimeException(s"Неверный заголовок: 0x${header.toInt.toHexString}")
      
      buf.skipBytes(2) // Пропуск size
      
      val points = scala.collection.mutable.ListBuffer[GpsRawPoint]()
      
      // Текущее состояние парсинга — накапливаем поля GPS-записи
      var timestamp: Long = 0L
      var lat: Float = Float.NaN
      var lon: Float = Float.NaN
      var satellites: Int = 0
      var speed: Int = 0
      var course: Int = 0
      var altitude: Int = 0
      var hasDateTime = false
      
      def storePointIfReady(): Unit =
        if hasDateTime && !lat.isNaN && !lon.isNaN && satellites > 0 then
          points += GpsRawPoint(
            imei = imei,
            latitude = lat.toDouble,
            longitude = lon.toDouble,
            altitude = altitude,
            speed = speed,
            angle = course,
            satellites = satellites,
            timestamp = timestamp
          )
      
      // Перебираем теги
      while buf.readableBytes() > 2 do
        val tag = buf.readUnsignedByte()
        
        tag match
          case 0x20 => // DateTime — начало новой GPS-записи
            storePointIfReady()
            // Сброс полей
            lat = Float.NaN
            lon = Float.NaN
            satellites = 0
            speed = 0
            course = 0
            altitude = 0
            hasDateTime = true
            timestamp = buf.readUnsignedInt() * 1000L
            
          case 0x30 => // Координаты: [correctness:4bit|sat:4bit][lat:int32][lon:int32]
            val firstByte = buf.readByte()
            val correctness = (firstByte & 0xF0) >> 4
            if correctness == 0 || correctness == 2 then
              satellites = firstByte & 0x0F
              lat = buf.readInt().toFloat / 1000000.0f
              lon = buf.readInt().toFloat / 1000000.0f
              // Если 0 спутников — координаты недействительны
              if satellites == 0 then
                lat = Float.NaN
                lon = Float.NaN
            else
              satellites = 0
              lat = Float.NaN
              lon = Float.NaN
              buf.skipBytes(8) // Пропуск невалидных координат
            
          case 0x33 => // Speed + Course (uint16 × 0.1)
            speed = (buf.readUnsignedShort() * 0.1).round.toInt
            course = (buf.readUnsignedShort() * 0.1).round.toInt
            
          case 0x34 => // Altitude (int16)
            altitude = buf.readShort().toInt
            
          case other =>
            // Пропускаем остальные теги
            getTagSize(other) match
              case Some(size) =>
                if buf.readableBytes() >= size then buf.skipBytes(size)
                else
                  // Конец данных — выходим
                  buf.skipBytes(buf.readableBytes() - 2) // Оставляем CRC
              case None =>
                // Неизвестный тег — не можем определить размер, прерываем
                buf.skipBytes(Math.max(0, buf.readableBytes() - 2))
      
      // Последняя накопленная запись
      storePointIfReady()
      
      // CRC (2 байта LE) — сохраняем для ACK
      if buf.readableBytes() >= 2 then
        lastCrc = buf.readShort()
      
      if points.isEmpty then
        throw new RuntimeException("Galileosky: GPS-записей нет в пакете")
      
      points.toList
    }.mapError(e => ProtocolError.ParseError(s"Galileosky data: ${e.getMessage}"))

  /**
   * ACK для Galileosky — эхо CRC последнего принятого пакета
   * [0x02][CRC 2B LE]
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(3).order(ByteOrder.LITTLE_ENDIAN)
    buf.writeByte(0x02)
    buf.writeShort(lastCrc)
    buf

  /**
   * IMEI ACK для Galileosky — тот же формат что и data ACK
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    ack(0)

  /**
   * Кодирование команд — делегирует в CommandEncoder (receive-only)
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("galileosky").encode(command)
