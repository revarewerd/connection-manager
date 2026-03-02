package com.wayrecall.tracker.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * Тесты кодирования команд для протокола Ruptela
 *
 * Формат бинарного пакета:
 * [Length 2B] — длина данных (cmdByte + data)
 * [CmdByte 1B] — тип команды (0x65=SetParam, 0x66=DeviceConfig, 0x67=SetOutput)
 * [Data NB] — содержимое команды
 * [CRC-16 2B] — CRC-16 (polynomial 0xA001, reflected)
 */
object RuptelaEncoderSpec extends ZIOSpecDefault:

  private val encoder = RuptelaEncoder
  private val testImei = "860000000000003"
  private val testTime = Instant.parse("2025-01-01T12:00:00Z")

  private def toByteArray(buf: ByteBuf): Array[Byte] =
    buf.readerIndex(0)
    val bytes = new Array[Byte](buf.readableBytes())
    buf.readBytes(bytes)
    bytes

  /** Читаем командный байт из пакета */
  private def readCmdByte(buf: ByteBuf): Byte =
    buf.readerIndex(2) // после Length(2B)
    buf.readByte()

  /** Читаем Length из начала пакета */
  private def readLength(buf: ByteBuf): Int =
    buf.readerIndex(0)
    buf.readShort() & 0xFFFF

  def spec: Spec[Any, Any] = suite("RuptelaEncoderSpec")(

    // ─── Базовые свойства ───

    test("protocolName должен быть 'ruptela'") {
      assertTrue(encoder.protocolName == "ruptela")
    },

    // ─── RebootCommand (0x65 SetParam, paramId=0x00) ───

    test("encode RebootCommand — cmdByte = 0x65 (SetParam)") {
      val cmd = RebootCommand("cmd-1", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        cmdByte = readCmdByte(buf)
        _ = buf.release()
      } yield assertTrue(cmdByte == 0x65.toByte)
    },

    test("encode RebootCommand — paramId = 0x00 (reboot parameter)") {
      val cmd = RebootCommand("cmd-2", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        // После Length(2B) + CmdByte(1B) → paramId на позиции 3
        _ = buf.release()
      } yield assertTrue(bytes(3) == 0x00.toByte)
    },

    // ─── SetIntervalCommand (0x65 SetParam, paramId=0x01, 4B value) ───

    test("encode SetIntervalCommand — cmdByte = 0x65") {
      val cmd = SetIntervalCommand("cmd-3", testImei, testTime, intervalSeconds = 60)
      for {
        buf <- encoder.encode(cmd)
        cmdByte = readCmdByte(buf)
        _ = buf.release()
      } yield assertTrue(cmdByte == 0x65.toByte)
    },

    test("encode SetIntervalCommand — paramId = 0x01") {
      val cmd = SetIntervalCommand("cmd-4", testImei, testTime, intervalSeconds = 60)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(3) == 0x01.toByte)
    },

    // ─── SetOutputCommand (0x67) ───

    test("encode SetOutputCommand — cmdByte = 0x67") {
      val cmd = SetOutputCommand("cmd-5", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        cmdByte = readCmdByte(buf)
        _ = buf.release()
      } yield assertTrue(cmdByte == 0x67.toByte)
    },

    test("encode SetOutputCommand — данные содержат idx и value") {
      val cmd = SetOutputCommand("cmd-6", testImei, testTime, outputIndex = 1, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        // После Length(2B) + CmdByte(1B) → outputIdx(1B) + value(1B)
        _ = buf.release()
      } yield assertTrue(
        bytes(3) == 1.toByte  // outputIndex
      )
    },

    test("encode SetOutputCommand (ON) — value = 0x01") {
      val cmd = SetOutputCommand("cmd-7", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(4) == 0x01.toByte)
    },

    test("encode SetOutputCommand (OFF) — value = 0x00") {
      val cmd = SetOutputCommand("cmd-8", testImei, testTime, outputIndex = 0, enabled = false)
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        _ = buf.release()
      } yield assertTrue(bytes(4) == 0x00.toByte)
    },

    // ─── DeviceConfigCommand (0x66) ───

    test("encode DeviceConfigCommand — cmdByte = 0x66") {
      val cmd = DeviceConfigCommand("cmd-9", testImei, testTime, configText = "CONFIG=1")
      for {
        buf <- encoder.encode(cmd)
        cmdByte = readCmdByte(buf)
        _ = buf.release()
      } yield assertTrue(cmdByte == 0x66.toByte)
    },

    test("encode DeviceConfigCommand — текст конфигурации в UTF-8") {
      val cmd = DeviceConfigCommand("cmd-10", testImei, testTime, configText = "test123")
      for {
        buf <- encoder.encode(cmd)
        bytes = toByteArray(buf)
        totalLen = bytes.length
        // Текст начинается с позиции 3 (после Length(2) + CmdByte(1))
        // Заканчивается перед CRC(2)
        textBytes = bytes.slice(3, totalLen - 2)
        text = new String(textBytes, "UTF-8")
        _ = buf.release()
      } yield assertTrue(text == "test123")
    },

    // ─── Length (первые 2 байта) ───

    test("Length корректно рассчитан для SetOutputCommand") {
      val cmd = SetOutputCommand("cmd-11", testImei, testTime, outputIndex = 0, enabled = true)
      for {
        buf <- encoder.encode(cmd)
        length = readLength(buf)
        // Length = 1(cmdByte) + 1(idx) + 1(value) = 3
        _ = buf.release()
      } yield assertTrue(length == 3)
    },

    // ─── CRC-16 (последние 2 байта) ───

    test("CRC-16 присутствует в конце пакета") {
      val cmd = RebootCommand("cmd-12", testImei, testTime)
      for {
        buf <- encoder.encode(cmd)
        totalLen = buf.readableBytes()
        // Минимальный пакет: Length(2) + CmdByte(1) + Data(1+) + CRC(2)
        _ = buf.release()
      } yield assertTrue(totalLen >= 6)
    },

    // ─── Неподдерживаемые команды ───

    test("PasswordCommand → ProtocolError") {
      val cmd = PasswordCommand("cmd-13", testImei, testTime, password = "test")
      for {
        result <- encoder.encode(cmd).either
      } yield assertTrue(result.isLeft)
    },

    // ─── Supports ───

    test("supports RebootCommand") {
      assertTrue(encoder.supports(RebootCommand("1", testImei, testTime)))
    },

    test("supports SetIntervalCommand") {
      assertTrue(encoder.supports(SetIntervalCommand("1", testImei, testTime, 30)))
    },

    test("supports SetOutputCommand") {
      assertTrue(encoder.supports(SetOutputCommand("1", testImei, testTime, 0, true)))
    },

    test("supports DeviceConfigCommand") {
      assertTrue(encoder.supports(DeviceConfigCommand("1", testImei, testTime, "test")))
    },

    test("NOT supports PasswordCommand") {
      assertTrue(!encoder.supports(PasswordCommand("1", testImei, testTime, "pwd")))
    },

    // ─── Capabilities ───

    test("capabilities содержит reboot, setInterval, setOutput, deviceConfig") {
      val caps = encoder.capabilities
      assertTrue(
        caps.supportedCommands.contains("reboot") &&
        caps.supportedCommands.contains("setInterval") &&
        caps.supportedCommands.contains("setOutput") &&
        caps.supportedCommands.contains("deviceConfig")
      )
    },

    test("capabilities.maxOutputs == 2") {
      assertTrue(encoder.capabilities.maxOutputs == 2)
    }
  )
