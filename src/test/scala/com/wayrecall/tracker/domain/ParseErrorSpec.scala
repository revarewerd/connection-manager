package com.wayrecall.tracker.domain

import zio.*
import zio.test.*

// ============================================================
// Тесты ParseError — 10 case classes ошибок парсинга
// Проверяем: message формат, protocol поле, exhaustive match
// ============================================================

object ParseErrorSpec extends ZIOSpecDefault:

  def spec = suite("ParseError")(
    messageSuite,
    protocolFieldSuite,
    patternMatchSuite
  ) @@ TestAspect.timeout(60.seconds)

  val messageSuite = suite("message формат")(
    test("InsufficientData — содержит expected и actual") {
      val err = ParseError.InsufficientData("Teltonika", expected = 100, actual = 42)
      assertTrue(
        err.message.contains("100"),
        err.message.contains("42"),
        err.protocol == "Teltonika"
      )
    },

    test("InvalidField — имя поля и значение") {
      val err = ParseError.InvalidField("Ruptela", field = "latitude", value = "out of range")
      assertTrue(
        err.message.contains("latitude"),
        err.message.contains("out of range")
      )
    },

    test("InvalidImei — imei в сообщении") {
      val err = ParseError.InvalidImei("Wialon", imei = "ABC", reason = "invalid format")
      assertTrue(
        err.message.contains("ABC"),
        err.protocol == "Wialon"
      )
    },

    test("CrcMismatch — actual и expected") {
      val err = ParseError.CrcMismatch("NavTelecom", actual = 0x1234, expected = 0x5678)
      assertTrue(
        err.message.contains("1234") || err.message.contains("4660"),
        err.message.contains("5678") || err.message.contains("22136")
      )
    },

    test("UnsupportedCodec — codecId") {
      val err = ParseError.UnsupportedCodec("Teltonika", codecId = 0xFF)
      val msg = err.message
      val hasCodecInfo = msg.contains("ff") || msg.contains("FF") || msg.contains("255")
      assertTrue(hasCodecInfo)
    },

    test("RecordCountMismatch — expected и actual") {
      val err = ParseError.RecordCountMismatch("Teltonika", expected = 10, actual = 7)
      assertTrue(
        err.message.contains("10"),
        err.message.contains("7")
      )
    },

    test("InvalidSignature — hex байты") {
      val err = ParseError.InvalidSignature("GoSafe", expectedSignature = Some("000F"))
      assertTrue(err.message.nonEmpty)
    },

    test("UnknownPacketType — typeId") {
      val err = ParseError.UnknownPacketType("Galileosky", packetType = "42")
      assertTrue(err.message.contains("42"))
    },

    test("InvalidCoordinates — lat/lon") {
      val err = ParseError.InvalidCoordinates("Ruptela", lat = 91.5, lon = 200.0, reason = "out of range")
      assertTrue(
        err.message.contains("91.5"),
        err.message.contains("200")
      )
    },

    test("GenericParseError — произвольное сообщение") {
      val err = ParseError.GenericParseError("Unknown", "something went wrong")
      assertTrue(err.message.contains("something went wrong"))
    }
  )

  val protocolFieldSuite = suite("protocol поле")(
    test("все ошибки содержат поле protocol") {
      val errors: List[ParseError] = List(
        ParseError.InsufficientData("Teltonika", 10, 5),
        ParseError.InvalidField("Wialon", "lat", "bad"),
        ParseError.InvalidImei("Ruptela", "xxx", "invalid"),
        ParseError.CrcMismatch("NavTelecom", 1, 2),
        ParseError.UnsupportedCodec("Teltonika", 99),
        ParseError.RecordCountMismatch("GoSafe", 5, 3),
        ParseError.InvalidSignature("Galileosky", None),
        ParseError.UnknownPacketType("Concox", "1"),
        ParseError.InvalidCoordinates("TK102", 0.0, 0.0, "zero coordinates"),
        ParseError.GenericParseError("Unknown", "err")
      )
      assertTrue(errors.forall(_.protocol.nonEmpty))
    },

    test("protocol сохраняет переданное значение") {
      val err = ParseError.InvalidImei("MyProto", "imei123", "test")
      assertTrue(err.protocol == "MyProto")
    }
  )

  val patternMatchSuite = suite("pattern match")(
    test("exhaustive match по всем 10 типам") {
      val err: ParseError = ParseError.GenericParseError("test", "msg")
      val result = err match
        case _: ParseError.InsufficientData     => 1
        case _: ParseError.InvalidField         => 2
        case _: ParseError.InvalidImei          => 3
        case _: ParseError.CrcMismatch          => 4
        case _: ParseError.UnsupportedCodec     => 5
        case _: ParseError.RecordCountMismatch  => 6
        case _: ParseError.InvalidSignature     => 7
        case _: ParseError.UnknownPacketType    => 8
        case _: ParseError.InvalidCoordinates   => 9
        case _: ParseError.GenericParseError    => 10
      assertTrue(result == 10)
    }
  )
