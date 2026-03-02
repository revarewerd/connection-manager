package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для SkySimParser (бинарный протокол, Big Endian)
 *
 * Формат IMEI: [0xF0][Length 2B BE][MsgType=0x01][IMEI 15B ASCII][CRC 1B]
 * Формат данных: [0xF0][Length 2B BE][MsgType=0x02][GPS data...][CRC 1B]
 * Координаты: int32 signed × 10^6
 * Скорость: uint8, км/ч
 */
object SkySimParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = SkySimParser

  val testImei = "352093082745395"
  val latEncoded: Int = (55.753215 * 1000000).toInt    // 55753215
  val lonEncoded: Int = (37.621356 * 1000000).toInt     // 37621356
  val timestampSec: Long = 1772280000L

  def spec = suite("SkySimParser")(

    suite("parseImei")(
      test("парсит IMEI из AUTH пакета") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0xF0)                          // Header
        buffer.writeShort(15 + 1)                        // Length: IMEI + CRC
        buffer.writeByte(0x01)                           // MsgType = AUTH
        buffer.writeBytes(testImei.getBytes("ASCII"))   // IMEI 15B
        buffer.writeByte(0x00)                           // CRC (заглушка)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      },

      test("отклоняет IMEI с буквами") {
        val imei = "35209308274539A"
        val buffer = Unpooled.buffer()
        buffer.writeByte(0xF0)
        buffer.writeShort(16)
        buffer.writeByte(0x01)
        buffer.writeBytes(imei.getBytes("ASCII"))
        buffer.writeByte(0x00)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит DATA пакет (MsgType 0x02)") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0xF0)                   // Header
        buffer.writeShort(21)                     // Length: GPS(18) + IO(2) + CRC(1)
        buffer.writeByte(0x02)                   // MsgType = DATA

        // GPS data:
        buffer.writeInt(latEncoded)              // Lat (4B, × 10^6)
        buffer.writeInt(lonEncoded)              // Lon (4B, × 10^6)
        buffer.writeShort(170)                   // Altitude (2B uint16)
        buffer.writeByte(60)                     // Speed (1B uint8, km/h)
        buffer.writeShort(180)                   // Course (2B uint16)
        buffer.writeByte(12)                     // Satellites (1B)
        buffer.writeInt(timestampSec.toInt)      // Timestamp (4B, unix sec)

        // IO Status (2B)
        buffer.writeShort(0)
        // CRC (1B)
        buffer.writeByte(0)

        for
          points <- parser.parseData(buffer, testImei)
        yield
          assertTrue(points.size == 1) &&
          assertTrue(points.head.imei == testImei) &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.001) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      }
    ),

    suite("ack")(
      test("формирует ACK (4 байта)") {
        val ack = parser.ack(1)
        // [0xF0][Length=0x0001 BE][0x03]
        assertTrue(ack.readableBytes() == 4) &&
        assertTrue(ack.readByte() == 0xF0.toByte) &&
        assertTrue(ack.readUnsignedShort() == 1) &&
        assertTrue(ack.readByte() == 0x03.toByte)
      },

      test("формирует IMEI ACK — принят (5 байт)") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readByte() == 0xF0.toByte)
      },

      test("формирует IMEI ACK — отклонён") {
        val ack = parser.imeiAck(false)
        assertTrue(ack.readableBytes() == 5)
      }
    )
  )
