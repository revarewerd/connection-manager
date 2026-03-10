package com.wayrecall.tracker.domain

import zio.*
import zio.test.*
import zio.json.*

// ============================================================
// Тесты Protocol enum, ProtocolError, FilterError,
// RedisError, KafkaError — все ошибочные типы CM domain
// ============================================================

object ProtocolSpec extends ZIOSpecDefault:

  def spec = suite("Protocol domain")(
    protocolEnumSuite,
    protocolErrorSuite,
    filterErrorSuite,
    redisErrorSuite,
    kafkaErrorSuite
  ) @@ TestAspect.timeout(60.seconds)

  val protocolEnumSuite = suite("Protocol enum")(
    test("все 18 протоколов") {
      assertTrue(Protocol.values.length == 18)
    },

    test("основные протоколы присутствуют") {
      val names = Protocol.values.map(_.toString).toSet
      assertTrue(
        names.contains("Teltonika"),
        names.contains("Wialon"),
        names.contains("WialonBinary"),
        names.contains("Ruptela"),
        names.contains("NavTelecom"),
        names.contains("GoSafe"),
        names.contains("Galileosky"),
        names.contains("Unknown")
      )
    },

    test("JSON roundtrip — Protocol") {
      val protocol = Protocol.Teltonika
      val json = protocol.toJson
      val decoded = json.fromJson[Protocol]
      assertTrue(decoded == Right(Protocol.Teltonika))
    },

    test("JSON roundtrip — все протоколы") {
      val results = Protocol.values.map { p =>
        val json = p.toJson
        json.fromJson[Protocol] == Right(p)
      }
      assertTrue(results.forall(identity))
    },

    test("Unknown — для автодетекта") {
      assertTrue(Protocol.Unknown.toString == "Unknown")
    }
  )

  val protocolErrorSuite = suite("ProtocolError")(
    test("InvalidChecksum — сообщение с actual и expected") {
      val err = ProtocolError.InvalidChecksum(actual = 0x1234, expected = 0x5678)
      assertTrue(
        err.message.contains("4660"),
        err.message.contains("22136"),
        err.isInstanceOf[Throwable]
      )
    },

    test("InvalidCodec — byte значение") {
      val err = ProtocolError.InvalidCodec(codec = 0x42.toByte)
      assertTrue(err.message.contains("66")) // 0x42 = 66
    },

    test("ParseError — reason") {
      val err = ProtocolError.ParseError("unexpected end of stream")
      assertTrue(err.message.contains("unexpected end of stream"))
    },

    test("InsufficientData — required vs available") {
      val err = ProtocolError.InsufficientData(required = 100, available = 42)
      assertTrue(
        err.message.contains("100"),
        err.message.contains("42")
      )
    },

    test("InvalidImei — imei в сообщении") {
      val err = ProtocolError.InvalidImei("12345")
      assertTrue(err.message.contains("12345"))
    },

    test("UnknownDevice — imei") {
      val err = ProtocolError.UnknownDevice("867730050123456")
      assertTrue(err.message.contains("867730050123456"))
    },

    test("UnsupportedProtocol") {
      val err = ProtocolError.UnsupportedProtocol("egts")
      assertTrue(err.message.contains("egts"))
    },

    test("ProtocolDetectionFailed") {
      val err = ProtocolError.ProtocolDetectionFailed("no magic bytes matched")
      assertTrue(err.message.contains("no magic bytes"))
    },

    test("все ProtocolError extends Throwable") {
      val errors: List[ProtocolError] = List(
        ProtocolError.InvalidChecksum(1, 2),
        ProtocolError.InvalidCodec(0),
        ProtocolError.ParseError("test"),
        ProtocolError.InsufficientData(10, 5),
        ProtocolError.InvalidImei("x"),
        ProtocolError.UnknownDevice("y"),
        ProtocolError.UnsupportedProtocol("z"),
        ProtocolError.ProtocolDetectionFailed("w")
      )
      assertTrue(errors.forall(_.isInstanceOf[Throwable]))
    }
  )

  val filterErrorSuite = suite("FilterError")(
    test("ExcessiveSpeed — speed и maxAllowed") {
      val err = FilterError.ExcessiveSpeed(180, 130)
      assertTrue(
        err.message.contains("180"),
        err.message.contains("130")
      )
    },

    test("InvalidCoordinates — lat/lon вне диапазона") {
      val err = FilterError.InvalidCoordinates(91.0, 200.0)
      assertTrue(
        err.message.contains("91"),
        err.message.contains("200")
      )
    },

    test("FutureTimestamp") {
      val err = FilterError.FutureTimestamp(9999999999L, 1000000000L)
      assertTrue(err.message.contains("future"))
    },

    test("Teleportation — distance и maxAllowed") {
      val err = FilterError.Teleportation(50000.0, 10000.0)
      assertTrue(
        err.message.contains("50000"),
        err.message.contains("10000")
      )
    }
  )

  val redisErrorSuite = suite("RedisError")(
    test("ConnectionFailed") {
      val err = RedisError.ConnectionFailed("connection refused")
      assertTrue(err.message.contains("connection refused"))
    },

    test("OperationFailed") {
      val err = RedisError.OperationFailed("READONLY")
      assertTrue(err.message.contains("READONLY"))
    },

    test("DecodingFailed") {
      val err = RedisError.DecodingFailed("invalid JSON")
      assertTrue(err.message.contains("invalid JSON"))
    }
  )

  val kafkaErrorSuite = suite("KafkaError")(
    test("ProducerError") {
      val err = KafkaError.ProducerError("topic does not exist")
      assertTrue(err.message.contains("topic does not exist"))
    },

    test("SerializationError") {
      val err = KafkaError.SerializationError("cannot encode GpsPoint")
      assertTrue(err.message.contains("cannot encode"))
    }
  )
