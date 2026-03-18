package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.time.{LocalDate, LocalTime, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * Парсер протокола GTLT3MT1 (GT-LITE, МИНИ-трекеры)
 * 
 * Текстовый CSV протокол, разделитель — запятая, фреймы разделяются '#'.
 * Трекеры: GT-LITE 3MT1, H02, H06, H08 и аналоги (китайский OEM).
 * 
 * Формат:
 *   *HQ,serial,command,time(HHmmss),validity,lat(DDMM.MMMM),N/S,
 *   lon(DDDMM.MMMM),E/W,speed,direction,date(ddMMyy),status#
 * 
 * Поля:
 *   [0] = *HQ (маркер)
 *   [1] = serial (уникальный ID трекера, часто IMEI)
 *   [2] = command (V1=GPS, V4=LBS, S20=heartbeat и др.)
 *   [3] = time (HHmmss)
 *   [4] = validity (A=valid, V=invalid)
 *   [5] = latitude (DDMM.MMMM) — NMEA формат
 *   [6] = N/S
 *   [7] = longitude (DDDMM.MMMM) — NMEA формат
 *   [8] = E/W
 *   [9] = speed (knots)
 *   [10] = direction (degrees)
 *   [11] = date (ddMMyy)
 *   [12] = status (hex flags)
 * 
 * NMEA: DDMM.MMMM → deg = first 2(3) chars, min = rest → deg + min/60
 * 
 * ВАЖНО: в legacy N/S и E/W читаются, но не применяются для инверсии.
 * Мы исправляем этот баг.
 * 
 * ACK: эхо первых 4 полей через запятую + "#"
 *   Пример: *HQ,serial,V1,083012#
 * 
 * Порт в legacy STELS: 9093
 */
object GtltParser extends ProtocolParser:
  
  override val protocolName: String = "gtlt"
  
  private val dateFormat = DateTimeFormatter.ofPattern("ddMMyy")
  private val timeFormat = DateTimeFormatter.ofPattern("HHmmss")
  
  // Кэш для ACK
  @volatile private var lastAckPrefix: String = ""
  
  /**
   * Парсит IMEI из первого GPS-пакета GTLT
   * serial — поле [1], используется как IMEI
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val message = readFrame(buffer)
      
      if message.isEmpty then
        throw new RuntimeException("Пустой фрейм GTLT")
      
      val elems = message.split(",").map(_.trim).toSeq
      
      if elems.length < 3 then
        throw new RuntimeException(s"Недостаточно полей для GTLT: ${elems.length}")
      
      // Проверяем маркер *HQ
      if !elems.head.startsWith("*HQ") then
        throw new RuntimeException(s"Ожидается *HQ маркер, получено: ${elems.head}")
      
      val serial = elems(1)
      lastAckPrefix = elems.take(4).mkString(",")
      
      if serial.isEmpty then
        throw new RuntimeException("Пустой serial GTLT")
      
      serial
    }.mapError(e => ProtocolError.ParseError(s"GTLT IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из пакета GTLT
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val message = readFrame(buffer)
      
      if message.isEmpty then
        throw new RuntimeException("Пустой фрейм GTLT")
      
      val elems = message.split(",").map(_.trim).toSeq
      
      if elems.length < 12 then
        throw new RuntimeException(s"Недостаточно полей для GPS данных GTLT: ${elems.length}")
      
      // Сохраняем для ACK
      lastAckPrefix = elems.take(4).mkString(",")
      
      val serial = elems(1)
      val command = elems(2)
      val timeStr = elems(3)
      val validity = elems(4)
      val latStr = elems(5)
      val northSouth = elems(6)
      val lonStr = elems(7)
      val eastWest = elems(8)
      val speedStr = elems(9)
      val directionStr = elems(10)
      val dateStr = elems(11)
      
      // Парсим NMEA координаты
      var lat = parseNmeaCoord(latStr, 2)
      var lon = parseNmeaCoord(lonStr, 3)
      
      // Применяем знак (исправляем баг legacy!)
      if northSouth == "S" then lat = -lat
      if eastWest == "W" then lon = -lon
      
      // Скорость и курс
      val speed = speedStr.toDoubleOption.getOrElse(0.0).toInt
      val direction = directionStr.toDoubleOption.getOrElse(0.0).toInt
      
      // Дата и время
      val date = LocalDate.parse(dateStr, dateFormat).atStartOfDay(ZoneId.of("UTC"))
      val time = LocalTime.parse(timeStr, timeFormat)
      val dateTime = date.`with`(time)
      val timestamp = dateTime.toInstant.toEpochMilli
      
      if lat == 0.0 && lon == 0.0 then
        throw new RuntimeException("GTLT: нулевые координаты (нет GPS fix)")
      
      List(GpsRawPoint(
        imei = imei,
        latitude = lat,
        longitude = lon,
        altitude = 0, // GTLT не передаёт высоту
        speed = speed,
        angle = direction,
        satellites = 0, // GTLT не передаёт кол-во спутников
        timestamp = timestamp
      ))
    }.mapError(e => ProtocolError.ParseError(s"GTLT data: ${e.getMessage}"))

  /**
   * Парсит NMEA координату: DDMM.MMMM → decimal degrees
   */
  private def parseNmeaCoord(str: String, degDigits: Int): Double =
    if str.isEmpty then 0.0
    else
      val deg = str.take(degDigits).toDouble
      val min = str.drop(degDigits).toDouble
      deg + min / 60.0

  /**
   * Читает текстовый фрейм (до '#')
   */
  private def readFrame(buffer: ByteBuf): String =
    val bytes = new Array[Byte](buffer.readableBytes())
    buffer.readBytes(bytes)
    new String(bytes, "ASCII").trim.stripSuffix("#")

  /**
   * ACK для GTLT — echo первых 4 полей + "#"
   * Пример: *HQ,serial,V1,083012#
   */
  override def ack(recordCount: Int): ByteBuf =
    val response = lastAckPrefix + "#"
    Unpooled.wrappedBuffer(response.getBytes("ASCII"))

  /**
   * IMEI ACK для GTLT — тот же формат
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    ack(0)

  /**
   * GTLT — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("gtlt").encode(command)
