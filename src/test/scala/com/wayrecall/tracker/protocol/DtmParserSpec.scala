package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для DtmParser (бинарный протокол, Big Endian)
 *
 * Формат IMEI: [0x7B][MsgType=0x01][Length 2B BE][IMEI 15B ASCII][DeviceType 1B][FirmwareVer 2B][CRC 1B]
 * Формат данных: [0x7B][MsgType=0x02][Length 2B BE][RecordCount 1B][records 26B each]
 * Координаты: int32 signed × 10^7
 * Скорость: uint16 × 10 (км/ч)
 * RECORD_SIZE = 26 байт
 */
object DtmParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = DtmParser

  val testImei = "352093082745395"
  val latEncoded: Int = (55.753215 * 10000000).toInt   // 557532150
  val lonEncoded: Int = (37.621356 * 10000000).toInt    // 376213560
  val timestampSec: Long = 1772280000L

  def spec = suite("DtmParser")(

    suite("parseImei")(
      test("парсит IMEI из AUTH пакета") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x7B)                           // Header '{'
        buffer.writeByte(0x01)                           // MsgType = AUTH
        buffer.writeShort(15 + 1 + 2 + 1)               // Length BE: IMEI + DevType + FW + CRC
        buffer.writeBytes(testImei.getBytes("ASCII"))    // IMEI 15B
        buffer.writeByte(0x01)                           // Device Type
        buffer.writeShort(0x0100)                        // Firmware Version
        buffer.writeByte(0x00)                           // CRC

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      }
    ),

    suite("parseData")(
      test("парсит DATA пакет (0x02) с одной записью") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x7B)       // Header
        buffer.writeByte(0x02)       // MsgType = DATA
        buffer.writeShort(27)        // Length BE: 1 count + 26 record

        // Record count
        buffer.writeByte(1)

        // Record (26 bytes):
        // Timestamp (4B, unix sec)
        buffer.writeInt(timestampSec.toInt)
        // FixFlags (1B): bit0=validGps=1, bit1=moving=1
        buffer.writeByte(0x03)
        // Latitude (4B signed, × 10^7)
        buffer.writeInt(latEncoded)
        // Longitude (4B signed, × 10^7)
        buffer.writeInt(lonEncoded)
        // Altitude (2B signed)
        buffer.writeShort(170)
        // Speed (2B, km/h × 10) → 600 → 60 km/h
        buffer.writeShort(600)
        // Course (2B, degrees × 10) → 1800 → 180°
        buffer.writeShort(1800)
        // Satellites (1B)
        buffer.writeByte(12)
        // HDOP (1B)
        buffer.writeByte(10)
        // Mileage (4B)
        buffer.writeInt(0)
        // DigitalIO (1B)
        buffer.writeByte(0)

        for
          points <- parser.parseData(buffer, testImei)
        yield
          assertTrue(points.size == 1) &&
          assertTrue(points.head.imei == testImei) &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.0001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.0001) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      },

      test("парсит ALARM пакет (0x04) как обычные данные") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x7B)
        buffer.writeByte(0x04)   // MSG_ALARM
        buffer.writeShort(27)

        buffer.writeByte(1) // count

        // Record:
        buffer.writeInt(timestampSec.toInt)
        buffer.writeByte(0x05) // valid GPS + alarm bit
        buffer.writeInt(latEncoded)
        buffer.writeInt(lonEncoded)
        buffer.writeShort(170)
        buffer.writeShort(600) // speed × 10
        buffer.writeShort(1800) // course × 10
        buffer.writeByte(12)
        buffer.writeByte(10) // HDOP
        buffer.writeInt(0) // mileage
        buffer.writeByte(0) // IO

        for
          points <- parser.parseData(buffer, testImei)
        yield assertTrue(points.nonEmpty) && assertTrue(points.head.speed == 60)
      },

      test("фильтрует записи без GPS fix (bit0=0)") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0x7B)
        buffer.writeByte(0x02)
        buffer.writeShort(27)

        buffer.writeByte(1) // count

        buffer.writeInt(timestampSec.toInt)
        buffer.writeByte(0x00) // FixFlags: NO valid GPS
        buffer.writeInt(latEncoded)
        buffer.writeInt(lonEncoded)
        buffer.writeShort(0)
        buffer.writeShort(0)
        buffer.writeShort(0)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeInt(0)
        buffer.writeByte(0)

        for
          result <- parser.parseData(buffer, testImei).either
        yield assertTrue(result.isLeft) // bit0=0 → нет GPS → ProtocolError
      }
    ),

    suite("ack")(
      test("формирует ACK (5 байт)") {
        val ack = parser.ack(3)
        // [0x7B][0x03][Length=1 BE][recordCount 1B]
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readByte() == 0x7B.toByte)
      },

      test("формирует IMEI ACK — принят") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 5) &&
        assertTrue(ack.readByte() == 0x7B.toByte)
      }
    )
  )
