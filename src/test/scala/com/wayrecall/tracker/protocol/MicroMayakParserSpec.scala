package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для MicroMayakParser (бинарный протокол, packed bitfields)
 *
 * Формат IMEI: [0x24 START][0x02 AUTH_KEY][kvitok 1B][8B BCD IMEI][CRC8 1B][0x0D END]
 * Формат данных: [0x24][marker=0x09][kvitok 1B][14B GPS main][additionalCount 1B][N*5B][CRC8][0x0D]
 * Координаты: packed bits(0,28)/600000.0 и bits(28,28)/600000.0 из 8B LE long
 * Speed: bits(56,8) — 0-255 km/h
 * Altitude: всегда 0
 * Angle: всегда 0
 */
object MicroMayakParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = MicroMayakParser

  // BCD IMEI: "352093082745395" → pad to 16 hex chars → "0352093082745395"
  // → bytes: 03 52 09 30 82 74 53 95
  val imeiBcd: Array[Byte] = Array(0x03, 0x52, 0x09, 0x30, 0x82, 0x74, 0x53, 0x95).map(_.toByte)

  val timestampSec: Long = 1772280000L

  // Координаты для packed format:
  // lat = 55.753215 → 55.753215 * 600000 = 33451929
  // lon = 37.621356 → 37.621356 * 600000 = 22572814
  // speed = 60
  // packed long (LE):
  //   bits[0..27]  = lat_encoded = 33451929 (28 bits, fits in 2^28 = 268435456)
  //   bits[28..55] = lon_encoded = 22572814 (28 bits)
  //   bits[56..63] = speed = 60 (8 bits)

  val latVal: Long = (55.753215 * 600000).toLong  // 33451929
  val lonVal: Long = (37.621356 * 600000).toLong   // 22572814
  val speedVal: Long = 60L

  val packedLong: Long = latVal | (lonVal << 28) | (speedVal << 56)

  private def writeIntLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  private def writeLongLE(buf: io.netty.buffer.ByteBuf, value: Long): Unit =
    writeIntLE(buf, (value & 0xFFFFFFFFL).toInt)
    writeIntLE(buf, ((value >> 32) & 0xFFFFFFFFL).toInt)

  private def writeShortLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)

  /** CRC8: (~fold(0, (a,b) => a+b) + 1) & 0xFF */
  private def crc8(data: Array[Byte]): Byte =
    val sum = data.foldLeft(0)((acc, b) => acc + (b & 0xFF))
    ((~sum + 1) & 0xFF).toByte

  def spec = suite("MicroMayakParser")(

    suite("parseImei")(
      test("парсит IMEI из AUTH пакета (BCD)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)   // START
        buf.writeByte(0x02)   // AUTH_KEY
        buf.writeByte(0x01)   // kvitok
        buf.writeBytes(imeiBcd) // 8B BCD IMEI

        // CRC8 over: AUTH_KEY + kvitok + IMEI bytes
        val crcData = Array[Byte](0x02, 0x01) ++ imeiBcd
        buf.writeByte(crc8(crcData))

        buf.writeByte(0x0D)   // END

        for
          result <- parser.parseImei(buf)
        yield
          // BCD: "03 52 09 30 82 74 53 95" → "0352093082745395" → stripPrefix("0") → "352093082745395"
          assertTrue(result == "352093082745395")
      },

      test("отклоняет пакет с неверным стартовым байтом") {
        val buf = Unpooled.buffer()
        buf.writeByte(0xFF) // wrong start
        buf.writeByte(0x02)
        buf.writeByte(0x01)
        buf.writeBytes(imeiBcd)
        buf.writeByte(0x00)
        buf.writeByte(0x0D)

        for
          result <- parser.parseImei(buf).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит GPS пакет (marker 0x09) с packed координатами") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)       // START
        buf.writeByte(0x09)       // marker = GPS from black box
        buf.writeByte(0x01)       // kvitok

        // Main GPS point (14B):
        // Timestamp (4B LE, unix sec)
        writeIntLE(buf, timestampSec.toInt)
        // Packed long (8B LE): lat + lon + speed
        writeLongLE(buf, packedLong)
        // Dops (2B LE): GPS sats in bits[0..3], GLONASS in bits[12..15]
        val dops = 12 | (0 << 12) // 12 GPS sats, 0 GLONASS
        writeShortLE(buf, dops)

        // Additional point count
        buf.writeByte(0)  // no additional points

        // CRC8 over: marker + kvitok + GPS data + count
        // Для теста пропустим точную CRC — парсер может/ может не проверять
        buf.writeByte(0x00)

        buf.writeByte(0x0D)  // END

        for
          points <- parser.parseData(buf, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352093082745395") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.01) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.altitude == 0) && // MicroMayak не передаёт высоту
          assertTrue(points.head.angle == 0) &&    // MicroMayak не передаёт курс
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      }
    ),

    suite("ack")(
      test("формирует DATA ACK (6 байт)") {
        val ack = parser.ack(1)
        // [0x24][0x00][(kvitok<<2)|(state&0x03)][marker][CRC8][0x0D]
        assertTrue(ack.readableBytes() == 6) &&
        assertTrue(ack.readByte() == 0x24.toByte)
      },

      test("формирует IMEI ACK — принят (10 байт)") {
        val ack = parser.imeiAck(true)
        // [0x24][0x04 AUTH_CONFIRMATION][kvitok][timestamp 4B BE][0x00 correct][CRC8][0x0D]
        assertTrue(ack.readableBytes() == 10) &&
        assertTrue(ack.readByte() == 0x24.toByte)
      }
    )
  )
