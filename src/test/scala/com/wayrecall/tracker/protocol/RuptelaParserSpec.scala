package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для RuptelaParser (бинарный протокол, Big Endian)
 *
 * Формат IMEI: [PacketLength 2B BE][IMEI 8B long]
 * Формат данных: [PacketLength 2B][IMEI 8B skip][CmdID 1B][records...]
 * Координаты: int32 signed × 10^7
 * Скорость: uint16 × 100 (км/ч)
 */
object RuptelaParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = RuptelaParser

  // Тестовые значения координат
  // lat = 55.753215 → int32 = (55.753215 * 10_000_000).toInt = 557532150
  // lon = 37.621356 → int32 = (37.621356 * 10_000_000).toInt = 376213560
  val latEncoded: Int = (55.753215 * 10000000).toInt   // 557532150
  val lonEncoded: Int = (37.621356 * 10000000).toInt    // 376213560
  val speedEncoded: Short = (60 * 100).toShort           // 6000 → 60 km/h после /100
  val altEncoded: Short = 170.toShort
  val angleEncoded: Short = 180.toShort
  val satsEncoded: Byte = 12.toByte
  // timestamp: 1772280000 unix seconds ( 2026-03-01 12:00:00 UTC)
  val timestampSec: Long = 1772280000L

  def spec = suite("RuptelaParser")(

    suite("parseImei")(
      test("парсит IMEI из бинарного пакета") {
        // IMEI 352093082745395 → Long → reverse padTo 15
        val imeiLong = 352093082745395L
        val buffer = Unpooled.buffer()
        buffer.writeShort(8) // packet length (IMEI only)
        buffer.writeLong(imeiLong)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("отклоняет пакет с недостаточным количеством байт") {
        val buffer = Unpooled.buffer()
        buffer.writeShort(8) // length
        buffer.writeInt(0)   // только 4 байта вместо 8

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит пакет CMD_RECORDS (0x01) с одной записью") {
        val buffer = Unpooled.buffer()

        // Packet header
        buffer.writeShort(100) // packet length (approximate, parser reads from stream)
        buffer.writeLong(352093082745395L) // IMEI (8B, skipped in parseData)
        buffer.writeByte(0x01) // CMD_RECORDS

        // Record count
        buffer.writeByte(1)

        // Record:
        // Timestamp (4B, unix sec)
        buffer.writeInt(timestampSec.toInt)
        // Timestamp ext (1B, ms/4)
        buffer.writeByte(0)
        // Flags (1B)
        buffer.writeByte(0)
        // Longitude (4B signed, × 10^7)
        buffer.writeInt(lonEncoded)
        // Latitude (4B signed, × 10^7)
        buffer.writeInt(latEncoded)
        // Altitude (2B signed)
        buffer.writeShort(altEncoded)
        // Angle (2B)
        buffer.writeShort(angleEncoded)
        // Satellites (1B)
        buffer.writeByte(satsEncoded)
        // Speed (2B, km/h × 100)
        buffer.writeShort(speedEncoded)
        // HDOP (1B)
        buffer.writeByte(10)
        // Event ID (1B)
        buffer.writeByte(0)
        // IO elements: total count + sub-counts (5 bytes)
        buffer.writeByte(0) // ioCount (total)
        buffer.writeByte(0) // io1 count (1B elements)
        buffer.writeByte(0) // io2 count (2B elements)
        buffer.writeByte(0) // io4 count (4B elements)
        buffer.writeByte(0) // io8 count (8B elements)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.size == 1) &&
          assertTrue(points.head.imei == "352093082745395") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.0001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.0001) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      },

      test("неизвестный CMD возвращает пустой список") {
        val buffer = Unpooled.buffer()
        buffer.writeShort(12) // length
        buffer.writeLong(352093082745395L) // IMEI skip
        buffer.writeByte(0xFF) // unknown CMD

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield assertTrue(points.isEmpty)
      }
    ),

    suite("ack")(
      test("формирует ACK пакет (7 байт)") {
        val ack = parser.ack(5)
        // [Length 2B=3][CmdID 1B=0x64][RecordCount 2B][CRC16 2B]
        assertTrue(ack.readableBytes() == 7) &&
        assertTrue(ack.readUnsignedShort() == 3) &&      // length
        assertTrue(ack.readByte() == 0x64.toByte)         // CMD_ACK
      },

      test("формирует IMEI ACK — принят (5 байт)") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readUnsignedShort() == 1) &&       // length
        assertTrue(ack.readByte() == 0x01.toByte)          // accepted
      },

      test("формирует IMEI ACK — отклонён") {
        val ack = parser.imeiAck(false)
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readUnsignedShort() == 1) &&
        assertTrue(ack.readByte() == 0x00.toByte)
      }
    )
  )
