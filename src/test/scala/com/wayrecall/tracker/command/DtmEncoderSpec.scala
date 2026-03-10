package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты кодирования команд для протокола DTM (IOSwitch)
 *
 * Формат бинарного пакета:
 * [0x7B] — начало
 * [0x02] — длина данных
 * [0xFF] — маркер IOSwitch
 * [XOR checksum] — XOR всех байтов данных (0xFF ^ idx ^ val)
 * [0x09 + outputIdx] — адрес выхода
 * [0x00 | 0x01] — значение (OFF / ON)
 * [0x7D] — конец
 */
object DtmEncoderSpec extends ZIOSpecDefault:

  private val encoder = DtmEncoder
  private val testImei = "860000000000002"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  /** Извлекаем все байты из ByteBuf как массив */
  private def toByteArray(buf: ByteBuf): Array[Byte] =
    buf.readerIndex(0)
    val bytes = new Array[Byte](buf.readableBytes())
    buf.readBytes(bytes)
    bytes

  def spec: Spec[Any, Any] = suite("DtmEncoderSpec")(

    // ─── Базовые свойства ───

    test("protocolName должен быть 'dtm'") {
      assertTrue(encoder.protocolName == "dtm")
    },

    // ─── SetOutputCommand ON ───

    test("encode SetOutputCommand (out=0, ON) — структура пакета") {
      val cmd = SetOutputCommand(
        commandId = "cmd-1",
        imei = testImei,
        timestamp = testTime,
        outputIndex = 0,
        enabled = true
      )
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield {
        assertTrue(
          bytes(0) == 0x7B.toByte,     // Начало пакета
          bytes(2) == 0xFF.toByte,      // Маркер IOSwitch
          bytes(bytes.length - 1) == 0x7D.toByte  // Конец пакета
        )
      }
    },

    test("encode SetOutputCommand (out=0, ON) — адрес выхода 0x09") {
      val cmd = SetOutputCommand("cmd-2", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
        // Адрес выхода = 0x09 + outputIndex
      } yield assertTrue(bytes(4) == 0x09.toByte)
    },

    test("encode SetOutputCommand (out=0, ON) — значение 0x01") {
      val cmd = SetOutputCommand("cmd-3", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(5) == 0x01.toByte)
    },

    // ─── SetOutputCommand OFF ───

    test("encode SetOutputCommand (out=0, OFF) — значение 0x00") {
      val cmd = SetOutputCommand("cmd-4", testImei, testTime, outputIndex = 0, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(5) == 0x00.toByte)
    },

    // ─── Разные выходы ───

    test("encode SetOutputCommand (out=1) — адрес 0x0A") {
      val cmd = SetOutputCommand("cmd-5", testImei, testTime, outputIndex = 1, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(4) == 0x0A.toByte) // 0x09 + 1
    },

    // ─── XOR Checksum ───

    test("XOR checksum корректно рассчитан (out=0, ON)") {
      val cmd = SetOutputCommand("cmd-6", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
        // checksum = 0xFF ^ (0x09+0) ^ 0x01 = 0xFF ^ 0x09 ^ 0x01 = 0xF7
        expectedChk = (0xFF ^ 0x09 ^ 0x01).toByte
      } yield assertTrue(bytes(3) == expectedChk)
    },

    test("XOR checksum для out=1, OFF") {
      val cmd = SetOutputCommand("cmd-7", testImei, testTime, outputIndex = 1, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
        // checksum = 0xFF ^ (0x09+1) ^ 0x00 = 0xFF ^ 0x0A ^ 0x00 = 0xF5
        expectedChk = (0xFF ^ 0x0A ^ 0x00).toByte
      } yield assertTrue(bytes(3) == expectedChk)
    },

    // ─── Длина пакета ───

    test("Длина пакета всегда 7 байт") {
      val cmd = SetOutputCommand("cmd-8", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        len = buf.readableBytes()
        _ = buf.release()
      } yield assertTrue(len == 7)
    },

    // ─── Неподдерживаемые команды ───

    test("RebootCommand → ProtocolError") {
      val cmd = RebootCommand("cmd-9", testImei, testTime)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("SetIntervalCommand → ProtocolError") {
      val cmd = SetIntervalCommand("cmd-10", testImei, testTime, intervalSeconds = 30)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("CustomCommand → ProtocolError") {
      val cmd = CustomCommand("cmd-11", testImei, testTime, commandText = "test")
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    test("RequestPositionCommand → ProtocolError") {
      val cmd = RequestPositionCommand("cmd-12", testImei, testTime)
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    // ─── Supports ───

    test("supports SetOutputCommand") {
      assertTrue(encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)))
    },

    test("NOT supports RebootCommand") {
      assertTrue(!encoder.supports(RebootCommand("1", testImei, testTime)))
    },

    test("NOT supports CustomCommand") {
      assertTrue(!encoder.supports(CustomCommand("1", testImei, testTime, "x")))
    },

    // ─── Capabilities ───

    test("capabilities — только setOutput") {
      val caps = encoder.capabilities
      assertTrue(
        caps.supportedCommands == Set("setOutput"),
        caps.maxOutputs == 2
      )
    }
  )
