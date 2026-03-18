package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.charset.StandardCharsets
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

/**
 * Парсер протокола GoSafe (G3S/G6S/G1S)
 * 
 * Текстовый ASCII протокол, GPRMC-подобный формат.
 * Трекеры: GoSafe G3S, G6S, G1S, G3A, S3 и аналоги.
 * 
 * Формат пакета IMEI (аутентификация):
 *   *GS[version],IMEI,[password]#
 *   Пример: *GS16,352234061001729,0000#
 * 
 * Формат пакета данных:
 *   $[IMEI],[datetime],[lat],[N/S],[lon],[E/W],[speed],[course],[altitude],[satellites],[hdop],[io_status]#
 *   Пример: $352234061001729,070623120000,55.753215,N,037.621356,E,000.0,000,170,12,1.2,000#
 * 
 * Альтернативный формат (GPRMC-like):
 *   *GS16,352234061001729,GPS:1;N55.753215;E037.621356;170;000.0;000;120000;070623#
 * 
 * Координаты: десятичные градусы (DD.DDDDDD)
 * Скорость: в узлах (knots) → конвертируем в км/ч (* 1.852)
 * Дата/время: DDMMYYHHMMSS
 * 
 * ACK: нет обязательного ACK (трекер шлёт данные непрерывно)
 * Порт в legacy STELS: 9086
 */
