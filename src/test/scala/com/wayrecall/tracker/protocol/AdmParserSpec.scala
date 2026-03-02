package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для AdmParser (бинарный протокол, Little Endian, variable structure)
 *
 * Формат IMEI: [DeviceId 2B LE][Size 1B][Type 1B (bit0-1=0x03 AUTH)][IMEI 15B ASCII][HwType 1B][ReplyEnabled 1B]
 * Формат данных: [DeviceId 2B LE][Size 1B][Type 1B (bit0-1=0x00/0x01)][Soft 1B][GpsPntr 2B LE]
 *               [Status 2B LE][Lat 4B float32 LE][Lon 4B float32 LE]
 *               [Course 2B LE × 10][Speed 2B LE × 10][Acc 1B][Height 2B LE][HDOP 1B][Sats 1B]
 *               [Timestamp 4B LE unix sec][Power 2B][Battery 2B]
 * Координаты: IEEE-754 float32 (прямые градусы)
 * Скорость: uint16 × 10 (км/ч)
 */
object AdmParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = AdmParser

  val testImei = "352093082745395"
  val timestampSec: Long = 1772280000L

  private def writeShortLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)

  private def writeIntLE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  /** Записывает float32 в формате Little Endian */
  private def writeFloatLE(buf: io.netty.buffer.ByteBuf, value: Float): Unit =
    writeIntLE(buf, java.lang.Float.floatToIntBits(value))

  def spec = suite("AdmParser")(

    suite("parseImei")(
      test("парсит IMEI из AUTH пакета") {
        val buffer = Unpooled.buffer()
        // DeviceId (2B LE)
        writeShortLE(buffer, 0x0001)
        // Size (1B): 15 + 1 + 1 = 17
        buffer.writeByte(17)
        // Type (1B): bits 0-1 = 0x03 (AUTH)
        buffer.writeByte(0x03)
        // IMEI (15B ASCII)
        buffer.writeBytes(testImei.getBytes("ASCII"))
        // HwType (1B)
        buffer.writeByte(0x01)
        // ReplyEnabled (1B): 0x02 = needs ACK
        buffer.writeByte(0x02)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      }
    ),

    suite("parseData")(
      test("парсит DATA пакет с float32 координатами") {
        val buffer = Unpooled.buffer()
        // DeviceId (2B LE)
        writeShortLE(buffer, 0x0001)
        // Size (1B): примерный размер
        buffer.writeByte(40)
        // Type (1B): bits 0-1 = 0x01 (DATA), no extra blocks
        buffer.writeByte(0x01)

        // Soft (1B)
        buffer.writeByte(0x01)
        // GpsPntr (2B LE)
        writeShortLE(buffer, 0)
        // Status (2B LE)
        writeShortLE(buffer, 0)

        // Latitude (4B float32 LE)
        writeFloatLE(buffer, 55.753215f)
        // Longitude (4B float32 LE)
        writeFloatLE(buffer, 37.621356f)

        // Course (2B LE, × 10): 1800 → 180°
        writeShortLE(buffer, 1800)
        // Speed (2B LE, × 10): 600 → 60 km/h
        writeShortLE(buffer, 600)

        // Acc (1B)
        buffer.writeByte(0)
        // Height (2B LE uint16)
        writeShortLE(buffer, 170)
        // HDOP (1B)
        buffer.writeByte(15)
        // Satellites (1B)
        buffer.writeByte(12)

        // Timestamp (4B LE, unix sec)
        writeIntLE(buffer, timestampSec.toInt)

        // Power (2B LE)
        writeShortLE(buffer, 12000)
        // Battery (2B LE)
        writeShortLE(buffer, 4200)

        for
          points <- parser.parseData(buffer, testImei)
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == testImei) &&
          // float32 precision: ≈ 6 значащих цифр
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.01) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.satellites == 12) &&
          assertTrue(points.head.timestamp == timestampSec * 1000)
      },

      test("нулевые координаты (0.0f) → NaN → точка пропускается") {
        val buffer = Unpooled.buffer()
        writeShortLE(buffer, 0x0001) // DeviceId
        buffer.writeByte(40) // size
        buffer.writeByte(0x01) // type DATA

        buffer.writeByte(0x01) // Soft
        writeShortLE(buffer, 0) // GpsPntr
        writeShortLE(buffer, 0) // Status

        // Нулевые координаты → no GPS fix
        writeFloatLE(buffer, 0.0f)
        writeFloatLE(buffer, 0.0f)

        writeShortLE(buffer, 0) // Course
        writeShortLE(buffer, 0) // Speed

        buffer.writeByte(0) // Acc
        writeShortLE(buffer, 0) // Height
        buffer.writeByte(0) // HDOP
        buffer.writeByte(0) // Sats
        writeIntLE(buffer, timestampSec.toInt)
        writeShortLE(buffer, 0)
        writeShortLE(buffer, 0)

        for
          result <- parser.parseData(buffer, testImei).either
        yield assertTrue(result.isLeft) // 0.0f → NaN → ProtocolError
      }
    ),

    suite("ack")(
      test("imeiAck пустой") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 0)
      }
    )
  )
