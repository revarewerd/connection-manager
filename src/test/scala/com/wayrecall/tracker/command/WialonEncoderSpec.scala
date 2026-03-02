package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты кодирования команд для протокола Wialon IPS
 *
 * Формат текстового пакета: #M#{commandText}\r\n
 * Поддерживается ТОЛЬКО CustomCommand
 */
object WialonEncoderSpec extends ZIOSpecDefault:

  private val encoder = WialonEncoder
  private val testImei = "860000000000004"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  /** Извлекаем текст из ByteBuf как UTF-8 строку */
  private def bufToString(buf: ByteBuf): String =
    buf.readerIndex(0)
    val bytes = new Array[Byte](buf.readableBytes())
    buf.readBytes(bytes)
    new String(bytes, "UTF-8")

  def spec: Spec[Any, Any] = suite("WialonEncoderSpec")(

    // ─── Базовые свойства ───

    test("protocolName должен быть 'wialon'") {
      assertTrue(encoder.protocolName == "wialon")
    },

    // ─── CustomCommand ───

    test("encode CustomCommand → '#M#text\\r\\n'") {
      val cmd = CustomCommand(
        commandId = "cmd-1",
        imei = testImei,
        timestamp = testTime,
        commandText = "hello"
      )
      for {
        buf <- encoder.encode(cmd)
        text = bufToString(buf)
        _ = buf.release()
      } yield assertTrue(text == "#M#hello\r\n")
    },

    test("CustomCommand с пустым текстом → '#M#\\r\\n'") {
      val cmd = CustomCommand("cmd-2", testImei, testTime, commandText = "")
      for {
        buf <- encoder.encode(cmd)
        text = bufToString(buf)
        _ = buf.release()
      } yield assertTrue(text == "#M#\r\n")
    },

    test("CustomCommand с длинным текстом") {
      val longText = "A" * 1000
      val cmd = CustomCommand("cmd-3", testImei, testTime, commandText = longText)
      for {
        buf <- encoder.encode(cmd)
        text = bufToString(buf)
        _ = buf.release()
      } yield assertTrue(
        text.startsWith("#M#") &&
        text.endsWith("\r\n") &&
        text.length == 3 + 1000 + 2 // "#M#" + text + "\r\n"
      )
    },

    test("CustomCommand с кириллицей") {
      val cmd = CustomCommand("cmd-4", testImei, testTime, commandText = "привет")
      for {
        buf <- encoder.encode(cmd)
        text = bufToString(buf)
        _ = buf.release()
      } yield assertTrue(text == "#M#привет\r\n")
    },

    test("CustomCommand со спецсимволами") {
      val cmd = CustomCommand("cmd-5", testImei, testTime, commandText = "key=value&foo=bar")
      for {
        buf <- encoder.encode(cmd)
        text = bufToString(buf)
        _ = buf.release()
      } yield assertTrue(text == "#M#key=value&foo=bar\r\n")
    },

    // ─── Неподдерживаемые команды ───

    test("RebootCommand → ProtocolError") {
      val cmd = RebootCommand("cmd-6", testImei, testTime)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("SetIntervalCommand → ProtocolError") {
      val cmd = SetIntervalCommand("cmd-7", testImei, testTime, intervalSeconds = 30)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("SetOutputCommand → ProtocolError") {
      val cmd = SetOutputCommand("cmd-8", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("PasswordCommand → ProtocolError") {
      val cmd = PasswordCommand("cmd-9", testImei, testTime, password = "test")
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    // ─── Supports ───

    test("supports CustomCommand") {
      assertTrue(encoder.supports(CustomCommand("1", testImei, testTime, "test")))
    },

    test("NOT supports RebootCommand") {
      assertTrue(!encoder.supports(RebootCommand("1", testImei, testTime)))
    },

    test("NOT supports SetOutputCommand") {
      assertTrue(!encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)))
    },

    // ─── Capabilities ───

    test("capabilities — только custom") {
      val caps = encoder.capabilities
      assertTrue(caps.supportedCommands == Set("custom"))
    },

    test("capabilities — нет SMS") {
      assertTrue(encoder.capabilities.smsCommands.isEmpty)
    }
  )