object GoSafeParser extends ProtocolParser:

  override val protocolName: String = "gosafe"
  
  private val KNOTS_TO_KMH = 1.852
  
  /**
   * Парсит IMEI из первого пакета GoSafe
   * 
   * Формат: *GS[ver],IMEI,[password]#
   * или    $IMEI,...#
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val bytes = new Array[Byte](buffer.readableBytes())
      buffer.readBytes(bytes)
      val line = new String(bytes, StandardCharsets.US_ASCII).trim
      
      val imei = if line.startsWith("*GS") then
        // *GS16,352234061001729,0000#
        val parts = line.stripPrefix("*").stripSuffix("#").split(",")
        if parts.length < 2 then
          throw new RuntimeException(s"Недостаточно полей в GS пакете: $line")
        parts(1).trim
      else if line.startsWith("$") then
        // $352234061001729,...#
        val parts = line.stripPrefix("$").stripSuffix("#").split(",")
        if parts.isEmpty then
          throw new RuntimeException(s"Пустой $$-пакет: $line")
        parts(0).trim
      else
        throw new RuntimeException(s"Неизвестный формат GoSafe пакета: ${line.take(20)}")
      
      // Валидация IMEI
      if !imei.forall(_.isDigit) || imei.length < 10 || imei.length > 16 then
        throw new RuntimeException(s"Невалидный IMEI GoSafe: $imei (len=${imei.length})")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"GoSafe IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из пакета GoSafe
   * 
   * Формат: $IMEI,DDMMYYHHMMSS,lat,N/S,lon,E/W,speed,course,alt,sat,hdop,io#
   * или:   *GS16,IMEI,GPS:1;N55.753;E037.621;alt;speed;course;HHMMSS;DDMMYY#
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val bytes = new Array[Byte](buffer.readableBytes())
      buffer.readBytes(bytes)
      val content = new String(bytes, StandardCharsets.US_ASCII).trim
      
      // Может содержать несколько пакетов, разделённых '#' и/или '\n'
      val packets = content.split("[#\n]+").filter(_.nonEmpty).toList
      
      packets.flatMap { packet =>
        parseOnePacket(packet.trim, imei).toOption
      }
    }.mapError(e => ProtocolError.ParseError(s"GoSafe data: ${e.getMessage}"))
      .flatMap { points =>
        if points.isEmpty then
          ZIO.fail(ProtocolError.ParseError("GoSafe: не удалось распарсить ни одну точку"))
        else
          ZIO.succeed(points)
      }
  
  /**
   * Парсит один пакет с GPS данными
   */
  private def parseOnePacket(packet: String, imei: String): Either[String, GpsRawPoint] =
    try
      if packet.contains("GPS:") then
        parseGpsFormat(packet, imei)
      else if packet.startsWith("$") then
        parseDollarFormat(packet.stripPrefix("$"), imei)
      else
        parseDollarFormat(packet, imei) // Попытка распарсить как $ формат без $
    catch
      case e: Exception => Left(e.getMessage)
  
  /**
   * Парсит формат: $IMEI,DDMMYYHHMMSS,lat,N/S,lon,E/W,speed,course,alt,sat,hdop,io
   */
  private def parseDollarFormat(data: String, imei: String): Either[String, GpsRawPoint] =
    val parts = data.split(",")
    if parts.length < 8 then
      return Left(s"Недостаточно полей в $$-формате: ${parts.length}")
    
    try
      // parts(0) = IMEI (пропускаем, уже знаем)
      val datetime = parts(1) // DDMMYYHHMMSS
      val lat = parts(2).toDouble
      val ns = if parts.length > 3 then parts(3) else "N"
      val lon = parts(4).toDouble
      val ew = if parts.length > 5 then parts(5) else "E"
      val speedKnots = if parts.length > 6 then parts(6).toDouble else 0.0
      val course = if parts.length > 7 then parts(7).toDoubleOption.getOrElse(0.0).toInt else 0
      val altitude = if parts.length > 8 then parts(8).toDoubleOption.getOrElse(0.0).toInt else 0
      val satellites = if parts.length > 9 then parts(9).toDoubleOption.getOrElse(0.0).toInt else 0
      
      val latitude = if ns == "S" then -lat else lat
      val longitude = if ew == "W" then -lon else lon
      val speedKmh = (speedKnots * KNOTS_TO_KMH).toInt
      val timestamp = parseDatetime(datetime)
      
      Right(GpsRawPoint(
        imei = imei,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speed = speedKmh,
        angle = course,
        satellites = satellites,
        timestamp = timestamp
      ))
    catch
      case e: Exception => Left(s"$$-format parse error: ${e.getMessage}")
  
  /**
   * Парсит формат: *GS16,IMEI,GPS:1;N55.753;E037.621;alt;speed;course;HHMMSS;DDMMYY
   */
  private def parseGpsFormat(data: String, imei: String): Either[String, GpsRawPoint] =
    try
      val gpsIdx = data.indexOf("GPS:")
      if gpsIdx < 0 then return Left("GPS: маркер не найден")
      
      val gpsData = data.substring(gpsIdx + 4) // после "GPS:"
      val parts = gpsData.split(";")
      if parts.length < 7 then
        return Left(s"Недостаточно полей в GPS формате: ${parts.length}")
      
      // parts(0) = fixStatus (1=valid)
      val latStr = parts(1) // N55.753215 или S55.753215
      val lonStr = parts(2) // E037.621356 или W037.621356
      val altitude = parts(3).toDoubleOption.getOrElse(0.0).toInt
      val speedKnots = parts(4).toDouble
      val course = parts(5).toDoubleOption.getOrElse(0.0).toInt
      val time = parts(6)     // HHMMSS
      val date = if parts.length > 7 then parts(7) else "" // DDMMYY
      
      val latitude = parseCoordinate(latStr)
      val longitude = parseCoordinate(lonStr)
      val speedKmh = (speedKnots * KNOTS_TO_KMH).toInt
      val timestamp = if date.nonEmpty then parseDatetime(date + time) else java.lang.System.currentTimeMillis()
      
      Right(GpsRawPoint(
        imei = imei,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speed = speedKmh,
        angle = course,
        satellites = 0,
        timestamp = timestamp
      ))
    catch
      case e: Exception => Left(s"GPS format parse error: ${e.getMessage}")
  
  /**
   * Парсит координату вида N55.753215 / S55.753215 / E037.621356 / W037.621356
   */
  private def parseCoordinate(s: String): Double =
    if s.isEmpty then 0.0
    else
      val direction = s.charAt(0)
      val value = s.substring(1).toDouble
      if direction == 'S' || direction == 'W' then -value else value
  
  /**
   * Парсит дату/время DDMMYYHHMMSS → epoch millis
   */
  private def parseDatetime(s: String): Long =
    if s.length < 12 then java.lang.System.currentTimeMillis()
    else
      try
        val day = s.substring(0, 2).toInt
        val month = s.substring(2, 4).toInt
        val year = 2000 + s.substring(4, 6).toInt
        val hour = s.substring(6, 8).toInt
        val minute = s.substring(8, 10).toInt
        val second = s.substring(10, 12).toInt
        LocalDateTime.of(year, month, day, hour, minute, second)
          .toInstant(ZoneOffset.UTC).toEpochMilli
      catch
        case _: Exception => java.lang.System.currentTimeMillis()
  
  /**
   * ACK для GoSafe — подтверждение приёма
   * GoSafe обычно не требует обязательный ACK, но ACK можно отправить
   */
  override def ack(recordCount: Int): ByteBuf =
    Unpooled.EMPTY_BUFFER
  
  /**
   * ACK для IMEI пакета GoSafe
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    if accepted then
      Unpooled.copiedBuffer("OK\r\n", StandardCharsets.US_ASCII)
    else
      Unpooled.copiedBuffer("ERR\r\n", StandardCharsets.US_ASCII)
  
  /**
   * Кодирование команд для GoSafe
   * GoSafe — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("gosafe").encode(command)
