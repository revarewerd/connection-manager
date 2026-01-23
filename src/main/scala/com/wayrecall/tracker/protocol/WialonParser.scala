package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.charset.StandardCharsets

/**
 * Парсер протокола Wialon IPS
 * 
 * Текстовый протокол через TCP:
 * - Login:  #L#imei;password\r\n
 * - Data:   #D#date;time;lat;N/S;lon;E/W;speed;course;alt;sats\r\n
 * - Answer: #AL#1\r\n (accepted) or #AL#0\r\n (rejected)
 * 
 * Формат координат: DDMM.MMMM (нужно конвертировать в decimal degrees)
 */
object WialonParser extends ProtocolParser:
  
  private val LOGIN_PREFIX = "#L#"
  private val DATA_PREFIX = "#D#"
  private val PING_PREFIX = "#P#"
  private val SHORT_DATA_PREFIX = "#SD#"
  
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val content = readLine(buffer)
      
      if content.startsWith(LOGIN_PREFIX) then
        // #L#imei;password\r\n
        val parts = content.drop(LOGIN_PREFIX.length).split(";")
        if parts.nonEmpty then parts(0).trim
        else throw new RuntimeException("Empty IMEI in login packet")
      else
        throw new RuntimeException(s"Expected login packet (#L#), got: ${content.take(10)}")
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Wialon IMEI: ${e.getMessage}"))
  
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val content = readLine(buffer)
      
      content match
        case s if s.startsWith(DATA_PREFIX) =>
          parseDataPacket(s.drop(DATA_PREFIX.length), imei) :: Nil
          
        case s if s.startsWith(SHORT_DATA_PREFIX) =>
          parseShortDataPacket(s.drop(SHORT_DATA_PREFIX.length), imei) :: Nil
          
        case s if s.startsWith(PING_PREFIX) =>
          // Ping packet - no data
          Nil
          
        case s =>
          throw new RuntimeException(s"Unknown packet type: ${s.take(10)}")
    }.mapError(e => ProtocolError.ParseError(s"Failed to parse Wialon data: ${e.getMessage}"))
  
  /**
   * Парсинг полного пакета данных #D#
   * Format: date;time;lat;N/S;lon;E/W;speed;course;alt;sats
   * Example: 030124;135545;5544.6025;N;03739.6834;E;100;15;10;4
   */
  private def parseDataPacket(data: String, imei: String): GpsRawPoint =
    val parts = data.split(";")
    if parts.length < 10 then
      throw new RuntimeException(s"Invalid data packet, expected 10 fields, got ${parts.length}")
    
    val date = parts(0)      // DDMMYY
    val time = parts(1)      // HHMMSS
    val latRaw = parts(2)    // DDMM.MMMM
    val latDir = parts(3)    // N/S
    val lonRaw = parts(4)    // DDDMM.MMMM
    val lonDir = parts(5)    // E/W
    val speed = parts(6)     // km/h
    val course = parts(7)    // degrees
    val altitude = parts(8)  // meters
    val satellites = parts(9) // count
    
    val latitude = parseCoordinate(latRaw, latDir == "S")
    val longitude = parseCoordinate(lonRaw, lonDir == "W")
    val timestamp = parseTimestamp(date, time)
    
    GpsRawPoint(
      imei = imei,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude.toDoubleOption.map(_.toInt).getOrElse(0),
      speed = speed.toDoubleOption.map(_.toInt).getOrElse(0),
      angle = course.toDoubleOption.map(_.toInt).getOrElse(0),
      satellites = satellites.toIntOption.getOrElse(0),
      timestamp = timestamp
    )
  
  /**
   * Парсинг сокращенного пакета #SD#
   * Format: date;time;lat;N/S;lon;E/W;speed;course;alt;sats
   */
  private def parseShortDataPacket(data: String, imei: String): GpsRawPoint =
    parseDataPacket(data, imei)
  
  /**
   * Конвертирует DDMM.MMMM в decimal degrees
   * Example: 5544.6025 → 55 + 44.6025/60 = 55.7434
   */
  private def parseCoordinate(raw: String, negative: Boolean): Double =
    val value = raw.toDoubleOption.getOrElse(0.0)
    val degrees = (value / 100).toInt
    val minutes = value - degrees * 100
    val result = degrees + minutes / 60.0
    if negative then -result else result
  
  /**
   * Парсит дату и время в Unix timestamp (миллисекунды)
   * date: DDMMYY, time: HHMMSS
   */
  private def parseTimestamp(date: String, time: String): Long =
    try
      val day = date.take(2).toInt
      val month = date.slice(2, 4).toInt
      val year = 2000 + date.drop(4).toInt
      
      val hour = time.take(2).toInt
      val minute = time.slice(2, 4).toInt
      val second = time.drop(4).toInt
      
      java.time.LocalDateTime.of(year, month, day, hour, minute, second)
        .atZone(java.time.ZoneOffset.UTC)
        .toInstant
        .toEpochMilli
    catch
      case _: Exception => java.lang.System.currentTimeMillis()
  
  /**
   * Читает строку из буфера до \r\n
   */
  private def readLine(buffer: ByteBuf): String =
    val bytes = new Array[Byte](buffer.readableBytes())
    buffer.readBytes(bytes)
    val str = new String(bytes, StandardCharsets.UTF_8)
    str.stripSuffix("\r\n").stripSuffix("\n")
  
  /**
   * ACK для данных - подтверждение приема
   */
  override def ack(recordCount: Int): ByteBuf =
    // Wialon: #AD#N\r\n где N - количество принятых записей, или -1 при ошибке
    val response = if recordCount >= 0 then s"#AD#$recordCount\r\n" else "#AD#-1\r\n"
    Unpooled.copiedBuffer(response, StandardCharsets.UTF_8)
  
  /**
   * ACK для логина
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val response = if accepted then "#AL#1\r\n" else "#AL#0\r\n"
    Unpooled.copiedBuffer(response, StandardCharsets.UTF_8)
  
  /**
   * Кодирование команды для отправки на трекер
   * Wialon использует текстовые команды
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      import com.wayrecall.tracker.domain.*
      
      val cmdText = command match
        case _: RebootCommand => 
          "restart"
        case SetIntervalCommand(_, _, _, interval) => 
          s"setinterval:$interval"
        case _: RequestPositionCommand => 
          "getposition"
        case SetOutputCommand(_, _, _, idx, enabled) =>
          s"setoutput:$idx:${if enabled then 1 else 0}"
        case CustomCommand(_, _, _, text) =>
          text
      
      // Wialon command format: #M#message\r\n
      val response = s"#M#$cmdText\r\n"
      Unpooled.copiedBuffer(response, StandardCharsets.UTF_8)
    }.mapError(e => ProtocolError.ParseError(s"Failed to encode Wialon command: ${e.getMessage}"))
