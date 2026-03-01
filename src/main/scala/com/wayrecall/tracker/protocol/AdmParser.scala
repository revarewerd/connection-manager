package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.ByteOrder
import java.util.Date

/**
 * Парсер протокола ADM 1.07 (АДМ/ADM-трекеры)
 * 
 * Бинарный LE-протокол с переменной структурой пакета (определяется битами типа).
 * Трекеры: ADM100, ADM300, ADM333, ADM700, ADM007.
 * 
 * Формат фрейма (MessageDecoder):
 *   [DeviceId 2B LE][Size 1B][Payload...] — size включает всё от DeviceId
 * 
 * Тип пакета (1 байт после Size):
 *   Биты 0-1 (typ & 0x03): 
 *     0x03 — IMEI/Auth пакет
 *     0x01/0x00 — Data пакет (GPS + датчики)
 *   Биты 2-5: наличие дополнительных блоков данных
 *     bit2: +4B (акселерометр, выходы, события)
 *     bit3: +12B (аналоговые входы)
 *     bit4: +8B (импульсные/дискретные входы)
 *     bit5: +9B (датчики уровня топлива и температуры)
 * 
 * Auth пакет (typ & 0x03 == 0x03):
 *   [IMEI 15B ASCII][HwType 1B][ReplyEnabled 1B]
 * 
 * Data пакет (typ & 0x03 == 0x01 или 0x00):
 *   [Soft 1B][GpsPntr 2B LE][Status 2B LE]
 *   [Lat float32 LE (IEEE-754)][Lon float32 LE (IEEE-754)]
 *   [Course uint16 LE × 0.1°][Speed uint16 LE × 0.1 km/h]
 *   [Acc 1B][Height uint16 LE][HDOP 1B][Satellites 1B]
 *   [Seconds uint32 LE (unix)][Power uint16 LE][Battery uint16 LE]
 *   [Optional blocks по битам typ]
 * 
 * ACK: (только если replyEnabled == 0x02)
 *   [DeviceId 2B][0x84][***1*][124 нулей] — итого 132 байта
 * 
 * Порт в legacy STELS: 9096
 */
