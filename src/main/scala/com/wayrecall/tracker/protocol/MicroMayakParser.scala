package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.ByteOrder

/**
 * Парсер протокола MicroMayak (Автофон МикроМаяк)
 * 
 * Бинарный протокол с маркерами начала/конца, CRC8, и упакованными GPS координатами.
 * Трекеры: Autofon MicroMayak SE, SE+, LITE и другие модификации.
 * 
 * Структура пакета:
 *   [0x24 START][marker][kvitok][body...][CRC8][0x0D END]
 * 
 * Типы пакетов (marker):
 *   0x02 — Аутентификация (AUTH_KEY): body = 8 байт BCD IMEI
 *   0x09 — GPS данные из чёрного ящика: body = 14 байт основная точка + N*5 байт дополнительных
 *   0x08 — Состояние блока: body = 14 байт, содержит timestamp
 *   0x05 — LBS (видимые соты): пропускаем (нет LBS конвертера)
 *   0x03 — Стартовая LBS точка: пропускаем
 *   0x0A — Упакованные координаты: пропускаем (0x0A не документирован полностью)
 *   0x0C — Описание hardware/software: пропускаем
 *   0xE0-0xFF — События: пропускаем
 * 
 * GPS упаковка (14 байт, LE):
 *   [timestamp uint32 LE] — unix seconds
 *   [long int64 LE] — упакованные координаты:
 *     bits(0,28)  → lat / 600000.0
 *     bits(28,28) → lon / 600000.0
 *     bits(56,8)  → speed (км/ч)
 *   [dops uint16 LE] — спутники:
 *     bits(12,4)  → GLONASS sats
 *     bits(0,4)   → GPS sats
 * 
 * Дополнительные точки (5 байт каждая):
 *   [timeShift byte] (не используется в legacy!)
 *   [shifts int32 LE] — дельты от основной точки:
 *     bits(0,12)  → lat delta / 600000.0
 *     bits(12,12) → lon delta / 600000.0
 *     bits(24,8)  → speed delta (также используется как time offset)
 * 
 * CRC8: (~fold(0, (a,b) => a+b) + 1) & 0xFF
 * 
 * ACK Auth: пакет [0x04][kvitok][timestamp_BE_4B][0x00]
 * ACK Data: пакет [0x00][(kvitok << 2) | (state & 0x03)][marker]
 * 
 * Порт в legacy STELS: 9094
 */
