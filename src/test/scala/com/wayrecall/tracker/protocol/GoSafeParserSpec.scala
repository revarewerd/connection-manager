package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * Тесты для GoSafeParser (текстовый протокол GoSafe)
 *
 * Формат IMEI: *GS16,IMEI,... или $IMEI,...
 * Формат данных ($): IMEI,DDMMYYHHMMSS,lat,N/S,lon,E/W,speed(knots),course,alt,sat,...
 * Координаты ($): decimal degrees напрямую
 * Скорость: в узлах, конвертация × 1.852 → км/ч
 */
object GoSafeParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = GoSafeParser

  def spec = suite("GoSafeParser")(

    suite("parseImei")(
      test("парсит IMEI из формата *GS") {
        val frame = "*GS16,352093082745395,0000#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("парсит IMEI из формата $ (dollar)") {
        val frame = "$352093082745395,070623120000,55.753215,N,037.621356,E,000.0,000,170,12#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("отклоняет IMEI с буквами") {
        val frame = "*GS16,ABCDEF,0000#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      },

      test("отклоняет слишком короткий IMEI") {
        val frame = "*GS16,12345,0000#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит данные в формате $ (decimal degrees)") {
        // Координаты напрямую в градусах, скорость в узлах
        // 32.4 узла × 1.852 = 60.0 км/ч
        val frame = "$352093082745395,010326120000,55.753215,N,037.621356,E,032.4,180,170,12,1.2,000#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352093082745395") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621356) < 0.001) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.satellites == 12)
      },

      test("парсит данные в формате GPS: (coordinate prefix)") {
        val frame = "*GS16,352093082745395,GPS:1;N55.753;E037.621;170;32.4;180;120000;010326#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.latitude > 55.0) &&
          assertTrue(points.head.longitude > 37.0)
      }
    ),

    suite("ack")(
      test("ack пустой (нет ACK у GoSafe)") {
        val ack = parser.ack(1)
        assertTrue(ack.readableBytes() == 0)
      },

      test("imeiAck — принят") {
        val ack = parser.imeiAck(true)
        val str = ack.toString(US_ASCII)
        assertTrue(str == "OK\r\n")
      },

      test("imeiAck — отклонён") {
        val ack = parser.imeiAck(false)
        val str = ack.toString(US_ASCII)
        assertTrue(str == "ERR\r\n")
      }
    )
  )
