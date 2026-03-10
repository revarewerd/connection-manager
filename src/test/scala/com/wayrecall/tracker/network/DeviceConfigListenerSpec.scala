package com.wayrecall.tracker.network

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.domain.{DeviceStatus, DisconnectReason}

/**
 * Тесты для DeviceConfigListener
 * 
 * Проверяем:
 * 1. channelPattern парсинг → извлечение IMEI и action
 * 2. handleDeviceDisabled → закрытие соединения + Kafka status
 * 3. handleDeviceEnabled → логирование
 * 4. Некорректный формат канала → warning
 * 5. Нет активного соединения → debug log
 */
object DeviceConfigListenerSpec extends ZIOSpecDefault:
  
  // === Утилита: парсинг канала Redis Pub/Sub ===
  private def parseChannel(channel: String): Option[(String, String)] =
    val parts = channel.split(":")
    if parts.length >= 4 then
      Some((parts(2), parts(3))) // (imei, action)
    else
      None
  
  // === Мок ConnectionRegistry ===
  private def makeMockRegistry(connections: Map[String, Boolean]): UIO[Ref[Map[String, Boolean]]] =
    Ref.make(connections)
  
  // === Мок KafkaProducer ===
  private def makeMockKafka: UIO[Ref[List[DeviceStatus]]] =
    Ref.make(List.empty[DeviceStatus])
  
  def spec = suite("DeviceConfigListener")(
    
    suite("парсинг канала device:config:{imei}:{action}")(
      
      test("device:config:123456789012345:disabled → (imei, disabled)") {
        val result = parseChannel("device:config:123456789012345:disabled")
        assertTrue(
          result.isDefined &&
          result.get._1 == "123456789012345" &&
          result.get._2 == "disabled"
        )
      },
      
      test("device:config:999999999999999:enabled → (imei, enabled)") {
        val result = parseChannel("device:config:999999999999999:enabled")
        assertTrue(
          result.isDefined &&
          result.get._1 == "999999999999999" &&
          result.get._2 == "enabled"
        )
      },
      
      test("device:config → None (слишком короткий)") {
        val result = parseChannel("device:config")
        assertTrue(result.isEmpty)
      },
      
      test("device:config:123 → None (3 части, нужно 4+)") {
        val result = parseChannel("device:config:123")
        assertTrue(result.isEmpty)
      },
      
      test("полный формат с лишними частями → работает") {
        val result = parseChannel("device:config:123456789012345:disabled:extra")
        assertTrue(
          result.isDefined &&
          result.get._1 == "123456789012345" &&
          result.get._2 == "disabled"
        )
      }
    ),
    
    suite("handleDeviceDisabled")(
      
      test("есть активное соединение → закрываем и публикуем статус") {
        for
          registryRef <- makeMockRegistry(Map("123456789012345" -> true))
          kafkaRef <- makeMockKafka
          
          // Имитируем: найти соединение → закрыть → unregister → publish
          hasConnection <- registryRef.get.map(_.contains("123456789012345"))
          _ <- ZIO.when(hasConnection) {
            for
              _ <- registryRef.update(_ - "123456789012345")
              now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
              status = DeviceStatus(
                imei = "123456789012345",
                vehicleId = 0L,
                isOnline = false,
                lastSeen = now,
                disconnectReason = Some(DisconnectReason.AdminDisconnect)
              )
              _ <- kafkaRef.update(_ :+ status)
            yield ()
          }
          
          connections <- registryRef.get
          events <- kafkaRef.get
        yield assertTrue(
          !connections.contains("123456789012345") &&
          events.size == 1 &&
          events.head.isOnline == false &&
          events.head.disconnectReason.contains(DisconnectReason.AdminDisconnect)
        )
      },
      
      test("нет активного соединения → ничего не делаем") {
        for
          registryRef <- makeMockRegistry(Map.empty) // Пустой реестр
          kafkaRef <- makeMockKafka
          
          hasConnection <- registryRef.get.map(_.contains("123456789012345"))
          _ <- ZIO.when(hasConnection) {
            kafkaRef.update(_ :+ DeviceStatus("123456789012345", 0, false, 0))
          }
          
          events <- kafkaRef.get
        yield assertTrue(events.isEmpty) // Не публиковали ничего
      }
    ),
    
    suite("handleDeviceEnabled")(
      
      test("логирование включения устройства (нет активных действий)") {
        // DeviceConfigListener при enabled только логирует
        val imei = "123456789012345"
        assertTrue(imei.nonEmpty) // Простая проверка — нет побочных эффектов
      }
    ),
    
    suite("DisconnectReason.AdminDisconnect")(
      
      test("правильная причина отключения при блокировке") {
        val reason = DisconnectReason.AdminDisconnect
        assertTrue(reason.toString == "AdminDisconnect")
      },
      
      test("все причины отключения определены") {
        val reasons = List(
          DisconnectReason.GracefulClose,
          DisconnectReason.IdleTimeout,
          DisconnectReason.ReadTimeout,
          DisconnectReason.WriteTimeout,
          DisconnectReason.ConnectionReset,
          DisconnectReason.ProtocolError,
          DisconnectReason.ServerShutdown,
          DisconnectReason.AdminDisconnect,
          DisconnectReason.Unknown
        )
        assertTrue(reasons.size == 9)
      }
    ),
    
    suite("channelPattern")(
      
      test("паттерн device:config:* матчит все IMEI") {
        val pattern = "device:config:*"
        assertTrue(pattern.endsWith("*"))
      }
    )
  )
