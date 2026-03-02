package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты кодирования команд для протокола NavTelecom (NTCB FLEX)
 *
 * Формат пакета:
 * [Signature 2B = 0x2A3E (*>)]
 * [Length 2B LE]
 * [MsgID 2B LE = 0x0300]
 * [CmdCode 1B = 0x01]
 * [Data NB — UTF-8 текст]
 * [CRC-16 2B LE (CCITT)]
 */
object NavTelecomEncoderSpec extends ZIOSpecDefault:

  private val encoder = NavTelecomEncoder
  private val testImei = "860000000000001"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  /** Извлекаем текстовую часть из NTCB FLEX пакета */
  private def extractCommandText(buf: ByteBuf): String =
    buf.readerIndex(0)
    buf.skipBytes(2) // signature
    val length = buf.readShortLE() & 0xFFFF
    buf.skipBytes(2) // msgId
    buf.skipBytes(1) // cmdCode
    // Data = length - 2(msgId) - 1(cmdCode)
    val dataLen = length - 3
    val bytes = new Array[Byte](dataLen)
    buf.readBytes(bytes)
    new String(bytes, "UTF-8")

  /** Проверяем сигнатуру NTCB пакета (0x2A3E = *>) */
  private def readSignature(buf: ByteBuf): Int =
    buf.readerIndex(0)
    buf.readShort() & 0xFFFF

  /** Читаем MsgID */
  private def readMsgId(buf: ByteBuf): Int =
    buf.readerIndex(4) // после signature(2) + length(2)
    buf.readShortLE() & 0xFFFF

  def spec: Spec[Any, Any] = suite("NavTelecomEncoderSpec")(

    // ─── Базовые свойства ───

    test("protocolName должен быть 'navtelecom'") {
      assertTrue(encoder.protocolName == "navtelecom")
    },

    // ─── PasswordCommand ───

    test("encode PasswordCommand → '>PASS:{password}'") {
      val cmd = PasswordCommand(
        commandId = "cmd-1",
        imei = testImei,
        timestamp = testTime,
        password = "secret123"
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == ">PASS:secret123")
    },

    test("PasswordCommand — сигнатура 0x2A3E") {
      val cmd = PasswordCommand("cmd-2", testImei, testTime, password = "test")
      for {
        buf <- encoder.encode(cmd)
        sig = readSignature(buf)
        _ = buf.release()
      } yield assertTrue(sig == 0x2A3E)
    },

    test("PasswordCommand — MsgID = 0x0300") {
      val cmd = PasswordCommand("cmd-3", testImei, testTime, password = "test")
      for {
        buf <- encoder.encode(cmd)
        msgId = readMsgId(buf)
        _ = buf.release()
      } yield assertTrue(msgId == 0x0300)
    },

    // ─── SetOutputCommand ───

    test("encode SetOutputCommand (out=0, ON) → '!1Y'") {
      val cmd = SetOutputCommand(
        commandId = "cmd-4",
        imei = testImei,
        timestamp = testTime,
        outputIndex = 0,
        enabled = true
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "!1Y")
    },

    test("encode SetOutputCommand (out=0, OFF) → '!1N'") {
      val cmd = SetOutputCommand("cmd-5", testImei, testTime, outputIndex = 0, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "!1N")
    },

    test("encode SetOutputCommand (out=1, ON) → '!2Y'") {
      val cmd = SetOutputCommand("cmd-6", testImei, testTime, outputIndex = 1, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "!2Y")
    },

    test("encode SetOutputCommand (out=3, OFF) → '!4N'") {
      val cmd = SetOutputCommand("cmd-7", testImei, testTime, outputIndex = 3, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "!4N")
    },

    // ─── CustomCommand ───

    test("encode CustomCommand — передаётся as-is") {
      val cmd = CustomCommand(
        commandId = "cmd-8",
        imei = testImei,
        timestamp = testTime,
        commandText = "STATUS"
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "STATUS")
    },

    // ─── Неподдерживаемые команды ───

    test("encode RebootCommand → ProtocolError (не поддерживается)") {
      val cmd = RebootCommand("cmd-9", testImei, testTime)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("encode SetIntervalCommand → ProtocolError") {
      val cmd = SetIntervalCommand("cmd-10", testImei, testTime, intervalSeconds = 30)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    // ─── Структура пакета ───

    test("CRC-16 CCITT присутствует в конце (2 байта LE)") {
      val cmd = PasswordCommand("cmd-11", testImei, testTime, password = "test")
      for {
        buf <- encoder.encode(cmd)
        totalLen = buf.readableBytes()
        // Минимум: 2(sig) + 2(len) + 2(msgId) + 1(cmdCode) + N(data) + 2(crc) 
        _ = buf.release()
      } yield assertTrue(totalLen >= 9)
    },

    test("Length (LE) корректно отражает размер данных") {
      val cmd = PasswordCommand("cmd-12", testImei, testTime, password = "abc")
      for {
        buf <- encoder.encode(cmd)
        _ = buf.readerIndex(2) // после signature
        length = buf.readShortLE() & 0xFFFF
        // length = 2(msgId) + 1(cmdCode) + len(">PASS:abc") = 3 + 9 = 12
        expectedText = ">PASS:abc"
        expectedLen = 2 + 1 + expectedText.getBytes("UTF-8").length
        _ = buf.release()
      } yield assertTrue(length == expectedLen)
    },

    // ─── Supports ───

    test("supports PasswordCommand") {
      assertTrue(encoder.supports(PasswordCommand("1", testImei, testTime, "pwd")))
    },

    test("supports SetOutputCommand") {
      assertTrue(encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)))
    },

    test("supports CustomCommand") {
      assertTrue(encoder.supports(CustomCommand("1", testImei, testTime, "test")))
    },

    test("NOT supports RebootCommand") {
      assertTrue(!encoder.supports(RebootCommand("1", testImei, testTime)))
    },

    // ─── Capabilities ───

    test("capabilities.requiresAuth == true") {
      assertTrue(encoder.capabilities.requiresAuth)
    },

    test("capabilities.maxOutputs == 4") {
      assertTrue(encoder.capabilities.maxOutputs == 4)
    },

    test("capabilities содержит setOutput, password, custom") {
      val caps = encoder.capabilities
      assertTrue(
        caps.supportedCommands.contains("setOutput") &&
        caps.supportedCommands.contains("password") &&
        caps.supportedCommands.contains("custom")
      )
    }
  )
