package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола Автофон Маяк (Autophone Mayak)
 * 
 * Бинарный протокол для трекеров-маяков Автофон.
 * Трекеры: Автофон Маяк, Автофон SE-маяк+, Автофон Альфа-Маяк.
 * 
 * Формат IMEI-пакета (аутентификация):
 *   [Header 1B = 0x4D ('M')][Version 1B][Length 2B LE]
 *   [MsgType 1B = 0x01][IMEI 15B ASCII][CRC 2B]
 * 
 * Формат пакета данных:
 *   [Header 1B = 0x4D][Version 1B][Length 2B LE][MsgType 1B = 0x02]
 *   [RecordCount 1B]
 *   N × Record:
 *     [Lat 4B int32 ×10^6][Lon 4B int32 ×10^6]
 *     [Alt 2B int16][Speed 1B uint8 km/h][Course 2B uint16]
 *     [Satellites 1B][Timestamp 4B uint32 unix sec]
 *     [Battery 1B][Temperature 1B signed][GSM Signal 1B]
 *   [CRC 2B]
 * 
 * Координаты: int32 × 10^-6 (6 знаков после запятой)
 * Скорость: km/h (1 байт, 0-255)
 * CRC: CRC-16/MODBUS (little-endian)
 * 
 * Особенности:
 * - Маяки шлют данные пакетами раз в 10-60 минут (экономия батареи)
 * - Один пакет может содержать 1-20 накопленных точек
 * - Трекер засыпает между сеансами связи (deep sleep)
 * 
 * Порт в legacy STELS: 9083
 */
object AutophoneMayakParser extends ProtocolParser:
  
  override val protocolName: String = "autophone-mayak"
  
  private val HEADER_BYTE: Int = 0x4D     // 'M'
  private val MSG_AUTH: Byte = 0x01
  private val MSG_DATA: Byte = 0x02
  private val MSG_ACK: Byte = 0x03
  private val MSG_HEARTBEAT: Byte = 0x04
  private val COORD_DIVISOR = 1_000_000.0
  private val RECORD_SIZE = 20             // Размер одной записи в байтах
  
  /**
   * Парсит IMEI из пакета аутентификации Автофон
   * [0x4D][Ver 1B][Len 2B LE][MsgType=0x01][IMEI 15B][CRC 2B]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException("Недостаточно данных для заголовка AutophoneMayak")
      
      val header = buffer.readUnsignedByte()
      if header != HEADER_BYTE then
        throw new RuntimeException(s"Неверный заголовок: 0x${header.toInt.toHexString}, ожидается 0x4D ('M')")
      
      val version = buffer.readUnsignedByte() // Версия протокола
      val length = buffer.readUnsignedShortLE()
      val msgType = buffer.readByte()
      
      if msgType != MSG_AUTH then
        throw new RuntimeException(s"Ожидается AUTH (0x01), получен: 0x${(msgType & 0xFF).toHexString}")
      
      if buffer.readableBytes() < 17 then // 15 IMEI + 2 CRC
        throw new RuntimeException(s"Недостаточно данных для IMEI: нужно 17, доступно ${buffer.readableBytes()}")
      
      val imeiBytes = new Array[Byte](15)
      buffer.readBytes(imeiBytes)
      val imei = new String(imeiBytes, "ASCII").trim
      
      // CRC (2 bytes) — пропускаем
      buffer.skipBytes(2)
      
      if !imei.forall(_.isDigit) || imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI AutophoneMayak: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"AutophoneMayak IMEI: ${e.getMessage}"))
  
  /**
   * Парсит GPS данные из пакета Автофон
   * [0x4D][Ver][Len 2B LE][MsgType=0x02][RecordCount 1B][Records...][CRC 2B]
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 7 then
        throw new RuntimeException("Недостаточно данных для заголовка пакета данных")
      
      val header = buffer.readUnsignedByte()
      if header != HEADER_BYTE then
        throw new RuntimeException(s"Неверный заголовок: 0x${header.toInt.toHexString}")
      
      val version = buffer.readUnsignedByte()
      val length = buffer.readUnsignedShortLE()
      val msgType = buffer.readByte()
      
      if msgType == MSG_HEARTBEAT then
        // Heartbeat пакет — без GPS данных (маяк сообщает о живости)
        throw new RuntimeException("Heartbeat пакет — нет GPS данных")
      
      if msgType != MSG_DATA then
        throw new RuntimeException(s"Ожидается DATA (0x02), получен: 0x${(msgType & 0xFF).toHexString}")
      
      val recordCount = buffer.readUnsignedByte()
      val points = scala.collection.mutable.ListBuffer[GpsRawPoint]()
      
      for i <- 0 until recordCount if buffer.readableBytes() >= RECORD_SIZE do
        // Координаты (int32 × 10^-6)
        val latRaw = buffer.readIntLE()
        val lonRaw = buffer.readIntLE()
        val latitude = latRaw / COORD_DIVISOR
        val longitude = lonRaw / COORD_DIVISOR
        
        // Высота (int16 LE)
        val altitude = buffer.readShortLE().toInt
        
        // Скорость (uint8 km/h)
        val speed = buffer.readUnsignedByte()
        
        // Курс (uint16 LE, в градусах 0-360)
        val course = buffer.readUnsignedShortLE()
        
        // Спутники (uint8)
        val satellites = buffer.readUnsignedByte()
        
        // Timestamp (uint32 LE unix seconds)
        val timestamp = buffer.readUnsignedIntLE() * 1000L
        
        // Battery level (uint8, 0-100%)
        val _battery = buffer.readUnsignedByte()
        
        // Temperature (int8, signed, °C)
        val _temperature = buffer.readByte()
        
        // GSM signal (uint8, dBm indicator)
        val _gsmSignal = buffer.readUnsignedByte()
        
        // Валидация координат (маяк может слать 0,0 если нет GPS-fix'а)
        if latitude != 0.0 || longitude != 0.0 then
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
      
      // CRC (2 bytes) — пропускаем
      if buffer.readableBytes() >= 2 then buffer.skipBytes(2)
      
      if points.isEmpty then
        throw new RuntimeException(s"AutophoneMayak: $recordCount записей в пакете, но валидных точек нет (0,0 координаты?)")
      
      points.toList
    }.mapError(e => ProtocolError.ParseError(s"AutophoneMayak data: ${e.getMessage}"))
  
  /**
   * ACK для AutophoneMayak
   * [0x4D][Ver=0x01][Len=0x0001][MsgType=0x03]
   */
  override def ack(recordCount: Int): ByteBuf =
    val buf = Unpooled.buffer(5)
    buf.writeByte(HEADER_BYTE)
    buf.writeByte(0x01) // версия
    buf.writeShortLE(1) // length
    buf.writeByte(MSG_ACK)
    buf
  
  /**
   * IMEI ACK для AutophoneMayak
   * [0x4D][Ver=0x01][Len=0x0002][MsgType=0x03][Status: 0x01=ok, 0x00=reject]
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buf = Unpooled.buffer(6)
    buf.writeByte(HEADER_BYTE)
    buf.writeByte(0x01)
    buf.writeShortLE(2)
    buf.writeByte(MSG_ACK)
    buf.writeByte(if accepted then 0x01 else 0x00)
    buf
  
  /**
   * Кодирование команд для AutophoneMayak — ограниченная поддержка
   * AutophoneMayak — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("autophone-mayak").encode(command)
