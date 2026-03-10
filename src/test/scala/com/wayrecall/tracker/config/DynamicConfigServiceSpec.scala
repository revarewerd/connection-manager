package com.wayrecall.tracker.config

import zio.*
import zio.test.*
import zio.json.*

// ============================================================
// Тесты FilterConfig и DynamicConfigService
// FilterConfig — чистый case class c JSON кодеком
// DynamicConfigService — Ref-based, тестируется без Redis
// ============================================================

object DynamicConfigServiceSpec extends ZIOSpecDefault:

  def spec = suite("DynamicConfigService")(
    filterConfigSuite,
    filterConfigJsonSuite,
    refBasedServiceSuite
  ) @@ TestAspect.timeout(60.seconds)

  // ─── FilterConfig domain ───

  val filterConfigSuite = suite("FilterConfig")(
    test("дефолтные значения") {
      val fc = FilterConfig()
      assertTrue(
        fc.deadReckoningMaxSpeedKmh == 300,
        fc.deadReckoningMaxJumpMeters == 1000,
        fc.deadReckoningMaxJumpSeconds == 1,
        fc.stationaryMinDistanceMeters == 20,
        fc.stationaryMinSpeedKmh == 2
      )
    },

    test("кастомные значения") {
      val fc = FilterConfig(
        deadReckoningMaxSpeedKmh = 200,
        deadReckoningMaxJumpMeters = 500,
        deadReckoningMaxJumpSeconds = 3,
        stationaryMinDistanceMeters = 50,
        stationaryMinSpeedKmh = 5
      )
      assertTrue(
        fc.deadReckoningMaxSpeedKmh == 200,
        fc.stationaryMinDistanceMeters == 50
      )
    }
  )

  val filterConfigJsonSuite = suite("FilterConfig JSON")(
    test("JSON roundtrip — дефолтный") {
      val fc = FilterConfig()
      val json = fc.toJson
      val decoded = json.fromJson[FilterConfig]
      assertTrue(decoded == Right(fc))
    },

    test("JSON roundtrip — кастомные значения") {
      val fc = FilterConfig(200, 500, 3, 50, 5)
      val json = fc.toJson
      val decoded = json.fromJson[FilterConfig]
      assertTrue(decoded == Right(fc))
    }
  )

  // ─── Ref-based behavior (без Redis) ───

  val refBasedServiceSuite = suite("Ref-based get/set")(
    test("getFilterConfig — возвращает начальное значение") {
      for
        ref     <- Ref.make(FilterConfig())
        config  <- ref.get
      yield assertTrue(config.deadReckoningMaxSpeedKmh == 300)
    },

    test("set обновляет значение") {
      for
        ref  <- Ref.make(FilterConfig())
        _    <- ref.set(FilterConfig(deadReckoningMaxSpeedKmh = 150))
        cfg  <- ref.get
      yield assertTrue(cfg.deadReckoningMaxSpeedKmh == 150)
    },

    test("несколько обновлений — последнее побеждает") {
      for
        ref <- Ref.make(FilterConfig())
        _   <- ref.set(FilterConfig(stationaryMinSpeedKmh = 5))
        _   <- ref.set(FilterConfig(stationaryMinSpeedKmh = 10))
        cfg <- ref.get
      yield assertTrue(cfg.stationaryMinSpeedKmh == 10)
    },

    test("concurrent read — не блокируется") {
      for
        ref     <- Ref.make(FilterConfig())
        configs <- ZIO.collectAllPar(List.fill(100)(ref.get))
      yield assertTrue(configs.length == 100, configs.forall(_.deadReckoningMaxSpeedKmh == 300))
    }
  )
