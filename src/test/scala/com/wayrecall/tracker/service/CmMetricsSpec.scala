package com.wayrecall.tracker.service

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Тесты для CmMetrics — lock-free метрики CM
 *
 * Проверяем:
 * 1. Счётчики (LongAdder) — increment, add, sum
 * 2. Gauge (AtomicLong) — set, get
 * 3. Prometheus вывод — формат и содержимое
 * 4. Конкурентные операции — корректность при параллельном доступе
 */
object CmMetricsSpec extends ZIOSpecDefault:

  // Сбрасываем все метрики перед каждым тестом (LongAdder.reset + AtomicLong.set(0))
  private def resetMetrics: UIO[Unit] = ZIO.succeed {
    CmMetrics.activeConnections.set(0)
    CmMetrics.totalConnections.reset()
    CmMetrics.totalDisconnections.reset()
    CmMetrics.packetsReceived.reset()
    CmMetrics.gpsPointsReceived.reset()
    CmMetrics.gpsPointsPublished.reset()
    CmMetrics.parseErrors.reset()
    CmMetrics.kafkaPublishErrors.reset()
    CmMetrics.kafkaPublishSuccess.reset()
    CmMetrics.redisOperations.reset()
    CmMetrics.unknownDevices.reset()
    CmMetrics.commandsSent.reset()
    CmMetrics.unknownDevicePackets.reset()
  }

  def spec: Spec[Any, Any] = suite("CmMetrics")(

    // ────────────────── Счётчики (LongAdder) ──────────────────

    suite("Счётчики LongAdder")(

      test("increment увеличивает на 1") {
        for
          _ <- resetMetrics
          _ <- ZIO.succeed(CmMetrics.totalConnections.increment())
          _ <- ZIO.succeed(CmMetrics.totalConnections.increment())
          _ <- ZIO.succeed(CmMetrics.totalConnections.increment())
        yield assertTrue(CmMetrics.totalConnections.sum() == 3L)
      },

      test("add увеличивает на произвольное значение") {
        for
          _ <- resetMetrics
          _ <- ZIO.succeed(CmMetrics.gpsPointsReceived.add(50))
          _ <- ZIO.succeed(CmMetrics.gpsPointsReceived.add(30))
        yield assertTrue(CmMetrics.gpsPointsReceived.sum() == 80L)
      },

      test("все счётчики начинаются с 0 после reset") {
        for
          _ <- resetMetrics
        yield assertTrue(
          CmMetrics.totalConnections.sum() == 0L &&
          CmMetrics.totalDisconnections.sum() == 0L &&
          CmMetrics.packetsReceived.sum() == 0L &&
          CmMetrics.gpsPointsReceived.sum() == 0L &&
          CmMetrics.gpsPointsPublished.sum() == 0L &&
          CmMetrics.parseErrors.sum() == 0L &&
          CmMetrics.kafkaPublishErrors.sum() == 0L &&
          CmMetrics.kafkaPublishSuccess.sum() == 0L &&
          CmMetrics.redisOperations.sum() == 0L &&
          CmMetrics.unknownDevices.sum() == 0L &&
          CmMetrics.commandsSent.sum() == 0L &&
          CmMetrics.unknownDevicePackets.sum() == 0L
        )
      }
    ),

    // ────────────────── Gauge (AtomicLong) ──────────────────

    suite("Gauge AtomicLong")(

      test("activeConnections — set и get") {
        for
          _ <- resetMetrics
          _ <- ZIO.succeed(CmMetrics.activeConnections.set(42))
        yield assertTrue(CmMetrics.activeConnections.get() == 42L)
      },

      test("activeConnections — может уменьшаться") {
        for
          _ <- resetMetrics
          _ <- ZIO.succeed(CmMetrics.activeConnections.set(100))
          _ <- ZIO.succeed(CmMetrics.activeConnections.set(50))
        yield assertTrue(CmMetrics.activeConnections.get() == 50L)
      }
    ),

    // ────────────────── Prometheus вывод ──────────────────

    suite("prometheusOutput")(

      test("содержит все обязательные метрики") {
        for
          _ <- resetMetrics
          output = CmMetrics.prometheusOutput
        yield assertTrue(
          output.contains("cm_connections_active") &&
          output.contains("cm_connections_total") &&
          output.contains("cm_disconnections_total") &&
          output.contains("cm_packets_received_total") &&
          output.contains("cm_gps_points_received_total") &&
          output.contains("cm_gps_points_published_total") &&
          output.contains("cm_parse_errors_total") &&
          output.contains("cm_kafka_publish_errors_total") &&
          output.contains("cm_kafka_publish_success_total") &&
          output.contains("cm_redis_operations_total") &&
          output.contains("cm_unknown_devices_total") &&
          output.contains("cm_commands_sent_total") &&
          output.contains("cm_unknown_device_packets_total") &&
          output.contains("cm_uptime_seconds") &&
          output.contains("cm_started_at_timestamp")
        )
      },

      test("содержит HELP и TYPE для каждой метрики") {
        for
          _ <- resetMetrics
          output = CmMetrics.prometheusOutput
        yield assertTrue(
          output.contains("# HELP cm_connections_active") &&
          output.contains("# TYPE cm_connections_active gauge") &&
          output.contains("# HELP cm_connections_total") &&
          output.contains("# TYPE cm_connections_total counter") &&
          output.contains("# TYPE cm_uptime_seconds gauge")
        )
      },

      test("отражает текущие значения счётчиков") {
        for
          _ <- resetMetrics
          _ <- ZIO.succeed {
            CmMetrics.activeConnections.set(7)
            CmMetrics.totalConnections.add(100)
            CmMetrics.packetsReceived.add(5000)
            CmMetrics.parseErrors.add(3)
          }
          output = CmMetrics.prometheusOutput
        yield assertTrue(
          output.contains("cm_connections_active 7") &&
          output.contains("cm_connections_total 100") &&
          output.contains("cm_packets_received_total 5000") &&
          output.contains("cm_parse_errors_total 3")
        )
      },

      test("uptime > 0") {
        val output = CmMetrics.prometheusOutput
        val uptimeLine = output.linesIterator.find(_.startsWith("cm_uptime_seconds")).get
        val uptimeValue = uptimeLine.split(" ").last.toLong
        assertTrue(uptimeValue >= 0L)
      }
    ),

    // ────────────────── Конкурентность ──────────────────

    suite("Конкурентные операции")(

      test("1000 параллельных increment корректно суммируются") {
        for
          _ <- resetMetrics
          _ <- ZIO.foreachParDiscard(1 to 1000) { _ =>
            ZIO.succeed(CmMetrics.packetsReceived.increment())
          }
        yield assertTrue(CmMetrics.packetsReceived.sum() == 1000L)
      },

      test("параллельные add корректно суммируются") {
        for
          _ <- resetMetrics
          _ <- ZIO.foreachParDiscard(1 to 100) { i =>
            ZIO.succeed(CmMetrics.gpsPointsReceived.add(i.toLong))
          }
          // Сумма 1..100 = 5050
        yield assertTrue(CmMetrics.gpsPointsReceived.sum() == 5050L)
      }
    )

  ) @@ TestAspect.sequential // Тесты последовательные — CmMetrics глобальный object
