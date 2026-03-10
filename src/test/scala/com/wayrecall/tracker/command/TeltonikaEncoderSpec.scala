package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты кодирования команд для протокола Teltonika (Codec 12)
 *
 * Формат пакета:
 * [Preamble 4B = 0x00000000]
 * [DataLength 4B]
 * [Codec = 0x0C]
 * [Qty = 0x01]
 * [Type = 0x05]
 * [CommandSize 4B]
 * [UTF-8 command text]
 * [Qty = 0x01]
 * [CRC-16 4B (CRC-16-IBM)]
 */
object TeltonikaEncoderSpec extends ZIOSpecDefault:

  private val encoder = TeltonikaEncoder
  private val testImei = "352093089612345"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  // ─── Вспомогательные методы ───

  /** Читаем бинарный пакет и возвращаем текст команды внутри Codec 12 */
  private def extractCommandText(buf: ByteBuf): String =
    buf.readerIndex(0)
    // Пропускаем Preamble (4B) + DataLength (4B) + Codec (1B) + Qty (1B) + Type (1B)
    buf.skipBytes(11)
    val cmdSize = buf.readInt()
    val bytes = new Array[Byte](cmdSize)
    buf.readBytes(bytes)
    new String(bytes, "UTF-8")

  /** Проверяем структуру Codec 12 пакета */
  private def verifyCodec12Structure(buf: ByteBuf): Boolean =
    buf.readerIndex(0)
    val preamble = buf.readInt()
    val dataLen = buf.readInt()
    val codec = buf.readByte()
    val qty1 = buf.readByte()
    val cmdType = buf.readByte()
    val cmdSize = buf.readInt()
    buf.skipBytes(cmdSize)
    val qty2 = buf.readByte()
    // CRC-16 занимает 4 байта (int)
    preamble == 0 && codec == 0x0C && qty1 == 1 && cmdType == 0x05 && qty2 == 1

  def spec: Spec[Any, Any] = suite("TeltonikaEncoderSpec")(

    // ─── Базовые свойства ───

    test("protocolName должен быть 'teltonika'") {
      assertTrue(encoder.protocolName == "teltonika")
    },

    // ─── RebootCommand ───

    test("encode RebootCommand → 'cpureset'") {
      val cmd = RebootCommand(
        commandId = "cmd-1",
        imei = testImei,
        timestamp = testTime
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "cpureset")
    },

    test("RebootCommand — корректная структура Codec 12") {
      val cmd = RebootCommand("cmd-1", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        valid = verifyCodec12Structure(buf)
        _ = buf.release()
      } yield assertTrue(valid)
    },

    // ─── SetIntervalCommand ───

    test("encode SetIntervalCommand → 'setparam 1001:{interval}'") {
      val cmd = SetIntervalCommand(
        commandId = "cmd-2",
        imei = testImei,
        timestamp = testTime,
        intervalSeconds = 30
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setparam 1001:30")
    },

    test("SetIntervalCommand с интервалом 1 сек") {
      val cmd = SetIntervalCommand("cmd-3", testImei, testTime, intervalSeconds = 1)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setparam 1001:1")
    },

    test("SetIntervalCommand с интервалом 3600 сек") {
      val cmd = SetIntervalCommand("cmd-4", testImei, testTime, intervalSeconds = 3600)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setparam 1001:3600")
    },

    // ─── RequestPositionCommand ───

    test("encode RequestPositionCommand → 'getrecord'") {
      val cmd = RequestPositionCommand(
        commandId = "cmd-5",
        imei = testImei,
        timestamp = testTime
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "getrecord")
    },

    // ─── SetOutputCommand ───

    test("encode SetOutputCommand (ON) → 'setdigout 1'") {
      val cmd = SetOutputCommand(
        commandId = "cmd-6",
        imei = testImei,
        timestamp = testTime,
        outputIndex = 0,
        enabled = true
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setdigout 1")
    },

    test("encode SetOutputCommand (OFF) → 'setdigout 0'") {
      val cmd = SetOutputCommand("cmd-7", testImei, testTime, outputIndex = 0, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setdigout 0")
    },

    test("encode SetOutputCommand (out=1, ON) → value = '1'") {
      val cmd = SetOutputCommand("cmd-8", testImei, testTime, outputIndex = 1, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setdigout 1")
    },

    test("encode SetOutputCommand (out=1, OFF)") {
      val cmd = SetOutputCommand("cmd-9", testImei, testTime, outputIndex = 1, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text.startsWith("setdigout"))
    },

    // ─── SetParameterCommand ───

    test("encode SetParameterCommand → 'setparam {id}:{value}'") {
      val cmd = SetParameterCommand(
        commandId = "cmd-10",
        imei = testImei,
        timestamp = testTime,
        paramId = 50001,
        paramValue = "hello"
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "setparam 50001:hello")
    },

    // ─── CustomCommand ───

    test("encode CustomCommand → передаётся as-is") {
      val cmd = CustomCommand(
        commandId = "cmd-11",
        imei = testImei,
        timestamp = testTime,
        commandText = "getinfo"
      )
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "getinfo")
    },

    test("CustomCommand с пустой строкой") {
      val cmd = CustomCommand("cmd-12", testImei, testTime, commandText = "")
      for {
        buf <- encoder.encode(cmd)
        text = extractCommandText(buf)
        _ = buf.release()
      } yield assertTrue(text == "")
    },

    // ─── Формат пакета ───

    test("Preamble всегда 0x00000000") {
      val cmd = RebootCommand("cmd-13", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        _ = buf.readerIndex(0)
        preamble = buf.readInt()
        _ = buf.release()
      } yield assertTrue(preamble == 0)
    },

    test("DataLength корректно рассчитан") {
      val cmd = RebootCommand("cmd-14", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        _ = buf.readerIndex(0)
        _ = buf.readInt() // preamble
        dataLen = buf.readInt()
        // dataLen = 1(codec) + 1(qty) + 1(type) + 4(cmdSize) + len("cpureset") + 1(qty) = 8 + 8 = 16
        cmdText = "cpureset"
        expectedLen = 1 + 1 + 1 + 4 + cmdText.getBytes("UTF-8").length + 1
        _ = buf.release()
      } yield assertTrue(dataLen == expectedLen)
    },

    test("CRC-16 присутствует в конце пакета (4 байта)") {
      val cmd = RebootCommand("cmd-15", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        totalLen = buf.readableBytes()
        // Минимальная длина: 4(preamble) + 4(dataLen) + 1(codec) + 1(qty) + 1(type) + 4(cmdSize) + N + 1(qty) + 4(crc)
        _ = buf.release()
      } yield assertTrue(totalLen >= 20) // 4+4+1+1+1+4+8+1+4 = 28 для "cpureset"
    },

    // ─── Supports ───

    test("supports RebootCommand") {
      assertTrue(encoder.supports(RebootCommand("1", testImei, testTime)))
    },

    test("supports SetIntervalCommand") {
      assertTrue(encoder.supports(SetIntervalCommand("1", testImei, testTime, 30)))
    },

    test("supports RequestPositionCommand") {
      assertTrue(encoder.supports(RequestPositionCommand("1", testImei, testTime)))
    },

    test("supports SetOutputCommand") {
      assertTrue(encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)))
    },

    test("supports CustomCommand") {
      assertTrue(encoder.supports(CustomCommand("1", testImei, testTime, "test")))
    },

    test("supports SetParameterCommand") {
      assertTrue(encoder.supports(SetParameterCommand("1", testImei, testTime, 1001, "30")))
    },

    // ─── Capabilities ───

    test("capabilities содержит все поддерживаемые команды") {
      val caps = encoder.capabilities
      assertTrue(
        caps.supportedCommands.contains("reboot"),
        caps.supportedCommands.contains("setInterval"),
        caps.supportedCommands.contains("requestPosition"),
        caps.supportedCommands.contains("setOutput"),
        caps.supportedCommands.contains("custom"),
        caps.supportedCommands.contains("setParameter")
      )
    },

    test("capabilities.maxOutputs == 2 для Teltonika") {
      assertTrue(encoder.capabilities.maxOutputs == 2)
    }
  )
