package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты для ReceiveOnlyEncoder и фабрики CommandEncoder.forProtocol
 *
 * ReceiveOnlyEncoder — заглушка для протоколов без TCP команд.
 * Всегда возвращает UnsupportedProtocol.
 *
 * CommandEncoder.forProtocol — фабрика для получения энкодера по имени протокола.
 */
object CommandEncoderFactorySpec extends ZIOSpecDefault:

  private val testImei = "860000000000005"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  def spec: Spec[Any, Any] = suite("CommandEncoderFactorySpec")(

    // ═══════════════════════════════════════════════════════
    // ReceiveOnlyEncoder
    // ═══════════════════════════════════════════════════════

    suite("ReceiveOnlyEncoder")(

      test("encode любой команды → UnsupportedProtocol") {
        val encoder = ReceiveOnlyEncoder("gosafe")
        val cmd = RebootCommand("cmd-1", testImei, testTime)
        for {
          result <- encoder.encode(cmd).either
        } yield assertTrue(result.isLeft)
      },

      test("supports всегда false") {
        val encoder = ReceiveOnlyEncoder("concox")
        assertTrue(
          !encoder.supports(RebootCommand("1", testImei, testTime)) &&
          !encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)) &&
          !encoder.supports(CustomCommand("1", testImei, testTime, "test")) &&
          !encoder.supports(SetIntervalCommand("1", testImei, testTime, 30))
        )
      },

      test("protocolName сохраняет переданное имя") {
        val encoder = ReceiveOnlyEncoder("my-protocol")
        assertTrue(encoder.protocolName == "my-protocol")
      },

      test("capabilities — пустые наборы команд") {
        val encoder = ReceiveOnlyEncoder("gosafe")
        val caps = encoder.capabilities
        assertTrue(
          caps.supportedCommands.isEmpty &&
          caps.tcpCommands.isEmpty &&
          caps.smsCommands.isEmpty
        )
      },

      test("UnsupportedProtocol содержит имя протокола") {
        val encoder = ReceiveOnlyEncoder("tk102")
        val cmd = CustomCommand("1", testImei, testTime, "test")
        for {
          result <- encoder.encode(cmd).either
        } yield result match
          case Left(ProtocolError.UnsupportedProtocol(msg)) =>
            assertTrue(msg.contains("tk102"))
          case _ =>
            assertTrue(false)
      }
    ),

    // ═══════════════════════════════════════════════════════
    // CommandEncoder.forProtocol — фабрика
    // ═══════════════════════════════════════════════════════

    suite("CommandEncoder.forProtocol")(

      // ─── Протоколы с энкодерами ───

      test("teltonika → TeltonikaEncoder") {
        val enc = CommandEncoder.forProtocol("teltonika")
        assertTrue(enc.protocolName == "teltonika")
      },

      test("navtelecom → NavTelecomEncoder") {
        val enc = CommandEncoder.forProtocol("navtelecom")
        assertTrue(enc.protocolName == "navtelecom")
      },

      test("dtm → DtmEncoder") {
        val enc = CommandEncoder.forProtocol("dtm")
        assertTrue(enc.protocolName == "dtm")
      },

      test("ruptela → RuptelaEncoder") {
        val enc = CommandEncoder.forProtocol("ruptela")
        assertTrue(enc.protocolName == "ruptela")
      },

      test("wialon → WialonEncoder") {
        val enc = CommandEncoder.forProtocol("wialon")
        assertTrue(enc.protocolName == "wialon")
      },

      test("wialon-binary → WialonEncoder") {
        val enc = CommandEncoder.forProtocol("wialon-binary")
        assertTrue(enc.protocolName == "wialon")
      },

      // ─── Регистронезависимость ───

      test("TELTONIKA (uppercase) → TeltonikaEncoder") {
        val enc = CommandEncoder.forProtocol("TELTONIKA")
        assertTrue(enc.protocolName == "teltonika")
      },

      test("Navtelecom (mixed case) → NavTelecomEncoder") {
        val enc = CommandEncoder.forProtocol("Navtelecom")
        assertTrue(enc.protocolName == "navtelecom")
      },

      // ─── Receive-only протоколы ───

      test("gosafe → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("gosafe")
        assertTrue(!enc.supports(RebootCommand("1", testImei, testTime)))
      },

      test("concox → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("concox")
        assertTrue(!enc.supports(RebootCommand("1", testImei, testTime)))
      },

      test("tk102 → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("tk102")
        assertTrue(!enc.supports(RebootCommand("1", testImei, testTime)))
      },

      test("tk103 → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("tk103")
        assertTrue(!enc.supports(RebootCommand("1", testImei, testTime)))
      },

      test("adm → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("adm")
        assertTrue(enc.capabilities.supportedCommands.isEmpty)
      },

      test("galileosky → ReceiveOnlyEncoder (TCP = receive-only)") {
        val enc = CommandEncoder.forProtocol("galileosky")
        assertTrue(enc.capabilities.tcpCommands.isEmpty)
      },

      test("неизвестный протокол → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("totally-unknown-protocol")
        for {
          result <- enc.encode(RebootCommand("1", testImei, testTime)).either
        } yield assertTrue(result.isLeft)
      },

      test("пустая строка → ReceiveOnlyEncoder") {
        val enc = CommandEncoder.forProtocol("")
        assertTrue(!enc.supports(RebootCommand("1", testImei, testTime)))
      }
    ),

    // ═══════════════════════════════════════════════════════
    // CommandCapability.forProtocol
    // ═══════════════════════════════════════════════════════

    suite("CommandCapability.forProtocol")(

      test("Teltonika — TCP и SMS команды") {
        val cap = CommandCapability.forProtocol("teltonika")
        assertTrue(
          cap.tcpCommands.nonEmpty &&
          cap.smsCommands.nonEmpty
        )
      },

      test("NavTelecom — requiresAuth = true") {
        val cap = CommandCapability.forProtocol("navtelecom")
        assertTrue(cap.requiresAuth)
      },

      test("Galileosky — только SMS команды") {
        val cap = CommandCapability.forProtocol("galileosky")
        assertTrue(
          cap.tcpCommands.isEmpty &&
          cap.smsCommands.nonEmpty
        )
      },

      test("Arnavi — только SMS (setOutput + changeServer)") {
        val cap = CommandCapability.forProtocol("arnavi")
        assertTrue(
          cap.smsCommands.contains("setOutput") &&
          cap.smsCommands.contains("changeServer") &&
          cap.tcpCommands.isEmpty
        )
      },

      test("Неизвестный → ReceiveOnly capabilities") {
        val cap = CommandCapability.forProtocol("unknown")
        assertTrue(cap.supportedCommands.isEmpty)
      },

      test("allTcpCapable содержит 5 протоколов") {
        assertTrue(CommandCapability.allTcpCapable.length == 5)
      },

      test("allSmsCapable содержит 4 протокола") {
        assertTrue(CommandCapability.allSmsCapable.length == 4)
      }
    )
  )
