package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для ConcoxParser (бинарный протокол, Big Endian, GT06 framing)
 *
 * Формат IMEI: [0x78][0x78][Length][Protocol=0x01][IMEI 8B BCD][...][CRC 2B][0x0D][0x0A]
 * Формат данных: [0x78][0x78][Length][Protocol=0x12][DateTime 6B][Sats 1B][Lat 4B][Lon 4B]
 *               [Speed 1B][CourseFlags 2B][LBS 8B][Serial 2B][CRC 2B][0x0D][0x0A]
 * Координаты: uint32 / 30000 / 60 (специфика Concox)
 * Altitude: всегда 0 (не передаётся)
 */
object ConcoxParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = ConcoxParser

  // Для IMEI: 352093082745395 в BCD
  // BCD encoding: "352093082745395" → дополняем до 16 символов → 0352093082745395
  // → bytes: 03 52 09 30 82 74 53 95
  val imeiBcd: Array[Byte] = Array(0x03, 0x52, 0x09, 0x30, 0x82, 0x74, 0x53, 0x95).map(_.toByte)

  // Координаты lat=55.753215, lon=37.621356 в формате Concox:
  // lat = 55.753215 * 60 * 30000 = 55.753215 * 1800000 = 100355787
  // lon = 37.621356 * 60 * 30000 = 37.621356 * 1800000 = 67718441
  val latEncoded: Int = (55.753215 * 1800000).toInt  // ≈ 100355787
  val lonEncoded: Int = (37.621356 * 1800000).toInt   // ≈ 67718441

  def spec = suite("ConcoxParser")(

    suite("parseImei")(
      test("парсит IMEI из логин-пакета (Protocol 0x01)") {
        val buffer = Unpooled.buffer()
        // Frame header
        buffer.writeByte(0x78)
        buffer.writeByte(0x78)
        // Length: 1(protocol) + 8(IMEI BCD) + 2(serial) + 2(CRC)
        buffer.writeByte(13)
        // Protocol Number: 0x01 = Login
        buffer.writeByte(0x01)
        // IMEI (8B BCD)
        buffer.writeBytes(imeiBcd)
        // Remaining bytes (serial etc, до заполнения length)
        buffer.writeShort(0x0001) // serial
        // CRC (2B) — X25
        buffer.writeShort(0x0000) // заглушка
        // Frame end
        buffer.writeByte(0x0D)
        buffer.writeByte(0x0A)

        for
          result <- parser.parseImei(buffer)
        yield
          // BCD "03 52 09 30 82 74 53 95" → hex string "0352093082745395"
          // dropWhile('0') → "352093082745395"
          assertTrue(result == "352093082745395")
      }
    ),

    suite("parseData")(
      test("парсит Location пакет (Protocol 0x12)") {
        val buffer = Unpooled.buffer()
        // Frame header
        buffer.writeByte(0x78)
        buffer.writeByte(0x78)
        // Length: 1 + 6 + 1 + 4 + 4 + 1 + 2 + 8 + 2 + 2 = 31
        buffer.writeByte(31)
        // Protocol Number: 0x12 = Location
        buffer.writeByte(0x12)

        // DateTime (6B): Year(+2000), Month, Day, Hour, Min, Sec
        buffer.writeByte(26)   // 2026
        buffer.writeByte(3)    // March
        buffer.writeByte(1)    // 1st
        buffer.writeByte(12)   // 12:00
        buffer.writeByte(0)    // :00
        buffer.writeByte(0)    // :00

        // Satellites (1B)
        buffer.writeByte(12)

        // Latitude (4B uint32 BE)
        buffer.writeInt(latEncoded)
        // Longitude (4B uint32 BE)
        buffer.writeInt(lonEncoded)

        // Speed (1B, km/h)
        buffer.writeByte(60)

        // Course + Flags (2B): lower 10 bits = course
        buffer.writeShort(180) // course = 180°

        // LBS Data (8B)
        buffer.writeBytes(new Array[Byte](8))

        // Serial (2B)
        buffer.writeShort(0x0001)

        // CRC (2B)
        buffer.writeShort(0x0000) // заглушка

        // Frame end
        buffer.writeByte(0x0D)
        buffer.writeByte(0x0A)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352093082745395") &&
          // Обратная конвертация: latEncoded / 30000 / 60
          assertTrue(scala.math.abs(points.head.latitude - 55.753) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621) < 0.01) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 0) && // Concox не передаёт высоту
          assertTrue(points.head.satellites == 12)
      }
    ),

    suite("ack")(
      test("формирует ответ (10 байт) с фреймом 0x78 0x78") {
        val ack = parser.imeiAck(true)
        // [0x78][0x78][0x05][protocolNum][serial 2B][CRC 2B][0x0D][0x0A]
        assertTrue(ack.readableBytes() == 10) &&
        assertTrue(ack.readByte() == 0x78.toByte) &&
        assertTrue(ack.readByte() == 0x78.toByte) &&
        assertTrue(ack.readByte() == 0x05.toByte)
      }
    )
  )
