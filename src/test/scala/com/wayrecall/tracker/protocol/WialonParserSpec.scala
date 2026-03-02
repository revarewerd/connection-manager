package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Тесты для WialonParser (текстовый протокол Wialon IPS)
 *
 * Формат IMEI: #L#imei;password\r\n
 * Формат данных: #D#date;time;lat;N/S;lon;E/W;speed;course;alt;sats\r\n
 * Координаты: NMEA DDMM.MMMM
 */
object WialonParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = WialonParser

  def spec = suite("WialonParser")(

    suite("parseImei")(
      test("парсит валидный IMEI из логин-пакета") {
        val frame = "#L#352093082745395;password\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("парсит IMEI без пароля") {
        val frame = "#L#352093082745395;\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("отклоняет пакет без префикса #L#") {
        val frame = "#D#data;here\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      },

      test("отклоняет пустой IMEI") {
        val frame = "#L#;pass\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит пакет #D# с GPS данными") {
        // lat 55°45.1929' N = 55 + 45.1929/60 = 55.753215
        // lon 37°37.2814' E = 37 + 37.2814/60 = 37.621356...
        val frame = "#D#010326;120000;5545.1929;N;03737.2814;E;60;180;170;12\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352093082745395") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753215) < 0.001) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.6214) < 0.001) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 170) &&
          assertTrue(points.head.satellites == 12)
      },

      test("парсит пакет #SD# (короткий формат)") {
        val frame = "#SD#010326;120000;5545.1929;N;03737.2814;E;60;180;170;12\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield assertTrue(points.nonEmpty) && assertTrue(points.head.speed == 60)
      },

      test("пинг #P# возвращает пустой список") {
        val frame = "#P#\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield assertTrue(points.isEmpty)
      },

      test("южная широта и западная долгота — отрицательные координаты") {
        // 33°51.2340 S → -33.8539
        // 18°25.6780 W → -18.4280
        val frame = "#D#010326;120000;3351.2340;S;01825.6780;W;0;0;0;8\r\n"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.head.latitude < 0.0) &&
          assertTrue(points.head.longitude < 0.0)
      }
    ),

    suite("ack")(
      test("формирует ACK с количеством записей") {
        val ack = parser.ack(5)
        val str = ack.toString(UTF_8)
        assertTrue(str == "#AD#5\r\n")
      },

      test("формирует IMEI ACK — принят") {
        val ack = parser.imeiAck(true)
        val str = ack.toString(UTF_8)
        assertTrue(str == "#AL#1\r\n")
      },

      test("формирует IMEI ACK — отклонён") {
        val ack = parser.imeiAck(false)
        val str = ack.toString(UTF_8)
        assertTrue(str == "#AL#0\r\n")
      }
    )
  )