object MicroMayakParser extends ProtocolParser:
  
  override val protocolName: String = "micromayak"
  
  private val START_MARKER: Byte = 0x24
  private val END_MARKER: Byte = 0x0D
  private val AUTH_KEY: Byte = 0x02
  private val AUTH_CONFIRMATION: Byte = 0x04
  private val COMPLETED_CORRECTLY: Byte = 0x00
  private val COMPLETED_INCORRECTLY: Byte = 0x01
  
  // Кэш для последнего kvitok и marker (для ACK)
  @volatile private var lastKvitok: Byte = 0
  @volatile private var lastMarker: Byte = 0
  
  // ─── Вспомогательные функции для извлечения битов ───
  
  /** Извлечение bitCount бит из long начиная с позиции offset снизу */
  private def extractBitsLong(value: Long, offset: Int, bitCount: Int): Long =
    (value >> offset) & ((1L << bitCount) - 1)
  
  /** Извлечение bitCount бит из int начиная с позиции offset снизу */
  private def extractBitsInt(value: Int, offset: Int, bitCount: Int): Int =
    (value >> offset) & ((1 << bitCount) - 1)
  
  /** CRC8: сумма всех байт, инвертировать, +1, & 0xFF */
  private def crc8(data: Array[Byte]): Int =
    val sum = data.foldLeft(0.toByte)((a, b) => (a + b).toByte)
    (~sum + 1) & 0xFF
  
  /** Читаем BCD байты → hex строка → stripPrefix("0") */
  private def readBcdImei(buffer: ByteBuf, length: Int): String =
    (0 until length).map { _ =>
      f"${buffer.readUnsignedByte()}%02X"
    }.mkString.stripPrefix("0")
  
  /**
   * Парсит IMEI из пакета аутентификации MicroMayak
   * 
   * Формат: [0x24][0x02][kvitok][8 bytes BCD IMEI][CRC8][0x0D]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 13 then
        throw new RuntimeException(s"MicroMayak: слишком короткий auth пакет: ${buffer.readableBytes()} байт")
      
      // Читаем стартовый маркер
      val start = buffer.readByte()
      if start != START_MARKER then
        throw new RuntimeException(f"MicroMayak: ожидается START 0x24, получено 0x${start}%02X")
      
      // Маркер типа пакета
      val marker = buffer.readByte()
      if marker != AUTH_KEY then
        throw new RuntimeException(f"MicroMayak: ожидается AUTH 0x02, получено 0x${marker}%02X")
      
      // Kvitok (номер квитанции)
      val kvitok = buffer.readByte()
      lastKvitok = kvitok
      lastMarker = marker
      
      // 8 байт BCD IMEI
      val imei = readBcdImei(buffer, 8)
      
      // CRC8
      val crc = buffer.readByte()
      
      // Конечный маркер
      val end = buffer.readByte()
      if end != END_MARKER then
        throw new RuntimeException(f"MicroMayak: ожидается END 0x0D, получено 0x${end}%02X")
      
      if imei.isEmpty then
        throw new RuntimeException("MicroMayak: пустой IMEI")
      
      imei
    }.mapError(e => ProtocolError.ParseError(s"MicroMayak IMEI: ${e.getMessage}"))

  /**
   * Парсит GPS данные из пакета MicroMayak (marker 0x09)
   * 
   * Формат: [0x24][0x09][kvitok][14B GPS][additionalCount 1B][additional count*5B][CRC8][0x0D]
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      if buffer.readableBytes() < 5 then
        throw new RuntimeException(s"MicroMayak: слишком короткий пакет: ${buffer.readableBytes()} байт")
      
      // Стартовый маркер
      val start = buffer.readByte()
      if start != START_MARKER then
        throw new RuntimeException(f"MicroMayak: ожидается START 0x24, получено 0x${start}%02X")
      
      // Маркер типа пакета
      val marker = buffer.readByte()
      lastMarker = marker
      
      // Kvitok
      val kvitok = buffer.readByte()
      lastKvitok = kvitok
      
      val points = marker match
        case 0x09 => // GPS данные из чёрного ящика
          parseGpsPacket(buffer, imei)
        
        case 0x08 => // Состояние блока (содержит timestamp, но нет координат)
          buffer.skipBytes(14) // 14 байт data
          List.empty
        
        case 0x0C => // Описание hardware/software
          buffer.skipBytes(5) // 5 байт data
          List.empty
        
        case 0x05 => // LBS (видимые соты) — пропускаем
          // 11 байт lbsArr + 1 байт count + count * 9 байт stations
          val lbsArr = 11
          buffer.skipBytes(lbsArr)
          val count = buffer.readUnsignedByte()
          buffer.skipBytes(count * 9)
          List.empty
        
        case 0x03 => // Стартовая LBS точка — пропускаем
          buffer.skipBytes(10)
          List.empty
        
        case 0x0A => // Упакованные координаты (не полностью документировано)
          buffer.skipBytes(16 + 10) // firstBlock + coordinates1
          val addCount = buffer.readUnsignedByte()
          buffer.skipBytes(addCount * 5)
          List.empty
        
        case m if (m & 0xFF) >= 0xE0 => // События
          buffer.skipBytes(4) // timestamp 4B
          List.empty
        
        case other =>
          throw new RuntimeException(f"MicroMayak: неизвестный маркер 0x${other}%02X")
      
      // CRC8 + END_MARKER
      if buffer.readableBytes() >= 2 then
        buffer.readByte() // CRC8
        buffer.readByte() // END_MARKER
      
      points
    }.mapError(e => ProtocolError.ParseError(s"MicroMayak data: ${e.getMessage}"))

  /**
   * Парсит основную GPS точку + дополнительные дельта-точки
   * 
   * 14 байт основная точка (LE):
   *   [uint32 LE] timestamp (unix seconds)
   *   [int64 LE] packed: lat(0,28)/600000, lon(28,28)/600000, speed(56,8)
   *   [uint16 LE] dops: nglonas(12,4), ngps(0,4)
   * 
   * + 1 байт count дополнительных точек
   * + count * 5 байт дельта-точки
   */
  private def parseGpsPacket(buffer: ByteBuf, imei: String): List[GpsRawPoint] =
    // Читаем 14 байт основной GPS точки в LE порядке
    val leBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN)
    
    val unixSeconds = leBuffer.readUnsignedInt() // uint32 LE
    val timestamp = unixSeconds * 1000L
    
    val packed = leBuffer.readLong() // int64 LE — упакованные координаты
    
    val lat = extractBitsLong(packed, 0, 28).toDouble / 600000.0
    val lon = extractBitsLong(packed, 28, 28).toDouble / 600000.0
    val speed = extractBitsLong(packed, 56, 8).toInt
    
    val dops = leBuffer.readUnsignedShort() // uint16 LE — спутники
    val nglonas = extractBitsInt(dops, 12, 4)
    val ngps = extractBitsInt(dops, 0, 4)
    val satellites = nglonas + ngps
    
    // Основная точка
    val mainPoint = GpsRawPoint(
      imei = imei,
      latitude = lat,
      longitude = lon,
      altitude = 0, // MicroMayak не передаёт высоту
      speed = speed,
      angle = 0, // MicroMayak не передаёт курс
      satellites = satellites,
      timestamp = timestamp
    )
    
    // Дополнительные точки (дельты)
    val additionalCount = buffer.readUnsignedByte()
    
    if additionalCount == 0 then
      List(mainPoint)
    else
      val additionalBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN)
      val points = scala.collection.mutable.ListBuffer(mainPoint)
      var prevPoint = mainPoint
      
      (0 until additionalCount).foreach { _ =>
        val timeShift = additionalBuffer.readByte() // В legacy не используется(!), но читаем
        val shifts = additionalBuffer.readInt() // int32 LE — дельты
        
        val speedDelta = extractBitsInt(shifts, 24, 8)
        val lonDelta = extractBitsInt(shifts, 12, 12).toDouble / 600000.0
        val latDelta = extractBitsInt(shifts, 0, 12).toDouble / 600000.0
        
        // Реплицируем legacy поведение:
        // time += speedDelta * 1000 (legacy баг? timeShift не используется)
        // speed = prev.speed + speedDelta
        // coords += delta
        val deltaPoint = GpsRawPoint(
          imei = imei,
          latitude = prevPoint.latitude + latDelta,
          longitude = prevPoint.longitude + lonDelta,
          altitude = 0,
          speed = (prevPoint.speed + speedDelta),
          angle = 0,
          satellites = satellites,
          timestamp = prevPoint.timestamp + speedDelta * 1000L
        )
        points += deltaPoint
        prevPoint = deltaPoint
      }
      
      points.toList

  /**
   * ACK для GPS данных MicroMayak
   * 
   * Формат confirmation: [0x24][0x00][(kvitok << 2) | (state & 0x03)][marker][CRC8][0x0D]
   */
  override def ack(recordCount: Int): ByteBuf =
    val state = COMPLETED_CORRECTLY
    // Тело confirmation: [0x00][(kvitok << 2) | (state & 0x03)][marker]
    val body = Array[Byte](
      COMPLETED_CORRECTLY,
      ((lastKvitok << 2) | (state & 0x03)).toByte,
      lastMarker
    )
    val crc = crc8(body)
    
    val buf = Unpooled.buffer(6)
    buf.writeByte(START_MARKER)
    buf.writeBytes(body)
    buf.writeByte(crc)
    buf.writeByte(END_MARKER)
    buf

  /**
   * IMEI ACK для MicroMayak — пакет подтверждения аутентификации
   * 
   * Формат: [0x24][0x04][kvitok][timestamp_BE_4B][0x00 correct][CRC8][0x0D]
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val timestamp = (java.lang.System.currentTimeMillis() / 1000).toInt
    val status = if accepted then COMPLETED_CORRECTLY else COMPLETED_INCORRECTLY
    
    // Тело: [AUTH_CONFIRMATION][kvitok][timestamp BE 4B][status]
    val body = Unpooled.buffer(7)
    body.writeByte(AUTH_CONFIRMATION)
    body.writeByte(lastKvitok)
    body.writeInt(timestamp) // BE по умолчанию
    body.writeByte(status)
    
    // Считаем CRC8 от тела
    val bodyBytes = new Array[Byte](7)
    body.readBytes(bodyBytes)
    body.release()
    val crc = crc8(bodyBytes)
    
    // Собираем финальный пакет
    val buf = Unpooled.buffer(10)
    buf.writeByte(START_MARKER)
    buf.writeBytes(bodyBytes)
    buf.writeByte(crc)
    buf.writeByte(END_MARKER)
    buf

  /**
   * MicroMayak — receive-only, делегирует в CommandEncoder
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    com.wayrecall.tracker.command.CommandEncoder.forProtocol("micro-mayak").encode(command)
