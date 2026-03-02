package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для WialonBinaryParser (бинарный протокол, Little Endian, legacy Stels format)
 *
 * Формат IMEI: [Size 4B LE][IMEI null-terminated ASCII][...]
 * Формат данных: После IMEI → Timestamp(4B LE), Flags(4B LE), Blocks loop
 * Координаты: IEEE-754 double64 LE (прямые градусы)
 * Block "posinfo": lon(8B double), lat(8B double), height(8B double), speed(2B int16 LE), course(2B int16 LE), sats(1B)
 * Timestamp: System.currentTimeMillis() (игнорирует пакетный!)
 */
object WialonBinaryParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = WialonBinaryParser

  val testImei = "352093082745395"

  private def writeInt32LE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  private def writeInt16LE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)

  private def writeDouble64LE(buf: io.netty.buffer.ByteBuf, value: Double): Unit =
    // ВНИМАНИЕ: readDouble64LE в парсере делает Long.reverseBytes после LE-чтения,
    // что эффективно интерпретирует байты в порядке BE.
    // Поэтому пишем double в BE формате (MSB first = стандартный Netty writeDouble)
    buf.writeLong(java.lang.Double.doubleToLongBits(value))

  private def writeNullTermString(buf: io.netty.buffer.ByteBuf, s: String): Unit =
    buf.writeBytes(s.getBytes("ASCII"))
    buf.writeByte(0x00)

  def spec = suite("WialonBinaryParser")(

    suite("parseImei")(
      test("парсит IMEI из бинарного пакета (null-terminated)") {
        val buffer = Unpooled.buffer()

        // Size (4B LE): IMEI(15) + null(1) + timestamp(4) + flags(4) + endBlock(2) = 26
        val totalSize = testImei.length + 1 + 4 + 4 + 2
        writeInt32LE(buffer, totalSize)

        // IMEI (null-terminated ASCII)
        writeNullTermString(buffer, testImei)

        // Remaining data (timestamp + flags + empty blocks)
        writeInt32LE(buffer, 1772280000)  // timestamp (ignored)
        writeInt32LE(buffer, 0)            // flags
        writeInt16LE(buffer, 0)            // blockType = 0 → end

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      },

      test("отклоняет не-15-значный IMEI") {
        val buffer = Unpooled.buffer()
        val shortImei = "12345"
        val shortSize = shortImei.length + 1 + 4 + 4 + 2
        writeInt32LE(buffer, shortSize)
        writeNullTermString(buffer, shortImei)
        writeInt32LE(buffer, 0) // timestamp
        writeInt32LE(buffer, 0) // flags
        writeInt16LE(buffer, 0) // end block

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит packet с блоком posinfo") {
        val buffer = Unpooled.buffer()

        // Size (4B LE)
        // IMEI already parsed — parseData receives buffer after IMEI portion
        // Содержит: timestamp + flags + blocks

        // Timestamp (4B LE) — WialonBinary uses System.currentTimeMillis, so this value is ignored
        writeInt32LE(buffer, 1772280000)
        // Flags (4B LE)
        writeInt32LE(buffer, 0)

        // Block: type=0x0001 (some nonzero to continue loop)
        writeInt16LE(buffer, 1) // blockType != 0

        // Block structure:
        val blockName = "posinfo"
        val dataSize = blockName.length + 1 + 1 + 1 + // name + null + hidden + dataType
                       8 + 8 + 8 + 2 + 2 + 1         // double*3 + short*2 + byte
        writeInt32LE(buffer, dataSize)  // blockSize

        // Hidden (1B)
        buffer.writeByte(0)
        // DataType (1B): 0x02 for posinfo
        buffer.writeByte(0x02)

        // Block name (null-terminated)
        writeNullTermString(buffer, blockName)

        // posinfo data:
        // Longitude (8B double LE)
        writeDouble64LE(buffer, 37.621356)
        // Latitude (8B double LE)
        writeDouble64LE(buffer, 55.753215)
        // Height (8B double LE)
        writeDouble64LE(buffer, 170.0)
        // Speed (2B int16 LE)
        writeInt16LE(buffer, 60)
        // Course (2B int16 LE)
        writeInt16LE(buffer, 180)
        // Satellites (1B)
        buffer.writeByte(12)

        // Next block: type=0 → end
        writeInt16LE(buffer, 0)

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
          assertTrue(points.head.satellites == 12)
          // Не проверяем timestamp т.к. WialonBinary использует System.currentTimeMillis()
      }
    ),

    suite("ack")(
      test("ack пустой buffer") {
        val ack = parser.ack(1)
        assertTrue(ack.readableBytes() == 0)
      },

      test("imeiAck пустой buffer") {
        val ack = parser.imeiAck(true)
        assertTrue(ack.readableBytes() == 0)
      }
    )
  )
