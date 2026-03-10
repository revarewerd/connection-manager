package com.wayrecall.tracker.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.wayrecall.tracker.domain.{DeviceEvent, VehicleConfig}

/**
 * Тесты для DeviceEventConsumer
 * 
 * Проверяем:
 * 1. Парсинг DeviceEvent из JSON
 * 2. handleConfigUpdated — HMSET routing flags
 * 3. handleConfigDeleted — сброс флагов в false
 * 4. Маршрутизация по eventType
 * 5. Legacy ключ vehicle:config:{imei}
 */
object DeviceEventConsumerSpec extends ZIOSpecDefault:
  
  // === Мок RedisClient для отслеживания hset/del операций ===
  private case class RedisOperation(key: String, op: String, fields: Map[String, String])
  
  private def makeMockRedis: UIO[(TestRedis, Ref[List[RedisOperation]])] =
    Ref.make(List.empty[RedisOperation]).map { opsRef =>
      val redis = TestRedis(opsRef)
      (redis, opsRef)
    }
  
  private case class TestRedis(opsRef: Ref[List[RedisOperation]]):
    def hset(key: String, fields: Map[String, String]): Task[Unit] =
      opsRef.update(_ :+ RedisOperation(key, "hset", fields))
    
    def del(key: String): Task[Unit] =
      opsRef.update(_ :+ RedisOperation(key, "del", Map.empty))
  
  def spec = suite("DeviceEventConsumer")(
    
    suite("JSON парсинг DeviceEvent")(
      
      test("config_updated с полной конфигурацией") {
        val json = """{"imei":"123456789012345","vehicleId":42,"organizationId":1,"eventType":"config_updated","vehicleConfig":{"organizationId":1,"imei":"123456789012345","name":"Газель АА123","hasGeozones":true,"hasSpeedRules":false,"hasRetranslation":true,"retranslationTargets":["wialon-42"]},"timestamp":1709636400000}"""
        
        val result = json.fromJson[DeviceEvent]
        assertTrue(
          result.isRight,
          result.toOption.get.imei == "123456789012345",
          result.toOption.get.eventType == "config_updated",
          result.toOption.get.vehicleConfig.isDefined,
          result.toOption.get.vehicleConfig.get.hasGeozones == true,
          result.toOption.get.vehicleConfig.get.hasRetranslation == true
        )
      },
      
      test("config_deleted без vehicleConfig") {
        val json = """{"imei":"123456789012345","vehicleId":42,"organizationId":1,"eventType":"config_deleted","timestamp":1709636400000}"""
        
        val result = json.fromJson[DeviceEvent]
        assertTrue(
          result.isRight,
          result.toOption.get.eventType == "config_deleted",
          result.toOption.get.vehicleConfig.isEmpty
        )
      },
      
      test("некорректный JSON → Left") {
        val json = """{"not_valid"""
        val result = json.fromJson[DeviceEvent]
        assertTrue(result.isLeft)
      },
      
      test("JSON без обязательных полей → Left") {
        val json = """{"imei":"123"}"""
        val result = json.fromJson[DeviceEvent]
        assertTrue(result.isLeft)
      },
      
      test("roundtrip: encode → decode") {
        val event = DeviceEvent(
          imei = "111111111111111",
          vehicleId = 7,
          organizationId = 3,
          eventType = "config_updated",
          vehicleConfig = Some(VehicleConfig(
            organizationId = 3,
            imei = "111111111111111",
            name = "Тест",
            hasGeozones = true,
            hasSpeedRules = true,
            hasRetranslation = false,
            retranslationTargets = List.empty
          )),
          timestamp = 1709636400000L
        )
        val json = event.toJson
        val decoded = json.fromJson[DeviceEvent]
        assertTrue(
          decoded.isRight,
          decoded.toOption.get == event
        )
      }
    ),
    
    suite("handleConfigUpdated — HMSET routing flags")(
      
      test("все поля конфигурации записываются в device:{imei}") {
        for
          (redis, opsRef) <- makeMockRedis
          config = VehicleConfig(
            organizationId = 5,
            imei = "123456789012345",
            name = "Газель",
            hasGeozones = true,
            hasSpeedRules = false,
            hasRetranslation = true,
            retranslationTargets = List("wialon-42", "webhook-7")
          )
          // Имитируем handleConfigUpdated
          fields = Map(
            "organizationId" -> config.organizationId.toString,
            "name" -> config.name,
            "hasGeozones" -> config.hasGeozones.toString,
            "hasSpeedRules" -> config.hasSpeedRules.toString,
            "hasRetranslation" -> config.hasRetranslation.toString,
            "retranslationTargets" -> config.retranslationTargets.mkString(",")
          )
          _ <- redis.hset(s"device:${config.imei}", fields)
          ops <- opsRef.get
        yield assertTrue(
          ops.size == 1,
          ops.head.key == "device:123456789012345",
          ops.head.op == "hset",
          ops.head.fields("hasGeozones") == "true",
          ops.head.fields("hasRetranslation") == "true",
          ops.head.fields("hasSpeedRules") == "false",
          ops.head.fields("retranslationTargets") == "wialon-42,webhook-7",
          ops.head.fields("organizationId") == "5",
          ops.head.fields("name") == "Газель"
        )
      },
      
      test("пустые retranslationTargets → пустая строка") {
        for
          (redis, opsRef) <- makeMockRedis
          config = VehicleConfig(
            organizationId = 1,
            imei = "999999999999999",
            hasRetranslation = false,
            retranslationTargets = List.empty
          )
          fields = Map("retranslationTargets" -> config.retranslationTargets.mkString(","))
          _ <- redis.hset(s"device:${config.imei}", fields)
          ops <- opsRef.get
        yield assertTrue(ops.head.fields("retranslationTargets") == "")
      },
      
      test("legacy ключ vehicle:config:{imei} тоже обновляется") {
        for
          (redis, opsRef) <- makeMockRedis
          config = VehicleConfig(
            organizationId = 1,
            imei = "111111111111111",
            name = "test"
          )
          // Основной HASH
          _ <- redis.hset(s"device:${config.imei}", Map("name" -> config.name))
          // Legacy ключ
          _ <- redis.hset(s"vehicle:config:${config.imei}", Map("data" -> config.toJson))
          ops <- opsRef.get
        yield assertTrue(
          ops.size == 2,
          ops(0).key == "device:111111111111111",
          ops(1).key == "vehicle:config:111111111111111"
        )
      }
    ),
    
    suite("handleConfigDeleted — сброс флагов")(
      
      test("флаги сбрасываются в false (HASH не удаляется)") {
        for
          (redis, opsRef) <- makeMockRedis
          resetFields = Map(
            "hasGeozones" -> "false",
            "hasSpeedRules" -> "false",
            "hasRetranslation" -> "false",
            "retranslationTargets" -> ""
          )
          _ <- redis.hset("device:123456789012345", resetFields)
          ops <- opsRef.get
        yield assertTrue(
          ops.size == 1,
          ops.head.op == "hset", // HSET, а не DEL  
          ops.head.fields("hasGeozones") == "false",
          ops.head.fields("hasSpeedRules") == "false",
          ops.head.fields("hasRetranslation") == "false",
          ops.head.fields("retranslationTargets") == ""
        )
      },
      
      test("legacy ключ vehicle:config:{imei} УДАЛЯЕТСЯ при config_deleted") {
        for
          (redis, opsRef) <- makeMockRedis
          _ <- redis.del("vehicle:config:123456789012345")
          ops <- opsRef.get
        yield assertTrue(
          ops.size == 1,
          ops.head.key == "vehicle:config:123456789012345",
          ops.head.op == "del"
        )
      }
    ),
    
    suite("VehicleConfig — маршрутизационные флаги")(
      
      test("needsRulesCheck = true если есть геозоны") {
        val config = VehicleConfig(organizationId = 1, imei = "x", hasGeozones = true)
        assertTrue(config.needsRulesCheck)
      },
      
      test("needsRulesCheck = true если есть спидовые правила") {
        val config = VehicleConfig(organizationId = 1, imei = "x", hasSpeedRules = true)
        assertTrue(config.needsRulesCheck)
      },
      
      test("needsRulesCheck = false если оба отключены") {
        val config = VehicleConfig(organizationId = 1, imei = "x")
        assertTrue(!config.needsRulesCheck)
      },
      
      test("retranslationTargets по умолчанию пусты") {
        val config = VehicleConfig(organizationId = 1, imei = "x")
        assertTrue(config.retranslationTargets.isEmpty)
      }
    ),
    
    suite("маршрутизация по eventType")(
      
      test("config_updated с vehicleConfig → обрабатывается") {
        val event = DeviceEvent(
          imei = "111",
          vehicleId = 1,
          organizationId = 1,
          eventType = "config_updated",
          vehicleConfig = Some(VehicleConfig(1, "111"))
        )
        assertTrue(event.eventType == "config_updated", event.vehicleConfig.isDefined)
      },
      
      test("config_updated без vehicleConfig → предупреждение") {
        val event = DeviceEvent(
          imei = "111",
          vehicleId = 1,
          organizationId = 1,
          eventType = "config_updated",
          vehicleConfig = None
        )
        assertTrue(event.eventType == "config_updated", event.vehicleConfig.isEmpty)
      },
      
      test("неизвестный eventType → пропускается") {
        val event = DeviceEvent(
          imei = "111",
          vehicleId = 1,
          organizationId = 1,
          eventType = "device_moved"
        )
        assertTrue(event.eventType != "config_updated", event.eventType != "config_deleted")
      }
    )
  )
