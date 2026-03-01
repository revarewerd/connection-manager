package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.time.{LocalDate, LocalTime, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * Парсер протокола Arnavi (Навигатор)
 * 
 * Текстовый CSV протокол, разделитель — запятая. Фреймы разделяются \r\n или \0.
 * Трекеры: Arnavi 4, Arnavi Mini, Arnavi GL.
 * Поддерживаемые версии: V2 и V3 (отличаются количеством полей).
 * 
 * Формат V2:
 *   $AV,V2,trackerID,Serial,VIN,VBAT,FSDATA,ISSTOP,ISIGNITION,D_STATE,
 *   FREQ1,COUNT1,FIX_TYPE,SAT_COUNT,TIME(HHmmss),XCOORD,YCOORD,
 *   SPEED,COURSE,DATE(ddMMyy),checksum
 * 
 * Формат V3: (добавлены FREQ2,COUNT2,ADC1,COUNTER3,TS_TEMP)
 *   $AV,V3,trackerID,Serial,VIN,VBAT,FSDATA,ISSTOP,ISIGNITION,D_STATE,
 *   FREQ1,COUNT1,FREQ2,COUNT2,FIX_TYPE,SAT_COUNT,TIME,XCOORD,YCOORD,
 *   SPEED,COURSE,DATE,ADC1,COUNTER3,TS_TEMP,checksum
 * 
 * IMEI: trackerID + "-" + Serial (составной)
 * 
 * Координаты: NMEA DDDMM.MMMM формат (100*deg + min) с суффиксом E/N
 *   coord: stripSuffix("E"|"N"), deg = int(d/100), min = d - deg*100, result = deg + min/60
 * 
 * ВАЖНО: XCOORDcontains LONGITUDE, YCOORD contains LATITUDE (не перепутаны)
 *   В legacy код: lon = coord(XCOORD), lat = coord(YCOORD) — X → lon, Y → lat
 * 
 * ACK: "RCPTOK\r\n" (ASCII)
 * 
 * Порт в legacy STELS: 9091
 */
object ArnaviParser extends ProtocolParser:
  
  override val protocolName: String = "arnavi"
  
  private val dateFormat = DateTimeFormatter.ofPattern("ddMMyy")
  private val timeFormat = DateTimeFormatter.ofPattern("HHmmss")
  
  // Кэш IMEI для передачи между parseImei и parseData
  @volatile private var cachedImei: String = ""
  
  /**
   * Парсит IMEI из первого CSV-пакета Arnavi
   * IMEI = trackerID-Serial (поля 2 и 3)
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val message = readFrame(buffer)
      val elems = message.split(",").map(_.trim).toSeq
      
      if elems.isEmpty || elems.head != "$AV" then
        throw new RuntimeException(s"Ожидается $$AV, получено: ${elems.headOption.getOrElse("пусто")}")
      
      if elems.length < 4 then
        throw new RuntimeException(s"Недостаточно полей для IMEI: ${elems.length}")
      
      val version = elems(1) // V2 или V3
      if version != "V2" && version != "V3" then
        throw new RuntimeException(s"Неподдерживаемая версия Arnavi: $version")
      
      val trackerID = elems(2)
      val serial = elems(3)
      val imei = s"$trackerID-$serial"
      cachedImei = imei
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"Arnavi IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из CSV-пакета Arnavi (V2 или V3)
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val message = readFrame(buffer)
      val elems = message.split(",").map(_.trim).toSeq
      
      if elems.isEmpty || elems.head != "$AV" then
        throw new RuntimeException(s"Ожидается $$AV, получено: ${elems.headOption.getOrElse("пусто")}")
      
      val version = elems(1)
      
      val point = version match
        case "V2" => parseV2(elems, imei)
        case "V3" => parseV3(elems, imei)
        case other => throw new RuntimeException(s"Неизвестная версия Arnavi: $other")
      
      List(point)
    }.mapError(e => ProtocolError.ParseError(s"Arnavi data: ${e.getMessage}"))

  /**
   * Парсит V2 формат Arnavi
   * Поля: $AV,V2,trackerID(2),Serial(3),VIN(4),VBAT(5),FSDATA(6),
   *   ISSTOP(7),ISIGNITION(8),D_STATE(9),FREQ1(10),COUNT1(11),
   *   FIX_TYPE(12),SAT_COUNT(13),TIME(14),XCOORD(15),YCOORD(16),
   *   SPEED(17),COURSE(18),DATE(19),checksum(20)
   */
  private def parseV2(elems: Seq[String], imei: String): GpsRawPoint =
    if elems.length < 20 then
      throw new RuntimeException(s"Arnavi V2: недостаточно полей (${elems.length}, нужно 20+)")
    
    val satCount = elems(13).toInt
    val timeStr = elems(14)
    val xcoord = elems(15)  // LONGITUDE (X → lon)
    val ycoord = elems(16)  // LATITUDE  (Y → lat)
    val speedStr = elems(17)
    val courseStr = elems(18)
    val dateStr = elems(19)
    
    buildPoint(imei, xcoord, ycoord, speedStr, courseStr, timeStr, dateStr, satCount)

  /**
   * Парсит V3 формат Arnavi (дополнительные поля FREQ2, COUNT2, ADC1, COUNTER3, TS_TEMP)
   * Поля: $AV,V3,trackerID(2),Serial(3),VIN(4),VBAT(5),FSDATA(6),
   *   ISSTOP(7),ISIGNITION(8),D_STATE(9),FREQ1(10),COUNT1(11),
   *   FREQ2(12),COUNT2(13),FIX_TYPE(14),SAT_COUNT(15),TIME(16),
   *   XCOORD(17),YCOORD(18),SPEED(19),COURSE(20),DATE(21),
   *   ADC1(22),COUNTER3(23),TS_TEMP(24),checksum(25)
   */
  private def parseV3(elems: Seq[String], imei: String): GpsRawPoint =
    if elems.length < 22 then
      throw new RuntimeException(s"Arnavi V3: недостаточно полей (${elems.length}, нужно 22+)")
    
    val satCount = elems(15).toInt
    val timeStr = elems(16)
    val xcoord = elems(17)
    val ycoord = elems(18)
    val speedStr = elems(19)
    val courseStr = elems(20)
    val dateStr = elems(21)
    
    buildPoint(imei, xcoord, ycoord, speedStr, courseStr, timeStr, dateStr, satCount)

  /**
   * Собирает GpsRawPoint из распарсенных строковых полей
   */
  private def buildPoint(
    imei: String, 
    xcoord: String, ycoord: String, 
    speedStr: String, courseStr: String,
    timeStr: String, dateStr: String,
    satellites: Int
  ): GpsRawPoint =
    // X → longitude, Y → latitude (как в legacy, не перепутано!)
    val lon = parseNmeaCoord(xcoord)
    val lat = parseNmeaCoord(ycoord)
    
    val speed = speedStr.toDouble.toInt
    val course = courseStr.toDouble.toInt
    
    val date = LocalDate.parse(dateStr, dateFormat).atStartOfDay(ZoneId.of("UTC"))
    val time = LocalTime.parse(timeStr, timeFormat)
    val dateTime = date.`with`(time)
    val timestamp = dateTime.toInstant.toEpochMilli
    
    if lat == 0.0 && lon == 0.0 then
      throw new RuntimeException("Arnavi: нулевые координаты (нет GPS fix)")
    
    GpsRawPoint(
      imei = imei,
      latitude = lat,
      longitude = lon,
      altitude = 0,
      speed = speed,
      angle = course,
      satellites = satellites,
      timestamp = timestamp
    )

  /**
   * Парсит NMEA координату: DDDMM.MMMM[E/N] → decimal degrees
   * 
   * Формат Arnavi: число вида 5545.1234E (или N для широты)
   * deg = целая часть / 100 → 55
   * min = остаток → 45.1234
   * result = 55 + 45.1234/60 = 55.7520
   */
  private def parseNmeaCoord(value: String): Double =
    val cleaned = value.replaceAll("[EN]$", "")
    val d = cleaned.toDouble
    val deg = (d.toInt / 100)
    val min = d - deg * 100
    deg + min / 60.0

  /**
   * Читает текстовый фрейм
   */
  private def readFrame(buffer: ByteBuf): String =
    val bytes = new Array[Byte](buffer.readableBytes())
    buffer.readBytes(bytes)
    new String(bytes, "ASCII").trim

  /**
   * ACK для Arnavi — "RCPTOK\r\n"
   */
  override def ack(recordCount: Int): ByteBuf =
    Unpooled.wrappedBuffer("RCPTOK\r\n".getBytes("ASCII"))

  /**
   * IMEI ACK для Arnavi — тот же формат
   * Arnavi не различает Login и Data ACK
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    ack(0)

  /**
   * Arnavi — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("arnavi").encode(command)
