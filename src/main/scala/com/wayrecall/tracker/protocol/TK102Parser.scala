package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.time.{LocalDate, LocalTime, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * Парсер протокола TK102/TK103 (китайские GPS-трекеры)
 * 
 * Текстовый ASCII протокол. Фрейм: ( ... )
 * Трекеры: TK102, TK102-2, TK103, TK103A/B, TK104, GPS103A/B и аналоги.
 * Протоколы TK102 и TK103 идентичны по структуре — используем один парсер.
 * 
 * Формат: (serial_12charcommand_4charbody)
 *   serial:  12 символов (числовой идентификатор устройства)
 *   command: 4 символа ("BP00"=Login, "BP05"=Heartbeat, "BR00"=Location)
 *   body:    данные координат (для BR00)
 * 
 * IMEI: вычисляется как "3528" + serial.dropWhile('0')
 * 
 * Login (BP00): ответ (serial + "AP01HSO")
 * Heartbeat (BP05): ответ (serial + "AP05HSO")
 * Location (BR00):
 *   [Date 6ch yyMMdd][Avail 1ch][Lat 9ch DDMM.MMMM][N/S 1ch]
 *   [Lon 10ch DDDMM.MMMM][E/W 1ch][Speed 5ch knots]
 *   [Time 6ch HHmmss][Course 6ch degrees]
 *   
 *   Координаты: NMEA формат DDMM.MMMM → degrees + minutes/60
 *   Скорость: узлы → остаётся как есть (трекер отправляет в km/h несмотря на формат)
 *   
 *   Ответ: (serial + "AR03")
 * 
 * ВАЖНО: в legacy N/S и E/W lettersignals читаются, но НЕ учитываются для инверсии знака.
 * Мы исправляем этот баг — применяем знак.
 * 
 * Порт в legacy STELS: TK102 = 9092, TK103 = 9095
 */
class TK102Parser(val protocolSuffix: String = "TK102") extends ProtocolParser:
  
  override val protocolName: String = "tk102"
  
  private val dateFormat = DateTimeFormatter.ofPattern("yyMMdd")
  private val timeFormat = DateTimeFormatter.ofPattern("HHmmss")
  
  // Последний serial для формирования ACK
  @volatile private var lastSerial: String = ""
  
  /**
   * Парсит IMEI из Login пакета TK102
   * (serial_12_chBP00...)
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val msg = readAsciiFrame(buffer)
      
      if msg.length < 17 then
        throw new RuntimeException(s"Слишком короткий пакет TK102: ${msg.length} символов")
      
      // Пропуск '(' (первый символ)
      val content = if msg.startsWith("(") then msg.drop(1) else msg
      val withoutEnd = if content.endsWith(")") then content.dropRight(1) else content
      
      val serial = withoutEnd.take(12)
      lastSerial = serial
      val command = withoutEnd.slice(12, 16)
      
      // IMEI = "3528" + serial без ведущих нулей
      val imei = "3528" + serial.dropWhile(_ == '0')
      
      if imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI TK102: $imei (serial=$serial)")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"$protocolSuffix IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из Location пакета (BR00)
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val msg = readAsciiFrame(buffer)
      
      val content = if msg.startsWith("(") then msg.drop(1) else msg
      val withoutEnd = if content.endsWith(")") then content.dropRight(1) else content
      
      val serial = withoutEnd.take(12)
      lastSerial = serial
      val command = withoutEnd.slice(12, 16)
      val body = withoutEnd.drop(16)
      
      command match
        case "BR00" =>
          // Парсим Location данные
          var pos = 0
          
          // Date (6 символов yyMMdd)
          val dateStr = body.substring(pos, pos + 6); pos += 6
          // Validity (1 символ — A=valid, V=invalid)
          val validity = body.charAt(pos); pos += 1
          
          // Latitude (9 символов DDMM.MMMM)
          val latStr = body.substring(pos, pos + 9); pos += 9
          val latLetter = body.charAt(pos); pos += 1
          
          // Longitude (10 символов DDDMM.MMMM)
          val lonStr = body.substring(pos, pos + 10); pos += 10
          val lonLetter = body.charAt(pos); pos += 1
          
          // Speed (5 символов — km/h)
          val speedStr = body.substring(pos, pos + 5); pos += 5
          val speed = speedStr.trim.toFloat.toInt
          
          // Time (6 символов HHmmss)
          val timeStr = body.substring(pos, pos + 6); pos += 6
          
          // Course (6 символов)
          val courseStr = body.substring(pos, Math.min(pos + 6, body.length))
          val course = courseStr.trim.toFloat.toInt
          
          // Парсим координаты из NMEA формата
          var lat = parseNmeaCoord(latStr, 2)
          var lon = parseNmeaCoord(lonStr, 3)
          
          // Применяем знак (исправляем баг legacy! В legacy N/S E/W не применялись)
          if latLetter == 'S' then lat = -lat
          if lonLetter == 'W' then lon = -lon
          
          // Парсим дату и время
          val date = LocalDate.parse(dateStr, dateFormat).atStartOfDay(ZoneId.of("UTC"))
          val time = LocalTime.parse(timeStr, timeFormat)
          val dateTime = date.`with`(time)
          val timestamp = dateTime.toInstant.toEpochMilli
          
          if lat == 0.0 && lon == 0.0 then
            throw new RuntimeException(s"$protocolSuffix: нулевые координаты (нет GPS fix)")
          
          List(GpsRawPoint(
            imei = imei,
            latitude = lat,
            longitude = lon,
            altitude = 0,
            speed = speed,
            angle = course,
            satellites = 9, // TK102 не передаёт кол-во спутников — берём default
            timestamp = timestamp
          ))
          
        case "BP00" | "BP05" =>
          // Login/Heartbeat — нет GPS данных
          throw new RuntimeException(s"$protocolSuffix: пакет $command без GPS данных (login/heartbeat)")
          
        case other =>
          throw new RuntimeException(s"$protocolSuffix: неизвестная команда $other")
          
    }.mapError(e => ProtocolError.ParseError(s"$protocolSuffix data: ${e.getMessage}"))

  /**
   * Парсит NMEA координату: DDMM.MMMM → degrees
   * @param str координата в NMEA формате
   * @param degDigits количество цифр градусов (2 для лат, 3 для лон)
   */
  private def parseNmeaCoord(str: String, degDigits: Int): Double =
    val deg = str.take(degDigits).toDouble
    val min = str.drop(degDigits).toDouble
    deg + min / 60.0

  /**
   * Читает ASCII фрейм TK102: от '(' до ')'
   */
  private def readAsciiFrame(buffer: ByteBuf): String =
    val bytes = new Array[Byte](buffer.readableBytes())
    buffer.readBytes(bytes)
    new String(bytes, "ASCII")

  /**
   * ACK для TK102 — ответ на Location
   * (serial + "AR03")
   */
  override def ack(recordCount: Int): ByteBuf =
    val response = s"(${lastSerial}AR03)"
    Unpooled.wrappedBuffer(response.getBytes("ASCII"))

  /**
   * IMEI ACK для TK102 — ответ на Login (BP00)
   * (serial + "AP01HSO")
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val response = s"(${lastSerial}AP01HSO)"
    Unpooled.wrappedBuffer(response.getBytes("ASCII"))

  /**
   * TK102/TK103 — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("tk102").encode(command)


/**
 * Companion object — фабричные методы для TK102 и TK103
 */
object TK102Parser:
  /** TK102 парсер (порт 5009) */
  val tk102: TK102Parser = new TK102Parser("TK102")
  /** TK103 парсер (порт 5010) — идентичный протокол */
  val tk103: TK102Parser = new TK102Parser("TK103")
