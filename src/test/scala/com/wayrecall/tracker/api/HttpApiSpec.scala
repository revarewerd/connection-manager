package com.wayrecall.tracker.api

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.config.*
import com.wayrecall.tracker.network.*
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.protocol.ProtocolParser
import io.netty.channel.ChannelHandlerContext
import java.time.Instant

/**
 * Тесты HTTP API эндпоинтов Connection Manager
 *
 * Тестируемые группы:
 * 1. Мониторинг (health, liveness, readiness, metrics, stats, version)
 * 2. Фильтры (get/update/reset config)
 * 3. Соединения (list, find, delete, last-position) — ограниченно из-за Netty
 * 4. Команды (send, quick reboot, quick position)
 * 5. Отладка (redis-ping, kafka-ping, clear-cache)
 *
 * Для тестирования создаём mock-реализации ConnectionRegistry,
 * DynamicConfigService, CommandService и слой AppConfig.
 */
object HttpApiSpec extends ZIOSpecDefault:

  // ═══════════════════════════════ Mock слои ═══════════════════════════════

  /** Mock ConnectionRegistry — возвращает пустой список без Netty */
  private class MockConnectionRegistry(countRef: Ref[Int]) extends ConnectionRegistry:
    def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] = ZIO.unit
    def unregister(imei: String): UIO[Unit] = ZIO.unit
    def findByImei(imei: String): UIO[Option[ConnectionEntry]] = ZIO.none
    def getAllConnections: UIO[List[ConnectionEntry]] = ZIO.succeed(Nil)
    def connectionCount: UIO[Int] = countRef.get
    def isConnected(imei: String): UIO[Boolean] = ZIO.succeed(false)
    def updateLastActivity(imei: String): UIO[Unit] = ZIO.unit
    def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]] = ZIO.succeed(Nil)

  private val mockConnectionRegistryLayer: ZLayer[Any, Nothing, ConnectionRegistry] =
    ZLayer {
      for count <- Ref.make(5) // Мок: 5 подключений
      yield new MockConnectionRegistry(count)
    }

  /** Mock DynamicConfigService */
  private class MockDynamicConfigService(ref: Ref[FilterConfig]) extends DynamicConfigService:
    def getFilterConfig: UIO[FilterConfig] = ref.get
    def updateFilterConfig(config: FilterConfig): Task[Unit] = ref.set(config)
    def subscribeToChanges: Task[Unit] = ZIO.unit

  private val mockDynamicConfigLayer: ZLayer[Any, Nothing, DynamicConfigService] =
    ZLayer {
      for ref <- Ref.make(FilterConfig())
      yield new MockDynamicConfigService(ref)
    }

  /** Mock CommandService */
  private class MockCommandService extends CommandService:
    def sendCommand(command: Command): Task[CommandResult] =
      ZIO.succeed(CommandResult(
        commandId = command.commandId,
        imei = command.imei,
        status = CommandStatus.Sent,
        message = Some("Команда отправлена (mock)"),
        timestamp = Instant.now()
      ))
    def startCommandListener: Task[Unit] = ZIO.unit
    def handleCommandResponse(imei: String, response: Array[Byte]): Task[Unit] = ZIO.unit

  private val mockCommandServiceLayer: ZLayer[Any, Nothing, CommandService] =
    ZLayer.succeed(new MockCommandService)

  /** Минимальный AppConfig для тестов */
  private val testAppConfig = AppConfig(
    instanceId = "test-instance-1",
    debugMode = false,
    tcp = TcpConfig(
      teltonika = TcpProtocolConfig(5001, true),
      wialon = TcpProtocolConfig(5002, true),
      ruptela = TcpProtocolConfig(5003, true),
      navtelecom = TcpProtocolConfig(5004, true),
      gosafe = TcpProtocolConfig(5005, true),
      skysim = TcpProtocolConfig(5006, false),
      autophoneMayak = TcpProtocolConfig(5007, false),
      dtm = TcpProtocolConfig(5008, true),
      galileosky = TcpProtocolConfig(5009, false),
      concox = TcpProtocolConfig(5010, false),
      tk102 = TcpProtocolConfig(5011, false),
      tk103 = TcpProtocolConfig(5012, false),
      arnavi = TcpProtocolConfig(5013, false),
      adm = TcpProtocolConfig(5014, false),
      gtlt = TcpProtocolConfig(5015, false),
      microMayak = TcpProtocolConfig(5016, false),
      multi = MultiProtocolPortConfig(5017, true),
      bossThreads = 1,
      workerThreads = 2,
      maxConnections = 100,
      keepAlive = true,
      tcpNodelay = true,
      connectionTimeoutSeconds = 30,
      readTimeoutSeconds = 60,
      writeTimeoutSeconds = 30,
      idleTimeoutSeconds = 300,
      idleCheckIntervalSeconds = 60
    ),
    http = HttpConfig(10090, "0.0.0.0"),
    redis = RedisConfig("localhost", 6379, None, 0, 8, 3600, 600),
    kafka = KafkaConfig(
      bootstrapServers = "localhost:9092",
      producer = KafkaProducerSettings("all", 3, 16384, 5, "none"),
      consumer = KafkaConsumerSettings("cm-test", 30000, "latest", 500),
      topics = KafkaTopicsConfig(
        gpsEvents = "gps-events",
        gpsEventsRules = "gps-events-rules",
        gpsEventsRetranslation = "gps-events-retranslation",
        gpsParseErrors = "gps-parse-errors",
        deviceStatus = "device-status",
        deviceCommands = "device-commands",
        deviceEvents = "device-events",
        commandAudit = "command-audit",
        unknownDevices = "unknown-devices",
        unknownGpsEvents = "unknown-gps-events"
      )
    ),
    filters = FiltersConfig(
      deadReckoning = DeadReckoningFilterConfig(300, 1000, 1),
      stationary = StationaryFilterConfig(20, 2)
    ),
    commands = CommandsConfig(30, 3),
    logging = LoggingConfig("INFO", false)
  )

  private val testAppConfigLayer: ZLayer[Any, Nothing, AppConfig] =
    ZLayer.succeed(testAppConfig)

  /** Все mock-слои вместе */
  private val allMockLayers =
    testAppConfigLayer ++ mockConnectionRegistryLayer ++ mockDynamicConfigLayer ++ mockCommandServiceLayer

  // ═══════════════════════════════ Хелперы ═══════════════════════════════

  /** Выполняем запрос к routes и возвращаем Response */
  private def callRoute(method: Method, path: String, body: Option[String] = None) =
    val request = body match
      case Some(b) => Request(
        method = method,
        url = URL.decode(path).getOrElse(URL.root),
        body = Body.fromString(b),
        headers = Headers(Header.ContentType(MediaType.application.json))
      )
      case None => Request(
        method = method,
        url = URL.decode(path).getOrElse(URL.root)
      )
    HttpApi.routes.toHttpApp.runZIO(request)

  /** Извлекаем тело ответа как строку */
  private def bodyAsString(response: Response): Task[String] =
    response.body.asString

  // ═══════════════════════════════ ТЕСТЫ ═══════════════════════════════

  def spec: Spec[Any, Any] = suite("HttpApiSpec")(

    // ────────────────── Мониторинг ──────────────────

    suite("Мониторинг")(

      test("GET /api/health → 200 с полем status=ok") {
        for
          response <- callRoute(Method.GET, "/api/health")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"status\":\"ok\"") &&
          body.contains("\"connections\":5")
        )
      },

      test("GET /api/health/liveness → 200 с alive=true") {
        for
          response <- callRoute(Method.GET, "/api/health/liveness")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("alive") &&
          body.contains("true")
        )
      },

      test("GET /api/health/readiness → 200 с ready=true") {
        for
          response <- callRoute(Method.GET, "/api/health/readiness")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"ready\":true")
        )
      },

      test("GET /api/metrics → 200 text/plain с Prometheus метриками") {
        for
          response <- callRoute(Method.GET, "/api/metrics")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("cm_connections_active 5") &&
          body.contains("cm_uptime_seconds")
        )
      },

      test("GET /api/stats → 200 с JSON статистикой") {
        for
          response <- callRoute(Method.GET, "/api/stats")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"totalConnections\":5")
        )
      },

      test("GET /api/version → 200 с версией сервиса") {
        for
          response <- callRoute(Method.GET, "/api/version")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"service\":\"connection-manager\"") &&
          body.contains("\"version\":\"3.0.0\"") &&
          body.contains("\"instanceId\":\"test-instance-1\"")
        )
      }
    ),

    // ────────────────── Фильтры ──────────────────

    suite("Управление фильтрами")(

      test("GET /api/config/filters → 200 с конфигурацией по умолчанию") {
        for
          response <- callRoute(Method.GET, "/api/config/filters")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"deadReckoningMaxSpeedKmh\":300")
        )
      },

      test("PUT /api/config/filters → 200 обновление конфигурации") {
        val newConfig = """{"deadReckoningMaxSpeedKmh":200,"deadReckoningMaxJumpMeters":500,"deadReckoningMaxJumpSeconds":2,"stationaryMinDistanceMeters":10,"stationaryMinSpeedKmh":1}"""
        for
          response <- callRoute(Method.PUT, "/api/config/filters", Some(newConfig))
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"success\":true")
        )
      },

      test("PUT /api/config/filters с невалидным JSON → 400") {
        for
          response <- callRoute(Method.PUT, "/api/config/filters", Some("{invalid}"))
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.BadRequest
        )
      },

      test("POST /api/config/filters/reset → 200 сброс на defaults") {
        for
          response <- callRoute(Method.POST, "/api/config/filters/reset")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"success\":true")
        )
      }
    ),

    // ────────────────── Соединения ──────────────────

    suite("Управление соединениями")(

      test("GET /api/connections → 200 пустой список (mock)") {
        for
          response <- callRoute(Method.GET, "/api/connections")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body == "[]"
        )
      },

      test("GET /api/connections/:imei → 404 (нет такого соединения)") {
        for
          response <- callRoute(Method.GET, "/api/connections/352093089612345")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.NotFound &&
          body.contains("Connection not found")
        )
      },

      test("GET /api/connections/:imei/last-position → 404") {
        for
          response <- callRoute(Method.GET, "/api/connections/352093089612345/last-position")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.NotFound
        )
      }
    ),

    // ────────────────── Парсеры ──────────────────

    suite("Парсеры")(

      test("GET /api/parsers → 200 список парсеров") {
        for
          response <- callRoute(Method.GET, "/api/parsers")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"protocol\":\"teltonika\"") &&
          body.contains("\"protocol\":\"wialon\"") &&
          body.contains("\"enabled\":true") &&
          body.contains("\"port\":5001")
        )
      }
    ),

    // ────────────────── Команды ──────────────────

    suite("Команды")(

      test("POST /api/commands/reboot/:imei → 200 (mock)") {
        for
          response <- callRoute(Method.POST, "/api/commands/reboot/352093089612345")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("Sent") &&
          body.contains("352093089612345")
        )
      },

      test("POST /api/commands/position/:imei → 200 (mock)") {
        for
          response <- callRoute(Method.POST, "/api/commands/position/352093089612345")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("Sent") &&
          body.contains("352093089612345")
        )
      }
    ),

    // ────────────────── Отладка ──────────────────

    suite("Отладка")(

      test("GET /api/debug/redis-ping → 200") {
        for
          response <- callRoute(Method.GET, "/api/debug/redis-ping")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("ok")
        )
      },

      test("GET /api/debug/kafka-ping → 200") {
        for
          response <- callRoute(Method.GET, "/api/debug/kafka-ping")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("ok")
        )
      },

      test("POST /api/debug/clear-cache → 200") {
        for
          response <- callRoute(Method.POST, "/api/debug/clear-cache")
          body <- bodyAsString(response)
        yield assertTrue(
          response.status == Status.Ok &&
          body.contains("\"success\":true")
        )
      }
    ),

    // ────────────────── 404 для несуществующих маршрутов ──────────────────

    suite("Несуществующие маршруты")(

      test("GET /api/nonexistent → 404") {
        for
          response <- callRoute(Method.GET, "/api/nonexistent")
        yield assertTrue(response.status == Status.NotFound)
      }
    )

  ).provideShared(allMockLayers)
