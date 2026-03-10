package com.wayrecall.tracker.domain

import zio.*
import zio.test.*
import zio.json.*
import java.time.Instant

// ============================================================
// Тесты GpsPoint, GeoMath, DeviceData, VehicleConfig,
// GpsRawPoint, GpsEventMessage, DisconnectReason, DeviceStatus,
// UnknownDeviceEvent, UnknownGpsPoint, ConnectionInfo, DeviceEvent
// ============================================================

object GpsPointSpec extends ZIOSpecDefault:

  def spec = suite("GpsPoint domain")(
    geoMathSuite,
    gpsPointSuite,
    vehicleConfigSuite,
    deviceDataSuite,
    deviceDataRedisHashSuite,
    deviceDataPositionHashSuite,
    gpsRawPointSuite,
    gpsEventMessageSuite,
    disconnectReasonSuite,
    deviceStatusSuite,
    unknownDeviceEventSuite,
    connectionInfoSuite,
    deviceEventSuite
  ) @@ TestAspect.timeout(60.seconds)

  // ─── GeoMath Haversine ───

  val geoMathSuite = suite("GeoMath.haversineDistance")(
    test("одинаковые точки — расстояние 0") {
      val d = GeoMath.haversineDistance(55.7558, 37.6173, 55.7558, 37.6173)
      assertTrue(d == 0.0)
    },

    test("Москва → Санкт-Петербург ~630 км") {
      val d = GeoMath.haversineDistance(55.7558, 37.6173, 59.9343, 30.3351)
      val inRange = d > 600_000 && d < 660_000 // ≈634 км
      assertTrue(inRange)
    },

    test("экватор — 1 градус долготы ≈111 км") {
      val d = GeoMath.haversineDistance(0.0, 0.0, 0.0, 1.0)
      val inRange = d > 110_000 && d < 112_000
      assertTrue(inRange)
    },

    test("полюс — 1 градус широты ≈111 км") {
      val d = GeoMath.haversineDistance(89.0, 0.0, 90.0, 0.0)
      val inRange = d > 110_000 && d < 112_000
      assertTrue(inRange)
    },

    test("коммутативность — порядок точек не влияет") {
      val d1 = GeoMath.haversineDistance(55.7558, 37.6173, 59.9343, 30.3351)
      val d2 = GeoMath.haversineDistance(59.9343, 30.3351, 55.7558, 37.6173)
      val diff = Math.abs(d1 - d2)
      val isClose = diff < 0.001
      assertTrue(isClose)
    },

    test("антиподы — ~20000 км") {
      val d = GeoMath.haversineDistance(0.0, 0.0, 0.0, 180.0)
      val inRange = d > 19_000_000 && d < 21_000_000
      assertTrue(inRange)
    }
  )

  // ─── GpsPoint ───

  val gpsPointSuite = suite("GpsPoint")(
    test("создание и поля") {
      val p = GpsPoint(1L, 55.75, 37.62, 150, 60, 180, 12, 1700000000000L)
      assertTrue(
        p.vehicleId == 1L,
        p.latitude == 55.75,
        p.longitude == 37.62,
        p.altitude == 150,
        p.speed == 60,
        p.angle == 180,
        p.satellites == 12,
        p.timestamp == 1700000000000L
      )
    },

    test("distanceTo — расстояние между точками") {
      val p1 = GpsPoint(1L, 55.7558, 37.6173, 0, 0, 0, 10, 0L)
      val p2 = GpsPoint(1L, 55.7600, 37.6200, 0, 0, 0, 10, 0L)
      val d = p1.distanceTo(p2)
      val inRange = d > 0 && d < 1000 // несколько сотен метров
      assertTrue(inRange)
    },

    test("distance extension method — алиас") {
      val p1 = GpsPoint(1L, 55.75, 37.61, 0, 0, 0, 10, 0L)
      val p2 = GpsPoint(1L, 55.76, 37.62, 0, 0, 0, 10, 0L)
      assertTrue(p1.distance(p2) == p1.distanceTo(p2))
    },

    test("JSON roundtrip") {
      val p = GpsPoint(42L, 55.75, 37.62, 100, 80, 270, 8, 1700000000000L)
      val json = p.toJson
      val decoded = json.fromJson[GpsPoint]
      assertTrue(decoded == Right(p))
    }
  )

  // ─── VehicleConfig ───

  val vehicleConfigSuite = suite("VehicleConfig")(
    test("needsRulesCheck — true если геозоны") {
      val vc = VehicleConfig(1L, "111", hasGeozones = true)
      assertTrue(vc.needsRulesCheck)
    },

    test("needsRulesCheck — true если правила скорости") {
      val vc = VehicleConfig(1L, "111", hasSpeedRules = true)
      assertTrue(vc.needsRulesCheck)
    },

    test("needsRulesCheck — false если нет ни геозон ни скорости") {
      val vc = VehicleConfig(1L, "111")
      assertTrue(!vc.needsRulesCheck)
    },

    test("JSON roundtrip") {
      val vc = VehicleConfig(1L, "867730050000001", "Газель", hasRetranslation = true, retranslationTargets = List("wialon-42"))
      val json = vc.toJson
      val decoded = json.fromJson[VehicleConfig]
      assertTrue(decoded == Right(vc))
    }
  )

  // ─── DeviceData ───

  val deviceDataSuite = suite("DeviceData")(
    test("needsRulesCheck — делегирует флаги") {
      val dd = DeviceData(1L, 100L, hasGeozones = true)
      assertTrue(dd.needsRulesCheck)
    },

    test("previousPosition — None если нет lat/lon") {
      val dd = DeviceData(1L, 100L)
      assertTrue(dd.previousPosition.isEmpty)
    },

    test("previousPosition — Some если lat+lon заданы") {
      val dd = DeviceData(1L, 100L, lat = Some(55.75), lon = Some(37.62), speed = Some(60), course = Some(180))
      val prev = dd.previousPosition
      assertTrue(
        prev.isDefined,
        prev.get.latitude == 55.75,
        prev.get.longitude == 37.62,
        prev.get.speed == 60,
        prev.get.angle == 180
      )
    },

    test("previousPosition — дефолтные значения при отсутствии полей") {
      val dd = DeviceData(1L, 100L, lat = Some(55.75), lon = Some(37.62))
      val prev = dd.previousPosition.get
      assertTrue(
        prev.altitude == 0,
        prev.speed == 0,
        prev.angle == 0,
        prev.satellites == 0
      )
    },

    test("toVehicleConfig — конвертация") {
      val dd = DeviceData(1L, 100L, "Test", hasGeozones = true, retranslationTargets = List("wl"))
      val vc = dd.toVehicleConfig
      assertTrue(
        vc.organizationId == 100L,
        vc.name == "Test",
        vc.hasGeozones,
        vc.retranslationTargets == List("wl")
      )
    }
  )

  // ─── DeviceData fromRedisHash ───

  val deviceDataRedisHashSuite = suite("DeviceData.fromRedisHash")(
    test("минимальный хэш — vehicleId + organizationId") {
      val hash = Map("vehicleId" -> "42", "organizationId" -> "100")
      val result = DeviceData.fromRedisHash(hash)
      assertTrue(
        result.isDefined,
        result.get.vehicleId == 42L,
        result.get.organizationId == 100L,
        result.get.name == ""
      )
    },

    test("пустой хэш — None") {
      val result = DeviceData.fromRedisHash(Map.empty)
      assertTrue(result.isEmpty)
    },

    test("невалидный vehicleId — None") {
      val hash = Map("vehicleId" -> "abc", "organizationId" -> "100")
      assertTrue(DeviceData.fromRedisHash(hash).isEmpty)
    },

    test("полный хэш — все поля") {
      val hash = Map(
        "vehicleId" -> "42", "organizationId" -> "100",
        "name" -> "Газель", "speedLimit" -> "90",
        "hasGeozones" -> "true", "hasSpeedRules" -> "false",
        "hasRetranslation" -> "true", "retranslationTargets" -> "wialon-42,webhook-7",
        "fuelTankVolume" -> "80.5",
        "lat" -> "55.75", "lon" -> "37.62", "speed" -> "60",
        "course" -> "180", "altitude" -> "150", "satellites" -> "12",
        "time" -> "2024-01-15T10:30:00Z", "isMoving" -> "true",
        "instanceId" -> "cm-teltonika-1", "protocol" -> "teltonika",
        "connectedAt" -> "2024-01-15T10:00:00Z", "remoteAddress" -> "1.2.3.4:5678"
      )
      val dd = DeviceData.fromRedisHash(hash).get
      assertTrue(
        dd.name == "Газель",
        dd.speedLimit == Some(90),
        dd.hasGeozones,
        dd.hasRetranslation,
        dd.retranslationTargets == List("wialon-42", "webhook-7"),
        dd.fuelTankVolume == Some(80.5),
        dd.lat == Some(55.75),
        dd.lon == Some(37.62),
        dd.speed == Some(60),
        dd.isMoving == Some(true),
        dd.instanceId == Some("cm-teltonika-1"),
        dd.protocol == Some("teltonika")
      )
    },

    test("boolean поля — только 'true' == true") {
      val hash = Map(
        "vehicleId" -> "1", "organizationId" -> "1",
        "hasGeozones" -> "false", "hasSpeedRules" -> "yes",
        "isMoving" -> "false"
      )
      val dd = DeviceData.fromRedisHash(hash).get
      assertTrue(
        !dd.hasGeozones,
        !dd.hasSpeedRules, // "yes" != "true"
        dd.isMoving == Some(false)
      )
    }
  )

  // ─── DeviceData.positionToHash ───

  val deviceDataPositionHashSuite = suite("DeviceData.positionToHash")(
    test("генерирует правильные поля") {
      val point = GpsPoint(1L, 55.75, 37.62, 150, 60, 180, 12, 1700000000000L)
      val now = Instant.parse("2024-01-15T12:00:00Z")
      val hash = DeviceData.positionToHash(point, isMoving = true, now)
      assertTrue(
        hash("lat") == "55.75",
        hash("lon") == "37.62",
        hash("speed") == "60",
        hash("course") == "180",
        hash("altitude") == "150",
        hash("satellites") == "12",
        hash("isMoving") == "true",
        hash("lastActivity") == now.toString
      )
    },

    test("connectionToHash — содержит все connection поля") {
      val now = Instant.parse("2024-01-15T12:00:00Z")
      val hash = DeviceData.connectionToHash("cm-1", "teltonika", now, "1.2.3.4:5678")
      assertTrue(
        hash("instanceId") == "cm-1",
        hash("protocol") == "teltonika",
        hash("connectedAt") == now.toString,
        hash("remoteAddress") == "1.2.3.4:5678"
      )
    },

    test("connectionFieldNames — 4 поля для HDEL") {
      assertTrue(DeviceData.connectionFieldNames.length == 4)
    }
  )

  // ─── GpsRawPoint ───

  val gpsRawPointSuite = suite("GpsRawPoint")(
    test("toValidated — присваивает vehicleId") {
      val raw = GpsRawPoint("867730050000001", 55.75, 37.62, 150, 60, 180, 12, 1700000000000L)
      val validated = raw.toValidated(42L)
      assertTrue(
        validated.vehicleId == 42L,
        validated.latitude == raw.latitude,
        validated.speed == raw.speed
      )
    },

    test("distanceTo — расстояние от raw к GpsPoint") {
      val raw = GpsRawPoint("i", 55.75, 37.62, 0, 0, 0, 10, 0L)
      val p = GpsPoint(1L, 55.76, 37.63, 0, 0, 0, 10, 0L)
      val d = raw.distanceTo(p)
      val inRange = d > 0 && d < 2000
      assertTrue(inRange)
    }
  )

  // ─── GpsEventMessage ───

  val gpsEventMessageSuite = suite("GpsEventMessage")(
    test("JSON roundtrip") {
      val msg = GpsEventMessage(
        vehicleId = 1L, organizationId = 100L, imei = "867730050000001",
        latitude = 55.75, longitude = 37.62, altitude = 150, speed = 60,
        course = 180, satellites = 12,
        deviceTime = 1700000000000L, serverTime = 1700000001000L,
        hasGeozones = true, hasRetranslation = true,
        retranslationTargets = Some(List("wialon-42")),
        protocol = "teltonika", instanceId = "cm-1"
      )
      val json = msg.toJson
      val decoded = json.fromJson[GpsEventMessage]
      assertTrue(decoded == Right(msg))
    },

    test("дефолтные значения") {
      val msg = GpsEventMessage(1L, 100L, "imei", 55.0, 37.0, 0, 0, 0, 0, 0L, 0L)
      assertTrue(
        !msg.hasGeozones,
        !msg.hasSpeedRules,
        !msg.hasRetranslation,
        msg.retranslationTargets.isEmpty,
        msg.isMoving,
        msg.isValid
      )
    }
  )

  // ─── DisconnectReason ───

  val disconnectReasonSuite = suite("DisconnectReason")(
    test("все 9 причин") {
      assertTrue(DisconnectReason.values.length == 9)
    },

    test("JSON roundtrip — все причины") {
      val results = DisconnectReason.values.map { r =>
        val json = r.toJson
        json.fromJson[DisconnectReason] == Right(r)
      }
      assertTrue(results.forall(identity))
    },

    test("важные причины присутствуют") {
      val names = DisconnectReason.values.map(_.toString).toSet
      assertTrue(
        names.contains("GracefulClose"),
        names.contains("IdleTimeout"),
        names.contains("ServerShutdown"),
        names.contains("AdminDisconnect")
      )
    }
  )

  // ─── DeviceStatus ───

  val deviceStatusSuite = suite("DeviceStatus")(
    test("JSON roundtrip") {
      val status = DeviceStatus(
        "867730050000001", 42L, isOnline = false, lastSeen = 1700000000000L,
        lastLatitude = Some(55.75), lastLongitude = Some(37.62),
        disconnectReason = Some(DisconnectReason.IdleTimeout),
        sessionDurationMs = Some(3600000L)
      )
      val json = status.toJson
      val decoded = json.fromJson[DeviceStatus]
      assertTrue(decoded == Right(status))
    }
  )

  // ─── UnknownDeviceEvent ───

  val unknownDeviceEventSuite = suite("UnknownDeviceEvent")(
    test("JSON roundtrip") {
      val evt = UnknownDeviceEvent("unknown-imei", "teltonika", "1.2.3.4", 5001, 1700000000000L, 3)
      val json = evt.toJson
      val decoded = json.fromJson[UnknownDeviceEvent]
      assertTrue(decoded == Right(evt))
    },

    test("дефолт connectionAttempt = 1") {
      val evt = UnknownDeviceEvent("imei", "wialon", "ip", 5002, 0L)
      assertTrue(evt.connectionAttempt == 1)
    }
  )

  // ─── ConnectionInfo ───

  val connectionInfoSuite = suite("ConnectionInfo")(
    test("JSON roundtrip") {
      val ci = ConnectionInfo("867730050000001", 1700000000000L, "1.2.3.4", 5001)
      val json = ci.toJson
      val decoded = json.fromJson[ConnectionInfo]
      assertTrue(decoded == Right(ci))
    }
  )

  // ─── DeviceEvent ───

  val deviceEventSuite = suite("DeviceEvent")(
    test("JSON roundtrip — config_updated") {
      val vc = VehicleConfig(100L, "867730050000001", "Test", hasGeozones = true)
      val evt = DeviceEvent("867730050000001", 42L, 100L, "config_updated", Some(vc), 1700000000000L)
      val json = evt.toJson
      val decoded = json.fromJson[DeviceEvent]
      assertTrue(decoded == Right(evt))
    },

    test("JSON roundtrip — config_deleted без vehicleConfig") {
      val evt = DeviceEvent("867730050000001", 42L, 100L, "config_deleted", None, 1700000000000L)
      val json = evt.toJson
      val decoded = json.fromJson[DeviceEvent]
      assertTrue(decoded == Right(evt))
    }
  )
