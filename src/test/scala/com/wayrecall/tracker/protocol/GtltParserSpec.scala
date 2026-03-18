package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * Тесты для GtltParser (текстовый CSV протокол *HQ,...#)
 *
 * Формат IMEI: *HQ,serial,command,...#
 * Формат данных: 12+ полей, NMEA координаты
 * Altitude: всегда 0 (не передаётся)
 * Satellites: всегда 0 (не передаётся)
 */
object GtltParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = GtltParser

  def spec = suite("GtltParser")(

    suite("parseImei")(
      test("парсит IMEI из пакета *HQ") {
        val frame = "*HQ,352093082745395,V1,120000,A,5545.1929,N,03737.2814,E,60,180,010326,FFFFFBFF#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093082745395")
      },

      test("отклоняет пакет без *HQ") {
        val frame = "INVALID,352093082745395,V1#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          result <- parser.parseImei(buffer).either
        yield assertTrue(result.isLeft)
      }
    ),

    suite("parseData")(
      test("парсит GPS данные") {
        // lat: 5545.1929 N → deg=55, min=45.1929 → 55 + 45.1929/60 ≈ 55.753
        // lon: 03737.2814 E → deg=37, min=37.2814 → 37 + 37.2814/60 ≈ 37.621
        val frame = "*HQ,352093082745395,V1,120000,A,5545.1929,N,03737.2814,E,60,180,010326,FFFFFBFF#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.imei == "352093082745395") &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621) < 0.01) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 0) && // GTLT не передаёт высоту
          assertTrue(points.head.satellites == 0) // GTLT не передаёт спутники
      },

      test("южная широта даёт отрицательную координату") {
        val frame = "*HQ,352093082745395,V1,120000,A,3351.2340,S,01825.6780,W,0,0,010326,FFFFFBFF#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.head.latitude < 0.0) &&
          assertTrue(points.head.longitude < 0.0)
      },

      test("невалидные speed/direction → 0 вместо crash") {
        val frame = "*HQ,352093082745395,V1,120000,A,5545.1929,N,03737.2814,E,abc,xyz,010326,FFFFFBFF#"
        val buffer = Unpooled.copiedBuffer(frame, US_ASCII)

        for
          points <- parser.parseData(buffer, "352093082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(points.head.speed == 0) &&
          assertTrue(points.head.angle == 0)
      }
    ),

    suite("ack")(
      test("protocolName") {
        assertTrue(parser.protocolName == "gtlt")
      }
    )
  )
