package com.wayrecall.tracker.domain

import zio.*
import zio.test.*
import zio.json.*
import java.time.Instant

// ============================================================
// Тесты Command domain — иерархия команд, CommandStatus,
// CommandResult, PendingCommand, CommandCapability, CommandDelivery
// ============================================================

object CommandSpec extends ZIOSpecDefault:

  def spec = suite("Command domain")(
    commandDeliverySuite,
    commandTypesSuite,
    commandStatusSuite,
    commandResultSuite,
    pendingCommandSuite,
    commandCapabilitySuite
  ) @@ TestAspect.timeout(60.seconds)

  // ─── CommandDelivery enum ───

  val commandDeliverySuite = suite("CommandDelivery")(
    test("3 канала доставки") {
      assertTrue(CommandDelivery.values.length == 3)
    },

    test("JSON roundtrip — все варианты") {
      val results = CommandDelivery.values.map { d =>
        val json = d.toJson
        json.fromJson[CommandDelivery] == Right(d)
      }
      assertTrue(results.forall(identity))
    }
  )

  // ─── Типы команд ───

  val commandTypesSuite = suite("типы команд")(
    test("RebootCommand — JSON roundtrip") {
      val cmd = RebootCommand("cmd-1", "867730050000001", Instant.parse("2024-01-15T10:00:00Z"))
      val json = cmd.toJson
      val decoded = json.fromJson[RebootCommand]
      assertTrue(decoded == Right(cmd))
    },

    test("SetIntervalCommand — intervalSeconds") {
      val cmd = SetIntervalCommand("cmd-2", "imei", Instant.now(), intervalSeconds = 30)
      assertTrue(cmd.intervalSeconds == 30, cmd.priority == 0)
    },

    test("RequestPositionCommand — delivery Auto по умолчанию") {
      val cmd = RequestPositionCommand("cmd-3", "imei", Instant.now())
      assertTrue(cmd.delivery == CommandDelivery.Auto)
    },

    test("SetOutputCommand — priority 1 по умолчанию (блокировка двигателя)") {
      val cmd = SetOutputCommand("cmd-4", "imei", Instant.now(), outputIndex = 0, enabled = true)
      assertTrue(cmd.priority == 1, cmd.outputIndex == 0, cmd.enabled)
    },

    test("CustomCommand — commandText сохраняется") {
      val cmd = CustomCommand("cmd-5", "imei", Instant.now(), commandText = "setparam 1001:10")
      assertTrue(cmd.commandText == "setparam 1001:10")
    },

    test("SetParameterCommand — paramId и paramValue") {
      val cmd = SetParameterCommand("cmd-6", "imei", Instant.now(), paramId = 1001, paramValue = "30")
      assertTrue(cmd.paramId == 1001, cmd.paramValue == "30")
    },

    test("PasswordCommand — priority 1 (auth перед IOSwitch)") {
      val cmd = PasswordCommand("cmd-7", "imei", Instant.now(), password = "secret")
      assertTrue(cmd.priority == 1, cmd.password == "secret")
    },

    test("DeviceConfigCommand — configText") {
      val cmd = DeviceConfigCommand("cmd-8", "imei", Instant.now(), configText = "SET:GPS:INTERVAL=10")
      assertTrue(cmd.configText == "SET:GPS:INTERVAL=10")
    },

    test("ChangeServerCommand — serverHost + serverPort, delivery SMS") {
      val cmd = ChangeServerCommand("cmd-9", "imei", Instant.now(), "gps.new-server.com", 5001)
      assertTrue(
        cmd.serverHost == "gps.new-server.com",
        cmd.serverPort == 5001,
        cmd.delivery == CommandDelivery.Sms
      )
    },

    test("polymorphic Command JSON — все типы через sealed trait") {
      val now = Instant.parse("2024-01-15T10:00:00Z")
      val commands: List[Command] = List(
        RebootCommand("1", "i1", now),
        SetIntervalCommand("2", "i2", now, 30),
        RequestPositionCommand("3", "i3", now),
        SetOutputCommand("4", "i4", now, 0, true),
        CustomCommand("5", "i5", now, "test"),
        SetParameterCommand("6", "i6", now, 1001, "v"),
        PasswordCommand("7", "i7", now, "pw"),
        DeviceConfigCommand("8", "i8", now, "cfg"),
        ChangeServerCommand("9", "i9", now, "host", 5001)
      )
      val results = commands.map { cmd =>
        val json = cmd.toJson
        json.fromJson[Command].isRight
      }
      assertTrue(results.forall(identity), commands.length == 9)
    }
  )

  // ─── CommandStatus enum ───

  val commandStatusSuite = suite("CommandStatus")(
    test("все 10 статусов") {
      assertTrue(CommandStatus.values.length == 10)
    },

    test("жизненный цикл команды — все шаги") {
      val statuses = CommandStatus.values.map(_.toString).toSet
      assertTrue(
        statuses.contains("Pending"),
        statuses.contains("Queued"),
        statuses.contains("Sent"),
        statuses.contains("Acked"),
        statuses.contains("Executed"),
        statuses.contains("Failed"),
        statuses.contains("Timeout"),
        statuses.contains("Rejected"),
        statuses.contains("Cancelled"),
        statuses.contains("Expired")
      )
    },

    test("JSON roundtrip") {
      val results = CommandStatus.values.map { s =>
        val json = s.toJson
        json.fromJson[CommandStatus] == Right(s)
      }
      assertTrue(results.forall(identity))
    }
  )

  // ─── CommandResult ───

  val commandResultSuite = suite("CommandResult")(
    test("JSON roundtrip — success") {
      val cr = CommandResult("cmd-1", "imei", CommandStatus.Executed, Some("ok"), Some("response data"), Instant.parse("2024-01-15T10:00:00Z"))
      val json = cr.toJson
      val decoded = json.fromJson[CommandResult]
      assertTrue(decoded == Right(cr))
    },

    test("JSON roundtrip — failure") {
      val cr = CommandResult("cmd-2", "imei", CommandStatus.Failed, Some("protocol error"), None, Instant.now())
      val json = cr.toJson
      assertTrue(json.fromJson[CommandResult].isRight)
    }
  )

  // ─── PendingCommand ───

  val pendingCommandSuite = suite("PendingCommand")(
    test("дефолтные значения — retryCount 0, maxRetries 3") {
      val now = Instant.now()
      val cmd = RebootCommand("c1", "imei", now)
      val pending = PendingCommand(cmd, now)
      assertTrue(
        pending.retryCount == 0,
        pending.maxRetries == 3,
        pending.lastAttemptAt.isEmpty,
        pending.lastError.isEmpty
      )
    },

    test("JSON roundtrip") {
      val now = Instant.parse("2024-01-15T10:00:00Z")
      val cmd = SetOutputCommand("c2", "imei", now, 0, true)
      val pending = PendingCommand(cmd, now, retryCount = 2, lastError = Some("timeout"))
      val json = pending.toJson
      val decoded = json.fromJson[PendingCommand]
      assertTrue(decoded == Right(pending))
    }
  )

  // ─── CommandCapability ───

  val commandCapabilitySuite = suite("CommandCapability")(
    test("Teltonika — поддерживает TCP и SMS") {
      val cap = CommandCapability.Teltonika
      assertTrue(
        cap.supportedCommands.contains("reboot"),
        cap.supportedCommands.contains("setInterval"),
        cap.supportedCommands.contains("setOutput"),
        cap.tcpCommands.nonEmpty,
        cap.smsCommands.nonEmpty,
        cap.maxOutputs == 2,
        !cap.requiresAuth
      )
    },

    test("NavTelecom — requiresAuth = true") {
      val cap = CommandCapability.NavTelecom
      assertTrue(
        cap.requiresAuth,
        cap.tcpCommands.contains("setOutput"),
        cap.tcpCommands.contains("password"),
        cap.smsCommands.isEmpty,
        cap.maxOutputs == 4
      )
    },

    test("Galileosky — SMS only") {
      val cap = CommandCapability.Galileosky
      assertTrue(
        cap.tcpCommands.isEmpty,
        cap.smsCommands.contains("setOutput"),
        cap.maxOutputs == 2
      )
    },

    test("ReceiveOnly — нет команд") {
      val cap = CommandCapability.ReceiveOnly
      assertTrue(
        cap.supportedCommands.isEmpty,
        cap.tcpCommands.isEmpty,
        cap.smsCommands.isEmpty
      )
    },

    test("forProtocol — teltonika возвращает Teltonika") {
      assertTrue(CommandCapability.forProtocol("teltonika") eq CommandCapability.Teltonika)
    },

    test("forProtocol — unknown возвращает ReceiveOnly") {
      assertTrue(CommandCapability.forProtocol("some-new-proto") eq CommandCapability.ReceiveOnly)
    },

    test("forProtocol — case insensitive: TELTONIKA") {
      // телтоника с caps — проверяем .toLowerCase в match
      val cap = CommandCapability.forProtocol("TELTONIKA")
      assertTrue(cap eq CommandCapability.Teltonika)
    },

    test("forProtocol — wialon-binary → Wialon") {
      val cap = CommandCapability.forProtocol("wialon-binary")
      assertTrue(cap eq CommandCapability.Wialon)
    },

    test("allTcpCapable — 5 протоколов") {
      assertTrue(CommandCapability.allTcpCapable.length == 5)
    },

    test("allSmsCapable — 4 протокола") {
      assertTrue(CommandCapability.allSmsCapable.length == 4)
    },

    test("DTM — только binary IOSwitch") {
      val cap = CommandCapability.Dtm
      assertTrue(
        cap.supportedCommands == Set("setOutput"),
        cap.tcpCommands == Set("setOutput"),
        cap.smsCommands.isEmpty,
        cap.maxOutputs == 2
      )
    },

    test("Ruptela — широкий набор команд") {
      val cap = CommandCapability.Ruptela
      assertTrue(
        cap.supportedCommands.contains("reboot"),
        cap.supportedCommands.contains("deviceConfig"),
        cap.tcpCommands.nonEmpty,
        cap.smsCommands.nonEmpty
      )
    }
  )
