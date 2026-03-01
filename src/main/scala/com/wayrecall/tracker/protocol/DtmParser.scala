package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола ДТМ (DTM — Девайс Телематик Модуль)
 * 
 * Бинарный протокол с фиксированной структурой пакета.
 * Трекеры: ДТМ, DTM-302, DTM-310 и аналоги.
 * 
 * Формат IMEI-пакета (аутентификация):
 *   [Header 1B = 0x7B ('{')][MsgType 1B = 0x01][Length 2B BE]
 *   [IMEI 15B ASCII][DeviceType 1B][FirmwareVer 2B][CRC 1B]
 * 
 * Формат пакета данных:
 *   [Header 1B = 0x7B][MsgType 1B = 0x02][Length 2B BE]
 *   [RecordCount 1B]
 *   N × Record (26 bytes):
 *     [Timestamp 4B uint32 unix sec]
 *     [FixFlags 1B: bit0=valid, bit1=moving, bit2=alarm]
 *     [Lat 4B int32 × 10^7][Lon 4B int32 × 10^7]
 *     [Alt 2B int16 meters]
 *     [Speed 2B uint16 km/h × 10]
 *     [Course 2B uint16 degrees × 10]
 *     [Satellites 1B uint8]
 *     [HDOP 1B uint8 × 10]
 *     [Mileage 4B uint32 meters]
 *     [DigitalIO 1B]
 *   [CRC 1B]
 * 
 * Координаты: int32 × 10^-7 (7 знаков, как у Ruptela)
 * Скорость: uint16 × 0.1 km/h (2 байта, для точности)
 * Курс: uint16 × 0.1 degrees
 * CRC: XOR всех байтов от Header до последнего байта данных
 * 
 * Порт в legacy STELS: 9082
 */
