package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Тесты для ArnaviParser (текстовый CSV протокол Arnavi)
 *
 * Формат IMEI: $AV,V2,trackerID,Serial,...
 * Формат данных V2: 20+ полей, X=lon Y=lat (NMEA), altitude=0
 * Координаты: DDDMM.MMMM с суффиксом E/N
 */
object ArnaviParserSpec extends ZIOSpecDefault:

  val parser: ProtocolParser = ArnaviParser

  def spec = suite("ArnaviParser")(

    suite("parseImei")(
      test("парсит IMEI из V2 пакета") {
        // $AV,V2,trackerID,Serial,... → IMEI = trackerID-Serial
        val frame = "$AV,V2,352093,082745395,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093-082745395")
      },

      test("парсит IMEI из V3 пакета") {
        val frame = "$AV,V3,352093,082745395,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          result <- parser.parseImei(buffer)
        yield assertTrue(result == "352093-082745395")
      }
    ),

    suite("parseData")(
      test("парсит GPS данные V2 формата") {
        // V2: [13]=sats, [14]=time, [15]=xcoord(LON), [16]=ycoord(LAT), [17]=speed, [18]=course, [19]=date
        // X=lon: 03737.2814E → NMEA: int(3737.2814/100)=37, min=37.2814, result=37+37.2814/60 ≈ 37.621
        // Y=lat: 5545.1929N → NMEA: int(5545.1929/100)=55, min=45.1929, result=55+45.1929/60 ≈ 55.753
        // Поля 4-12: VIN,VBAT,FSDATA,ISSTOP,ISIGNITION,D_STATE,FREQ1,COUNT1,FIX_TYPE (9 fillers)
        val frame = "$AV,V2,352093,082745395,0,0,0,0,0,0,0,0,3,12,120000,03737.2814E,5545.1929N,60,180,010326,00"
        val buffer = Unpooled.copiedBuffer(frame, UTF_8)

        for
          points <- parser.parseData(buffer, "352093-082745395")
        yield
          assertTrue(points.nonEmpty) &&
          assertTrue(scala.math.abs(points.head.latitude - 55.753) < 0.01) &&
          assertTrue(scala.math.abs(points.head.longitude - 37.621) < 0.01) &&
          assertTrue(points.head.speed == 60) &&
          assertTrue(points.head.angle == 180) &&
          assertTrue(points.head.altitude == 0)
      }
    ),

    suite("ack")(
      test("ACK — RCPTOK") {
        val ack = parser.ack(1)
        val str = ack.toString(UTF_8)
        assertTrue(str == "RCPTOK\r\n")
      },

      test("IMEI ACK — RCPTOK") {
        val ack = parser.imeiAck(true)
        val str = ack.toString(UTF_8)
        assertTrue(str == "RCPTOK\r\n")
      }
    )
  )
