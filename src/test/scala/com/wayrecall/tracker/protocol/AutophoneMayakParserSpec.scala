package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для AutophoneMayakParser (бинарный протокол, Little Endian)
 *
 * Формат IMEI: [0x4D][Version 1B][Length 2B LE][MsgType=0x01][IMEI 15B ASCII][CRC 2B]
 * Формат данных: [0x4D][Version 1B][Length 2B LE][MsgType=0x02][RecordCount 1B][records 20B each]
 * Координаты: int32 LE signed × 10^6
 * Скорость: uint8, км/ч
 * RECORD_SIZE = 20 байт
 */
object AutophoneMayakParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = AutophoneMayakParser

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

  def spec = suite("AutophoneMayakParser")(

    suite("parseImei")(
      test("парсит IMEI из AUTH пакета") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x4D)                          // Header 'M'
        buffer.writeByte(0x01)                          // Version
        writeShortLE(buffer, 15 + 2)                    // Length LE: IMEI + CRC
        buffer.writeByte(0x01)                          // MsgType = AUTH
        buffer.writeBytes(testImei.getBytes("ASCII"))   // IMEI 15B
        writeShortLE(buffer, 0x0000)                    // CRC

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      }
    ),

    suite("parseData")(
      test("парсит DATA пакет с одной записью (20 байт)") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x4D)              // Header
        buffer.writeByte(0x01)              // Version
        writeShortLE(buffer, 22)            // Length LE (1 type + 1 count + 20 record)
        buffer.writeByte(0x02)              // MsgType = DATA

        // Record count
        buffer.writeByte(1)

        // Record (20 bytes):
        // Lat (4B LE, × 10^6)
        writeIntLE(buffer, latEncoded)
        // Lon (4B LE, × 10^6)
        writeIntLE(buffer, lonEncoded)
        // Altitude (2B LE signed)
        writeShortLE(buffer, 170)
        // Speed (1B uint8, km/h)
        buffer.writeByte(60)
        // Course (2B LE uint16)
        writeShortLE(buffer, 180)
        // Satellites (1B)
        buffer.writeByte(12)
        // Timestamp (4B LE, unix sec)
        writeIntLE(buffer, timestampSec.toInt)
        // Battery (1B)
        buffer.writeByte(90)
        // Temperature (1B signed)
        buffer.writeByte(25)
        // GSM Signal (1B)
        buffer.writeByte(20)

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
      },

      test("фильтрует точки с нулевыми координатами (нет GPS fix)") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x4D)
        buffer.writeByte(0x01)
        writeShortLE(buffer, 22)
        buffer.writeByte(0x02) // DATA

        buffer.writeByte(1) // 1 record

        // Нулевые координаты → должна быть отфильтрована
        writeIntLE(buffer, 0) // lat = 0
        writeIntLE(buffer, 0) // lon = 0
        writeShortLE(buffer, 0) // alt
        buffer.writeByte(0) // speed
        writeShortLE(buffer, 0) // course
        buffer.writeByte(0) // sats
        writeIntLE(buffer, timestampSec.toInt)
        buffer.writeByte(0) // battery
        buffer.writeByte(0) // temp
        buffer.writeByte(0) // gsm

        for
          result <- parser.parseData(buffer, testImei).either
        yield assertTrue(result.isLeft) // 0,0 координаты → парсер выбрасывает ProtocolError
      }
    ),

    suite("ack")(
      test("формирует ACK (5 байт)") {
        val ack = parser.ack(1)
        // [0x4D][0x01 ver][Length=1 LE][0x03]
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readByte() == 0x4D.toByte)
      },

      test("формирует IMEI ACK — принят (6 байт)") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 6) &&
        assertTrue(ack.readByte() == 0x4D.toByte)
      }
    )
  )