object DtmParser extends ProtocolParser:
  
  override val protocolName: String = "dtm"
  
  private val HEADER_BYTE: Int = 0x7B     // '{'
  private val MSG_AUTH: Byte = 0x01
  private val MSG_DATA: Byte = 0x02
  private val MSG_ACK: Byte = 0x03
  private val MSG_ALARM: Byte = 0x04
  private val COORD_DIVISOR = 10_000_000.0
  private val SPEED_DIVISOR = 10.0
  private val COURSE_DIVISOR = 10.0
  private val RECORD_SIZE = 26             // Размер одной записи в байтах
  
  /**
   * Парсит IMEI из пакета аутентификации DTM
   * [0x7B][MsgType=0x01][Len 2B BE][IMEI 15B][DevType 1B][FwVer 2B][CRC 1B]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 4 then
        throw new RuntimeException("Недостаточно данных для заголовка DTM")
      
      val header = buffer.readUnsignedByte()
      if header != HEADER_BYTE then
        throw new RuntimeException(s"Неверный заголовок DTM: 0x${header.toInt.toHexString}, ожидается 0x7B ('{')")
      
      val msgType = buffer.readByte()
      val length = buffer.readUnsignedShort()
      
      if msgType != MSG_AUTH then
        throw new RuntimeException(s"Ожидается AUTH (0x01), получен: 0x${(msgType & 0xFF).toHexString}")
      
      if buffer.readableBytes() < 19 then // 15 IMEI + 1 DevType + 2 FwVer + 1 CRC
        throw new RuntimeException(s"Недостаточно данных для IMEI: нужно 19, доступно ${buffer.readableBytes()}")
      
      // IMEI (15 bytes ASCII)
      val imeiBytes = new Array[Byte](15)
      buffer.readBytes(imeiBytes)
      val imei = new String(imeiBytes, "ASCII").trim
      
      // Device type (1 byte) — для логирования
      val _deviceType = buffer.readUnsignedByte()
      
      // Firmware version (2 bytes)
      val _firmwareVersion = buffer.readUnsignedShort()
      
      // CRC (1 byte) — пропускаем
      buffer.skipBytes(1)
      
      if !imei.forall(_.isDigit) || imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI DTM: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"DTM IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из пакета DTM
   * [0x7B][MsgType=0x02][Len 2B][RecordCount 1B][Records...][CRC 1B]
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для заголовка пакета данных DTM")
      
      val header = buffer.readUnsignedByte()
      if header != HEADER_BYTE then
        throw new RuntimeException(s"Неверный заголовок: 0x${header.toInt.toHexString}")
      
      val msgType = buffer.readByte()
      val length = buffer.readUnsignedShort()
      
      if msgType == MSG_ALARM then
        // Alarm пакет — структура как данные, но с дополнительными полями
        // Обрабатываем как обычные данные
        () // продолжаем
      else if msgType != MSG_DATA then
        throw new RuntimeException(s"Ожидается DATA (0x02) или ALARM (0x04), получен: 0x${(msgType & 0xFF).toHexString}")
      
      if buffer.readableBytes() < 1 then
        throw new RuntimeException("Нет данных о количестве записей")
      
      val recordCount = buffer.readUnsignedByte()
      val points = scala.collection.mutable.ListBuffer[GpsRawPoint]()
      
      for i <- 0 until recordCount if buffer.readableBytes() >= RECORD_SIZE do
        // Timestamp (uint32 unix seconds)
        val timestamp = buffer.readUnsignedInt() * 1000L
        
        // Fix flags (1 byte): bit0=valid GPS, bit1=moving, bit2=alarm
        val fixFlags = buffer.readUnsignedByte()
        val isValidGps = (fixFlags & 0x01) != 0
        
        // Координаты (int32 × 10^-7)
        val latRaw = buffer.readInt()
        val lonRaw = buffer.readInt()
        val latitude = latRaw / COORD_DIVISOR
        val longitude = lonRaw / COORD_DIVISOR
        
        // Высота (int16)
        val altitude = buffer.readShort().toInt
        
        // Скорость (uint16 × 0.1 km/h)
        val speedRaw = buffer.readUnsignedShort()
        val speed = (speedRaw / SPEED_DIVISOR).toInt
        
        // Курс (uint16 × 0.1 degrees)
        val courseRaw = buffer.readUnsignedShort()
        val course = (courseRaw / COURSE_DIVISOR).toInt
        
        // Спутники (uint8)
        val satellites = buffer.readUnsignedByte()
        
        // HDOP (uint8 × 0.1) — пропускаем
        val _hdop = buffer.readUnsignedByte()
        
        // Пробег (uint32 meters) — пропускаем
        val _mileage = buffer.readUnsignedInt()
        
        // Digital IO (1 byte) — пропускаем
        val _digitalIO = buffer.readUnsignedByte()
        
        // Добавляем только валидные точки с GPS fix
        if isValidGps && (latitude != 0.0 || longitude != 0.0) then
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
      
      // CRC (1 byte) — пропускаем
      if buffer.readableBytes() >= 1 then buffer.skipBytes(1)
      
      if points.isEmpty then
        throw new RuntimeException(s"DTM: $recordCount записей, но валидных GPS точек нет")
      
      points.toList
    }.mapError(e => ProtocolError.ParseError(s"DTM data: ${e.getMessage}"))
  
  /**
   * ACK для DTM
   * [0x7B][MsgType=0x03][Len=0x0001][RecordCount]
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(5)
    buf.writeByte(HEADER_BYTE)
    buf.writeByte(MSG_ACK)
    buf.writeShort(1)
    buf.writeByte(recordCount & 0xFF)
    buf
  
  /**
   * IMEI ACK для DTM
   * [0x7B][MsgType=0x03][Len=0x0002][Status: 0x01=ok, 0x00=reject][Padding]
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buf = Unpooled.buffer(5)
    buf.writeByte(HEADER_BYTE)
    buf.writeByte(MSG_ACK)
    buf.writeShort(1)
    buf.writeByte(if accepted then 0x01 else 0x00)
    buf
  
  /**
   * Кодирование команд для DTM
   * DTM поддерживает команды — делегирует в DtmEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.DtmEncoder.encode(command)