object AdmParser extends ProtocolParser:
  
  override val protocolName: String = "adm"
  
  // Сохраняем данные сессии
  @volatile private var imei: String = ""
  @volatile private var replyEnabled: Byte = 0
  @volatile private var lastDeviceId: Int = 0
  
  /**
   * Парсит IMEI из Auth пакета ADM (typ & 0x03 == 0x03)
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
      
      if buf.readableBytes() < 3 then
        throw new RuntimeException("Недостаточно данных для заголовка ADM")
      
      val deviceId = buf.readUnsignedShort()
      val size = buf.readUnsignedByte()
      
      if buf.readableBytes() < 1 then
        throw new RuntimeException("Недостаточно данных для типа пакета ADM")
      
      val typ = buf.readByte()
      val typeBits = typ & 0x03
      
      if typeBits != 0x03 then
        throw new RuntimeException(s"Ожидается Auth (0x03), получен тип: 0x${(typ & 0xFF).toHexString} (bits=${typeBits})")
      
      if buf.readableBytes() < 17 then // 15 IMEI + 1 HwType + 1 ReplyEnabled
        throw new RuntimeException(s"Недостаточно данных для IMEI Auth: ${buf.readableBytes()}")
      
      // IMEI (15 bytes ASCII)
      val imeiBytes = new Array[Byte](15)
      buf.readBytes(imeiBytes)
      imei = new String(imeiBytes, "ASCII").trim
      
      // Hardware type
      val _hwType = buf.readByte()
      
      // Reply enabled (0x02 = нужно отвечать)
      replyEnabled = buf.readByte()
      lastDeviceId = deviceId
      
      // Пропускаем остаток пакета (если есть)
      val startSize = 3 + 1 + 15 + 1 + 1 // deviceId + size + typ + IMEI + hwType + reply
      val remaining = size - (startSize - 2) // size считается от deviceId, но deviceId уже прочитан
      if remaining > 0 && buf.readableBytes() >= remaining then
        buf.skipBytes(remaining)
      
      if !imei.forall(_.isDigit) || imei.length < 10 then
        throw new RuntimeException(s"Невалидный IMEI ADM: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"ADM IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из Data пакета ADM (typ & 0x03 == 0x01 или 0x00)
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
      val startIndex = buf.readerIndex()
      
      if buf.readableBytes() < 3 then
        throw new RuntimeException("Недостаточно данных для заголовка ADM")
      
      val deviceId = buf.readUnsignedShort()
      lastDeviceId = deviceId
      val size = buf.readUnsignedByte()
      
      if buf.readableBytes() < 1 then
        throw new RuntimeException("Недостаточно данных для типа ADM")
      
      val typ = buf.readByte()
      val typeBits = typ & 0x03
      
      if typeBits == 0x03 then
        throw new RuntimeException("ADM: получен Auth пакет вместо Data")
      
      if typeBits != 0x01 && typeBits != 0x00 then
        throw new RuntimeException(s"ADM: неизвестный тип 0x${(typ & 0xFF).toHexString}")
      
      // Soft version
      val _soft = buf.readUnsignedByte()
      
      // GPS pointer
      val _gpsPntr = buf.readUnsignedShort()
      
      // Status
      val _status = buf.readUnsignedShort()
      
      // Координаты (IEEE-754 float, LE)
      val latRaw = buf.readFloat()
      val lonRaw = buf.readFloat()
      
      // Если координаты 0.0 — значит нет GPS fix
      val lat = if latRaw == 0.0f then Double.NaN else latRaw.toDouble
      val lon = if lonRaw == 0.0f then Double.NaN else lonRaw.toDouble
      
      // Course (uint16 × 0.1 degrees)
      val course = (buf.readUnsignedShort() * 0.1).round.toInt
      
      // Speed (uint16 × 0.1 km/h)
      val speed = (buf.readUnsignedShort() * 0.1).round.toInt
      
      // Accelerometer flag
      val _acc = buf.readUnsignedByte()
      
      // Height (uint16 meters)
      val height = buf.readUnsignedShort()
      
      // HDOP
      val _hdop = buf.readUnsignedByte()
      
      // Satellites
      val satellites = buf.readUnsignedByte()
      
      // Timestamp (uint32 unix seconds, LE)
      val seconds = buf.readUnsignedInt()
      val timestamp = seconds * 1000L
      
      // Power + Battery (пропускаем)
      val _power = buf.readUnsignedShort()
      val _battery = buf.readUnsignedShort()
      
      // Optional блоки данных (определяются битами typ)
      if (typ & 0x04) != 0 && buf.readableBytes() >= 4 then buf.skipBytes(4)   // bit2: акселерометр
      if (typ & 0x08) != 0 && buf.readableBytes() >= 12 then buf.skipBytes(12) // bit3: аналоговые входы
      if (typ & 0x10) != 0 && buf.readableBytes() >= 8 then buf.skipBytes(8)   // bit4: импульсные входы
      if (typ & 0x20) != 0 && buf.readableBytes() >= 9 then buf.skipBytes(9)   // bit5: топливо + темп
      
      // Пропускаем непрочитанный остаток
      val consumed = buf.readerIndex() - startIndex
      val leftToSkip = size - consumed + 2 // +2 т.к. size считает от deviceId (2 байта перед size)
      if leftToSkip > 0 && buf.readableBytes() >= leftToSkip then
        buf.skipBytes(leftToSkip)
      
      if lat.isNaN || lon.isNaN then
        throw new RuntimeException("ADM: нет GPS fix (координаты 0.0)")
      
      List(GpsRawPoint(
        imei = imei,
        latitude = lat,
        longitude = lon,
        altitude = height,
        speed = speed,
        angle = course,
        satellites = satellites,
        timestamp = timestamp
      ))
    }.mapError(e => ProtocolError.ParseError(s"ADM data: ${e.getMessage}"))

  /**
   * ACK для ADM — отправляется только если replyEnabled == 0x02
   * [DeviceId 2B BE][0x84]["***1*"][124 нулей] = 132 байта
   * Внимание: в legacy deviceId отправляется в BE (writeShort без order)
   */
  override def ack(recordCount: Int): ByteBuf =
    if replyEnabled == 0x02 then
      val buf = Unpooled.buffer(132)
      buf.writeShort(lastDeviceId) // BE (как в legacy)
      buf.writeByte(0x84)
      buf.writeBytes("***1*".getBytes("ASCII"))
      buf.writeZero(124)
      buf
    else
      Unpooled.EMPTY_BUFFER

  /**
   * IMEI ACK для ADM — тот же формат, что и data ACK
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    Unpooled.EMPTY_BUFFER // ADM не требует ACK для Auth

  /**
   * ADM — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("adm").encode(command)
