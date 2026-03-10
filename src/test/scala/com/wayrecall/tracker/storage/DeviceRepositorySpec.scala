package com.wayrecall.tracker.storage

import zio.*
import zio.test.*
import com.wayrecall.tracker.domain.VehicleInfo

// ============================================================
// Тесты DeviceRepository — Dummy реализация для unit тестов
// Dummy содержит 3 устройства: 2 активных (teltonika, wialon),
// 1 неактивное (ruptela disabled)
// ============================================================

object DeviceRepositorySpec extends ZIOSpecDefault:

  val layer: ULayer[DeviceRepository] = DeviceRepository.dummy

  def spec = suite("DeviceRepository.Dummy")(
    findByImeiSuite,
    findAllEnabledSuite,
    isEnabledSuite
  ) @@ TestAspect.timeout(60.seconds)

  val findByImeiSuite = suite("findByImei")(
    test("активное устройство — возвращает Some") {
      for
        result <- DeviceRepository.findByImei("860719020025346")
      yield assertTrue(
        result.isDefined,
        result.get.id == 1L,
        result.get.deviceType == "teltonika",
        result.get.isActive
      )
    }.provide(layer),

    test("второе активное устройство — wialon") {
      for
        result <- DeviceRepository.findByImei("860719020025347")
      yield assertTrue(
        result.isDefined,
        result.get.deviceType == "wialon"
      )
    }.provide(layer),

    test("неактивное устройство — возвращает None (фильтруется)") {
      for
        result <- DeviceRepository.findByImei("860719020025348")
      yield assertTrue(result.isEmpty)
    }.provide(layer),

    test("несуществующий IMEI — None") {
      for
        result <- DeviceRepository.findByImei("000000000000000")
      yield assertTrue(result.isEmpty)
    }.provide(layer)
  )

  val findAllEnabledSuite = suite("findAllEnabled")(
    test("возвращает только активные устройства") {
      for
        all <- DeviceRepository.findAllEnabled
      yield assertTrue(
        all.length == 2,
        all.forall(_.isActive)
      )
    }.provide(layer),

    test("содержит teltonika и wialon") {
      for
        all <- DeviceRepository.findAllEnabled
      yield
        val types = all.map(_.deviceType).toSet
        assertTrue(
          types.contains("teltonika"),
          types.contains("wialon"),
          !types.contains("ruptela")
        )
    }.provide(layer)
  )

  val isEnabledSuite = suite("isEnabled")(
    test("активное устройство — true") {
      for
        result <- DeviceRepository.isEnabled("860719020025346")
      yield assertTrue(result)
    }.provide(layer),

    test("неактивное устройство — false") {
      for
        result <- DeviceRepository.isEnabled("860719020025348")
      yield assertTrue(!result)
    }.provide(layer),

    test("несуществующее устройство — false") {
      for
        result <- DeviceRepository.isEnabled("nonexistent")
      yield assertTrue(!result)
    }.provide(layer)
  )
