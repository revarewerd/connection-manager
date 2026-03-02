package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для GalileoskyParser (бинарный протокол, Little Endian, tag-based)
 *
 * Формат IMEI: [0x01][Size 2B LE][tags...][CRC 2B LE]
 * Tag 0x03 = IMEI (15B ASCII)
 * Tag 0x20 = DateTime (4B LE, unix sec)
 * Tag 0x30 = Coords (9B: 1B flags+sats, 4B lat, 4B lon) × 10^6
 * Tag 0x33 = Speed+Course (4B: 2B speed × 10, 2B course × 10)
 * Tag 0x34 = Altitude (2B LE signed)
 * ACK: [0x02][lastCrc 2B LE]
 */
object GalileoskyParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = GalileoskyParser

  val testImei = "352093082745395"
  val latEncoded: Int = (55.753215 * 1000000).toInt    // 55753215
  val lonEncoded: Int = (37.621356 * 1000000).toInt     // 37621356
  val timestampSec: Long = 1772280000L

  private def writeShortLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)

  private def writeIntLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  def spec = suite("GalileoskyParser")(

    suite("parseImei")(
      test("парсит IMEI из tag 0x03") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x01)             // Header
        writeShortLE(buffer, 18)           // Size LE (tag(1) + imei(15) + crc(2))

        // Tag 0x03 = IMEI
        buffer.writeByte(0x03)
        buffer.writeBytes(testImei.getBytes("ASCII"))

        // CRC (2B LE) — заглушка
        writeShortLE(buffer, 0x1234)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      }
    ),

    suite("parseData")(
      test("парсит пакет с GPS тегами (0x20, 0x30, 0x33, 0x34)") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x01)             // Header

        // Рассчитаем размер payload: tag_20(5) + tag_30(10) + tag_33(5) + tag_34(3) + crc(2) = 25
        writeShortLE(buffer, 25)

        // Tag 0x20 = DateTime (4B LE)
        buffer.writeByte(0x20)
        writeIntLE(buffer, timestampSec.toInt)

        // Tag 0x30 = Coords (9B): firstByte + lat(4B LE) + lon(4B LE)
        buffer.writeByte(0x30)
        // firstByte: upper nibble = correctness (0 = valid), lower nibble = satellites (12 = 0x0C)
        // correctness=0 in upper nibble, sats=12 in lower nibble
        buffer.writeByte(0x0C) // (0 << 4) | 12
        writeIntLE(buffer, latEncoded)
        writeIntLE(buffer, lonEncoded)

        // Tag 0x33 = Speed+Course (4B LE): speed(2B) + course(2B), both × 10
        buffer.writeByte(0x33)
        writeShortLE(buffer, 600)   // speed × 10 → 60 km/h
        writeShortLE(buffer, 1800)  // course × 10 → 180°

        // Tag 0x34 = Altitude (2B LE)
        buffer.writeByte(0x34)
        writeShortLE(buffer, 170)

        // CRC (2B LE)
        writeShortLE(buffer, 0xABCD)

        for
          points <- parser.parseData(buffer, testImei)
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == testImei) &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.001) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      },

      test("нулевые спутники → точка не сохраняется") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x01)
        writeShortLE(buffer, 25)

        // Tag 0x20
        buffer.writeByte(0x20)
        writeIntLE(buffer, timestampSec.toInt)

        // Tag 0x30: sats = 0
        buffer.writeByte(0x30)
        buffer.writeByte(0x00) // correctness=0, sats=0
        writeIntLE(buffer, latEncoded)
        writeIntLE(buffer, lonEncoded)

        // Tag 0x33
        buffer.writeByte(0x33)
        writeShortLE(buffer, 600)
        writeShortLE(buffer, 1800)

        // Tag 0x34
        buffer.writeByte(0x34)
        writeShortLE(buffer, 170)

        // CRC
        writeShortLE(buffer, 0x0000)

        for
          result <- parser.parseData(buffer, testImei).either
        yield assertTrue(result.isLeft) // sats=0 → не сохраняется → ProtocolError
      }
    ),

    suite("ack")(
      test("формирует ACK (3 байта: 0x02 + CRC echo)") {
        val ack = parser.ack(1)
        // [0x02][lastCrc 2B LE]
        assertTrue(ack.readableBytes() == 3) &&
        assertTrue(ack.readByte() == 0x02.toByte)
      }
    )
  )
