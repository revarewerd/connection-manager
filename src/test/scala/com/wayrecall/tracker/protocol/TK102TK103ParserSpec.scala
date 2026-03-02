package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled

/**
 * Тесты для TK102Parser и TK103Parser (текстовый протокол)
 *
 * Формат IMEI: (serial_12BP00cmd)
 * serial = 12 символов, IMEI = "3528" + serial.dropWhile('0')
 * Формат данных: (serial_12BR00yyMMddAlatNlonEspeedHHmmsscourse)
 * Координаты: NMEA DDMM.MMMM
 * Altitude: всегда 0 (не передаётся)
 * Satellites: всегда 9 (хардкод)
 */
object TK102ParserSpec extends ZIOSpecDefault:

  val parser = TK102Parser.tk102
  val parserTK103 = TK102Parser.tk103

  def spec = suite("TK102Parser")(

    suite("parseImei")(
      test("парсит IMEI из логин-пакета BP00") {
        // serial = "012345678901"
        // IMEI = "3528" + "012345678901".dropWhile('0') = "3528" + "12345678901" = "352812345678901"
        val frame = "(012345678901BP00HSO)"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352812345678901")
      },

      test("парсит IMEI из пакета с нулями в серийном номере") {
        // serial = "000093082745"
        // IMEI = "3528" + "93082745" = "352893082745"
        val frame = "(000093082745BP00HSO)"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result.startsWith("3528"))
      },

      test("парсит пакет без скобок (парсер не требует их)") {
        // Парсер обрабатывает и с скобками и без — content = msg если нет '('
        val frame = "012345678901BP00HSO"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352812345678901")
      }
    ),

    suite("parseData")(
      test("парсит GPS данные из пакета BR00") {
        // serial=012345678901, cmd=BR00
        // date=030126, validity=A
        // lat=5545.1929N → 55 + 45.1929/60 ≈ 55.753
        // lon=03737.2814E → 37 + 37.2814/60 ≈ 37.621
        // speed=060.0, time=120000, course=180.00
        val frame = "(012345678901BR00030126A5545.1929N03737.2814E060.0120000180.00)"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          points <- parser.parseData(buffer, "352812345678901")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352812345678901") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621) < 0.01) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.altitude == 0) && // TK102 не передаёт высоту
          assertTrue(points.head.satellites == 9) // хардкод
      },

      test("парсит TK103 (тот же формат)") {
        val frame = "(012345678901BR00030126A5545.1929N03737.2814E060.0120000180.00)"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          points <- parserTK103.parseData(buffer, "352812345678901")
        yield assertTrue(points.nonEmpty)
      },

      test("BP00 (логин) не содержит GPS данных") {
        val frame = "(012345678901BP00HSO)"
        val buffer = Unpooled.copiedBuffer(frame.getBytes("ASCII"))

        for
          result <- parser.parseData(buffer, "352812345678901").either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("ack")(
      test("protocolName всегда tk102") {
        // protocolName хардкод 'tk102' в классе, protocolSuffix используется для ошибок
        assertTrue(parser.protocolName == "tk102")
      },

      test("protocolName TK103 тоже tk102") {
        // TK103 использует тот же класс, protocolName одинаковый
        assertTrue(parserTK103.protocolName == "tk102")
      }
    )
  )
