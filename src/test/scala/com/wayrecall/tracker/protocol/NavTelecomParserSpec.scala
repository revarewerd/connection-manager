package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}

/**
 * Тесты для NavTelecomParser (бинарный протокол, FLEX, LE + BE signature)
 *
 * Формат IMEI: [Sig 2B BE = 0x2A3E][Length 2B LE][MsgID 2B LE = 0x0100][IMEI 15B ASCII][CRC 2B]
 * Формат данных: FLEX records
 * Координаты: int32 LE signed × 10^7
 * Скорость: uint16 LE × 10 (км/ч)
 */
object NavTelecomParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = NavTelecomParser

  val testImei = "352093082745395"
  val latEncoded: Int = (55.753215 * 10000000).toInt   // 557532150
  val lonEncoded: Int = (37.621356 * 10000000).toInt    // 376213560
  val timestampSec: Long = 1772280000L

  /** Вспомогательный метод для записи Little-Endian short */
  private def writeShortLE(buf: ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)

  /** Вспомогательный метод для записи Little-Endian int */
  private def writeIntLE(buf: ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  def spec = suite("NavTelecomParser")(

    suite("parseImei")(
      test("парсит IMEI из IDENT пакета") {
        val buffer = Unpooled.buffer()
        // Signature (2B BE): 0x2A3E = "*>"
        buffer.writeShort(0x2A3E)
        // Length (2B LE): 15 (IMEI length) + 2 (CRC)
        writeShortLE(buffer, 17)
        // MsgID (2B LE): 0x0100 = IDENT
        writeShortLE(buffer, 0x0100)
        // IMEI (15B ASCII)
        buffer.writeBytes(testImei.getBytes("ASCII"))
        // CRC (2B LE) — заглушка
        writeShortLE(buffer, 0x0000)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      },

      test("отклоняет пакет с неверной сигнатурой") {
        val buffer = Unpooled.buffer()
        buffer.writeShort(0xFFFF) // wrong signature
        writeShortLE(buffer, 17)
        writeShortLE(buffer, 0x0100)
        buffer.writeBytes(testImei.getBytes("ASCII"))
        writeShortLE(buffer, 0x0000)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      },

      test("отклоняет пакет с неверным MSG ID") {
        val buffer = Unpooled.buffer()
        buffer.writeShort(0x2A3E)
        writeShortLE(buffer, 17)
        writeShortLE(buffer, 0xFFFF) // wrong msg id
        buffer.writeBytes(testImei.getBytes("ASCII"))
        writeShortLE(buffer, 0x0000)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит FLEX пакет с одной записью") {
        val buffer = Unpooled.buffer()
        // Signature
        buffer.writeShort(0x2A3E)
        // Length LE: msgId(2) + record(24) + crc(2) = 28
        writeShortLE(buffer, 28)
        // MsgID LE: 0xF100 = MSG_FLEX
        writeShortLE(buffer, 0xF100)

        // FLEX Record:
        // RecordID (2B LE)
        writeShortLE(buffer, 1)
        // Timestamp (4B LE, unix sec)
        writeIntLE(buffer, timestampSec.toInt)
        // Flags (1B): bit0 = hasGps = 1
        buffer.writeByte(0x01)
        // Latitude (4B LE signed, × 10^7)
        writeIntLE(buffer, latEncoded)
        // Longitude (4B LE signed, × 10^7)
        writeIntLE(buffer, lonEncoded)
        // Altitude (2B LE signed)
        writeShortLE(buffer, 170)
        // Speed (2B LE, km/h × 10) → 600 → 60 km/h
        writeShortLE(buffer, 600)
        // Course (2B LE, degrees × 10) → 1800 → 180°
        writeShortLE(buffer, 1800)
        // Satellites (1B)
        buffer.writeByte(12)
        // HDOP (1B)
        buffer.writeByte(15)
        // IO count (1B)
        buffer.writeByte(0) // no IO elements

        // CRC (2B LE) — заглушка
        writeShortLE(buffer, 0x0000)

        for
          points <- parser.parseData(buffer, testImei)
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == testImei) &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.0001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.0001) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      }
    ),

    suite("ack")(
      test("формирует ACK пакет (10 байт)") {
        val ack = parser.ack(5)
        // [0x2A3E sig][Length=4 LE][0x0200 MSG_ACK LE][recordCount 2B LE][CRC16 2B LE]
        assertTrue(ack.readableBytes() == 10)
      },

      test("формирует IMEI ACK — принят") {
        val ack = parser.imeiAck(true)
        // Должен начинаться с сигнатуры 0x2A3E
        assertTrue(ack.readableBytes() > 0) &&
        assertTrue(ack.readShort() == 0x2A3E.toShort)
      }
    )
  )
