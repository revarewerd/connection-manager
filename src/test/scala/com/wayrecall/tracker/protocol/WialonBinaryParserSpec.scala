package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для WialonBinaryParser (бинарный Wialon Retranslator)
 *
 * Порядок байт (проверено по legacy Java WialonParser):
 *   - Размер пакета (4B): Little-Endian
 *   - Timestamp, Flags, BlockType, BlockSize, Speed, Course: Big-Endian
 *   - Doubles (lon, lat, height): Little-Endian (IEEE-754)
 *
 * Формат parseData: [Size 4B LE][IMEI\0][Timestamp 4B BE][Flags 4B BE][Blocks...]
 * Block: [type 2B BE][size 4B BE][hidden 1B][dataType 1B][name\0][data...]
 */
object WialonBinaryParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = WialonBinaryParser

  val testImei = "352093082745395"

  // ============ Хелперы записи ============

  /** Little-Endian Int32 (только для Size пакета) */
  private def writeInt32LE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte(value & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 24) & 0xFF)

  /** Big-Endian Int16 (blockType, speed, course) */
  private def writeInt16BE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte(value & 0xFF)

  /** Big-Endian Int32 (timestamp, flags, blockSize) */
  private def writeInt32BE(buf: io.netty.buffer.ByteBuf, value: Int): Unit =
    buf.writeByte((value >> 24) & 0xFF)
    buf.writeByte((value >> 16) & 0xFF)
    buf.writeByte((value >> 8) & 0xFF)
    buf.writeByte(value & 0xFF)

  /** Little-Endian Double (координаты: lon, lat, height) */
  private def writeDoubleLE(buf: io.netty.buffer.ByteBuf, value: Double): Unit =
    val bits = java.lang.Double.doubleToLongBits(value)
    // Записываем байты в LE порядке (младший байт первым)
    buf.writeByte((bits & 0xFF).toInt)
    buf.writeByte(((bits >> 8) & 0xFF).toInt)
    buf.writeByte(((bits >> 16) & 0xFF).toInt)
    buf.writeByte(((bits >> 24) & 0xFF).toInt)
    buf.writeByte(((bits >> 32) & 0xFF).toInt)
    buf.writeByte(((bits >> 40) & 0xFF).toInt)
    buf.writeByte(((bits >> 48) & 0xFF).toInt)
    buf.writeByte(((bits >> 56) & 0xFF).toInt)

  private def writeNullTermString(buf: io.netty.buffer.ByteBuf, s: String): Unit =
    buf.writeBytes(s.getBytes("ASCII"))
    buf.writeByte(0x00)

  /** Строит полный бинарный пакет Wialon Retranslator */
  private def buildFullPacket(
    imei: String,
    timestamp: Int,
    flags: Int,
    blocks: io.netty.buffer.ByteBuf
  ): io.netty.buffer.ByteBuf =
    // Считаем размер тела (после Size): IMEI\0 + timestamp + flags + blocks
    val bodySize = imei.length + 1 + 4 + 4 + blocks.readableBytes()
    val buf = Unpooled.buffer()
    writeInt32LE(buf, bodySize)
    writeNullTermString(buf, imei)
    writeInt32BE(buf, timestamp)
    writeInt32BE(buf, flags)
    buf.writeBytes(blocks)
    buf

  def spec = suite("WialonBinaryParser")(

    suite("parseImei")(
      test("парсит IMEI из бинарного пакета (null-terminated)") {
        val blocks = Unpooled.buffer()
        writeInt16BE(blocks, 0) // blockType = 0 → end

        val buffer = buildFullPacket(testImei, 1772280000, 0, blocks)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == testImei)
      },

      test("отклоняет не-15-значный IMEI") {
        val shortImei = "12345"
        val blocks = Unpooled.buffer()
        writeInt16BE(blocks, 0) // end
        val buffer = buildFullPacket(shortImei, 0, 0, blocks)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит packet с блоком posinfo") {
        // Строим блок posinfo
        val blockContent = Unpooled.buffer()
        blockContent.writeByte(0)    // hidden = false
        blockContent.writeByte(0x02) // dataType = 0x02 (posinfo)
        writeNullTermString(blockContent, "posinfo")
        writeDoubleLE(blockContent, 37.621356)  // longitude
        writeDoubleLE(blockContent, 55.753215)  // latitude
        writeDoubleLE(blockContent, 170.0)      // height
        writeInt16BE(blockContent, 60)           // speed
        writeInt16BE(blockContent, 180)          // course
        blockContent.writeByte(12)               // satellites

        val blockSize = blockContent.readableBytes()

        // Собираем блоки: [type 2B BE][size 4B BE][content...] + [type=0 → end]
        val blocks = Unpooled.buffer()
        writeInt16BE(blocks, 1)         // blockType = 1 (non-zero)
        writeInt32BE(blocks, blockSize) // blockSize
        blocks.writeBytes(blockContent)
        writeInt16BE(blocks, 0)         // end sentinel

        val timestamp = 1772280000 // Unix seconds
        val buffer = buildFullPacket(testImei, timestamp, 0, blocks)

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
          assertTrue(points.head.timestamp == 1772280000L * 1000L) // timestamp из пакета
      },

      test("возвращает пустой список если буфер < 4 байт") {
        val buffer = Unpooled.buffer()
        buffer.writeByte(0)
        buffer.writeByte(0)

        for
          points <- parser.parseData(buffer, testImei)
        yield assertTrue(points.isEmpty)
      },

      test("парсит пакет без блока posinfo — 0 точек") {
        // Блок с другим именем (не posinfo) — должен быть пропущен
        val blockContent = Unpooled.buffer()
        blockContent.writeByte(0)    // hidden
        blockContent.writeByte(0x01) // dataType (не 0x02)
        writeNullTermString(blockContent, "sensor1")
        // Произвольные данные датчика
        blockContent.writeByte(42)

        val blockSize = blockContent.readableBytes()

        val blocks = Unpooled.buffer()
        writeInt16BE(blocks, 1)
        writeInt32BE(blocks, blockSize)
        blocks.writeBytes(blockContent)
        writeInt16BE(blocks, 0) // end

        val buffer = buildFullPacket(testImei, 1234567890, 0, blocks)

        for
          points <- parser.parseData(buffer, testImei)
        yield assertTrue(points.isEmpty)
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
