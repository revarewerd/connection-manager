package com.wayrecall.tracker.domain

import zio.*
import zio.test.*
import zio.json.*

// ============================================================
// Тесты VehicleInfo — case class для данных о ТС
// ============================================================

object VehicleInfoSpec extends ZIOSpecDefault:

  def spec = suite("VehicleInfo")(
    test("создание и поля") {
      val vi = VehicleInfo(42L, "867730050000001", Some("Газель АА123"), "teltonika", true)
      assertTrue(
        vi.id == 42L,
        vi.imei == "867730050000001",
        vi.name == Some("Газель АА123"),
        vi.deviceType == "teltonika",
        vi.isActive
      )
    },

    test("name может быть None") {
      val vi = VehicleInfo(1L, "imei", None, "ruptela", false)
      assertTrue(vi.name.isEmpty, !vi.isActive)
    },

    test("JSON roundtrip") {
      val vi = VehicleInfo(42L, "867730050000001", Some("Test"), "teltonika", true)
      val json = vi.toJson
      val decoded = json.fromJson[VehicleInfo]
      assertTrue(decoded == Right(vi))
    },

    test("JSON roundtrip с name=None") {
      val vi = VehicleInfo(1L, "imei", None, "wialon", false)
      val json = vi.toJson
      val decoded = json.fromJson[VehicleInfo]
      assertTrue(decoded == Right(vi))
    }
  ) @@ TestAspect.timeout(30.seconds)
