package com.wayrecall.tracker.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.config.{AppConfig, DynamicConfigService, FilterConfig}
import com.wayrecall.tracker.network.{ConnectionRegistry, CommandService}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * HTTP API для управления Connection Manager (v3.0)
 * 
 * Полный набор эндпоинтов (порт 10090):
 * 
 * === СИСТЕМА МОНИТОРИНГА ===
 * - GET  /api/health                       - Health check
 * - GET  /api/health/readiness             - Readiness probe (K8s)
 * - GET  /api/health/liveness              - Liveness probe (K8s)
 * - GET  /api/metrics                      - Prometheus метрики
 * - GET  /api/stats                        - Детальная статистика
 * - GET  /api/version                      - Версия сервиса
 *
 * === УПРАВЛЕНИЕ ФИЛЬТРАМИ ===
 * - GET  /api/config/filters               - Текущая конфигурация
 * - PUT  /api/config/filters               - Обновить конфигурацию
 * - POST /api/config/filters/reset         - Сбросить на defaults
 *
 * === УПРАВЛЕНИЕ СОЕДИНЕНИЯМИ ===
 * - GET  /api/connections                  - Список активных соединений
 * - GET  /api/connections/:imei            - Детали соединения
 * - DELETE /api/connections/:imei          - Принудительно отключить
 * - GET  /api/connections/:imei/last-position - Последняя GPS позиция
 *
 * === ПАРСЕРЫ ===
 * - GET  /api/parsers                      - Какие парсеры включены
 *
 * === КОМАНДЫ ===
 * - POST /api/commands                     - Отправить команду на трекер
 * - POST /api/commands/reboot/:imei        - Quick reboot
 * - POST /api/commands/position/:imei      - Quick position request
 *
 * === ОТЛАДКА ===
 * - GET  /api/debug/redis-ping             - Проверка Redis
 * - GET  /api/debug/kafka-ping             - Проверка Kafka
 * - POST /api/debug/clear-cache            - Очистить in-memory кэши
 */
object HttpApi:
  
  // ======================== DTO ========================
  
  case class HealthResponse(
    status: String,
    timestamp: Long,
    connections: Int,
    uptime: Long
  ) derives JsonCodec
  
  case class ReadinessResponse(
    ready: Boolean,
    redis: Boolean,
    kafka: Boolean
  ) derives JsonCodec

  case class VersionResponse(
    service: String,
    version: String,
    scalaVersion: String,
    instanceId: String,
    startedAt: Long
  ) derives JsonCodec
  
  case class StatsResponse(
    totalConnections: Int,
    totalPacketsReceived: Long,
    totalPointsFiltered: Long,
    totalPointsPublished: Long,
    uptimeSeconds: Long
  ) derives JsonCodec
  
  case class ConnectionResponse(
    imei: String,
    connectedAt: Long,
    remoteAddress: String,
    protocol: String,
    lastActivity: Long
  ) derives JsonCodec
  
  case class ConnectionDetailResponse(
    imei: String,
    connectedAt: Long,
    remoteAddress: String,
    protocol: String,
    lastActivity: Long,
    packetsReceived: Long,
    pointsAccepted: Long
  ) derives JsonCodec
  
  case class LastPositionResponse(
    imei: String,
    latitude: Double,
    longitude: Double,
    speed: Double,
    angle: Int,
    timestamp: Long,
    satellites: Int
  ) derives JsonCodec
  
  case class ParserInfoResponse(
    protocol: String,
    port: Int,
    enabled: Boolean,
    connectionsCount: Int
  ) derives JsonCodec
  
  case class ApiResponse[T](
    success: Boolean,
    data: Option[T],
    error: Option[String]
  ) derives JsonCodec
  
  object ApiResponse:
    def ok[T: JsonEncoder](data: T): ApiResponse[T] = 
      ApiResponse(success = true, data = Some(data), error = None)
    def error[T](message: String): ApiResponse[T] = 
      ApiResponse(success = false, data = None, error = Some(message))
  
  // Время запуска (для uptime)
  private val startedAt: Long = java.lang.System.currentTimeMillis()
  
  // ======================== ROUTES ========================
  
  def routes: Routes[AppConfig & DynamicConfigService & ConnectionRegistry & CommandService, Response] =
    monitoringRoutes ++ filterRoutes ++ connectionRoutes ++ parserRoutes ++ commandRoutes ++ debugRoutes
  
  // ---- Система мониторинга ----
  
  private def monitoringRoutes: Routes[AppConfig & ConnectionRegistry, Response] =
    Routes(
      // Health check
      Method.GET / "api" / "health" -> handler {
        for
          connCount <- ConnectionRegistry.connectionCount
          now = java.lang.System.currentTimeMillis()
          response = HealthResponse(
            status = "ok",
            timestamp = now,
            connections = connCount,
            uptime = (now - startedAt) / 1000
          )
        yield Response.json(response.toJson)
      },
      
      // K8s Readiness probe — проверяет зависимости (Redis, Kafka)
      Method.GET / "api" / "health" / "readiness" -> handler {
        // TODO: добавить реальные проверки Redis и Kafka пинга
        val response = ReadinessResponse(ready = true, redis = true, kafka = true)
        ZIO.succeed(Response.json(response.toJson))
      },
      
      // K8s Liveness probe — быстрая проверка что процесс жив
      Method.GET / "api" / "health" / "liveness" -> handler {
        ZIO.succeed(Response.json("""{"alive": true}"""))
      },
      
      // Prometheus-совместимые метрики (text/plain exposition format)
      Method.GET / "api" / "metrics" -> handler {
        for
          connCount <- ConnectionRegistry.connectionCount
          now = java.lang.System.currentTimeMillis()
          uptimeSeconds = (now - startedAt) / 1000
          metrics = s"""# HELP cm_connections_active Количество активных TCP соединений
# TYPE cm_connections_active gauge
cm_connections_active $connCount

# HELP cm_uptime_seconds Время работы сервиса в секундах
# TYPE cm_uptime_seconds gauge
cm_uptime_seconds $uptimeSeconds

# HELP cm_started_at_timestamp Время запуска сервиса (unix ms)
# TYPE cm_started_at_timestamp gauge
cm_started_at_timestamp $startedAt
"""
        yield Response(
          status = Status.Ok,
          headers = Headers(Header.ContentType(MediaType.text.plain)),
          body = Body.fromString(metrics)
        )
      },
      
      // Детальная статистика
      Method.GET / "api" / "stats" -> handler {
        for
          connCount <- ConnectionRegistry.connectionCount
          now = java.lang.System.currentTimeMillis()
          response = StatsResponse(
            totalConnections = connCount,
            totalPacketsReceived = 0L,   // TODO: добавить счётчики через Ref[Long]
            totalPointsFiltered = 0L,
            totalPointsPublished = 0L,
            uptimeSeconds = (now - startedAt) / 1000
          )
        yield Response.json(response.toJson)
      },
      
      // Версия сервиса
      Method.GET / "api" / "version" -> handler {
        for
          config <- ZIO.service[AppConfig]
          response = VersionResponse(
            service = "connection-manager",
            version = "3.0.0",
            scalaVersion = "3.4.0",
            instanceId = config.instanceId,
            startedAt = startedAt
          )
        yield Response.json(response.toJson)
      }
    )
  
  // ---- Управление фильтрами ----
  
  private def filterRoutes: Routes[AppConfig & DynamicConfigService, Response] =
    Routes(
      // Получить текущую конфигурацию фильтров
      Method.GET / "api" / "config" / "filters" -> handler {
        for
          config <- DynamicConfigService.getFilterConfig
        yield Response.json(config.toJson)
      },
      
      // Обновить конфигурацию фильтров (Dynamic — через Redis Pub/Sub sync)
      Method.PUT / "api" / "config" / "filters" -> handler { (req: Request) =>
        (for
          body <- req.body.asString
          config <- ZIO.fromEither(body.fromJson[FilterConfig])
                       .mapError(e => s"Invalid JSON: $e")
          _ <- DynamicConfigService.updateFilterConfig(config)
        yield Response.json(ApiResponse.ok("Configuration updated").toJson))
          .catchAll(e => ZIO.succeed(
            Response.json(ApiResponse.error[String](e.toString).toJson)
              .status(Status.BadRequest)
          ))
      },
      
      // Сбросить фильтры на defaults из application.conf
      Method.POST / "api" / "config" / "filters" / "reset" -> handler {
        (for
          appConfig <- ZIO.service[AppConfig]
          f = appConfig.filters
          defaultFilter = FilterConfig(
            deadReckoningMaxSpeedKmh = f.deadReckoning.maxSpeedKmh,
            deadReckoningMaxJumpMeters = f.deadReckoning.maxJumpMeters,
            deadReckoningMaxJumpSeconds = f.deadReckoning.maxJumpSeconds,
            stationaryMinDistanceMeters = f.stationary.minDistanceMeters,
            stationaryMinSpeedKmh = f.stationary.minSpeedKmh
          )
          _ <- DynamicConfigService.updateFilterConfig(defaultFilter)
        yield Response.json(ApiResponse.ok("Filters reset to defaults").toJson))
          .catchAll(e => ZIO.succeed(
            Response.json(ApiResponse.error[String](e.toString).toJson)
              .status(Status.InternalServerError)
          ))
      }
    )
  
  // ---- Управление соединениями ----
  
  private def connectionRoutes: Routes[ConnectionRegistry, Response] =
    Routes(
      // Список всех активных соединений
      Method.GET / "api" / "connections" -> handler {
        for
          connections <- ConnectionRegistry.getAllConnections
          response = connections.map { entry =>
            ConnectionResponse(
              imei = entry.imei,
              connectedAt = entry.connectedAt,
              remoteAddress = entry.ctx.channel().remoteAddress().toString,
              protocol = entry.parser.protocolName,
              lastActivity = entry.lastActivityAt
            )
          }
        yield Response.json(response.toJson)
      },
      
      // Детали конкретного соединения
      Method.GET / "api" / "connections" / string("imei") -> handler { (imei: String, _: Request) =>
        for
          entryOpt <- ConnectionRegistry.findByImei(imei)
          response <- entryOpt match
            case Some(entry) =>
              ZIO.succeed(Response.json(
                ConnectionDetailResponse(
                  imei = entry.imei,
                  connectedAt = entry.connectedAt,
                  remoteAddress = entry.ctx.channel().remoteAddress().toString,
                  protocol = entry.parser.protocolName,
                  lastActivity = entry.lastActivityAt,
                  packetsReceived = 0L,
                  pointsAccepted = 0L
                ).toJson
              ))
            case None =>
              ZIO.succeed(
                Response.json(ApiResponse.error[String](s"Connection not found: $imei").toJson)
                  .status(Status.NotFound)
              )
        yield response
      },
      
      // Принудительно отключить трекер
      Method.DELETE / "api" / "connections" / string("imei") -> handler { (imei: String, _: Request) =>
        (for
          entryOpt <- ConnectionRegistry.findByImei(imei)
          _ <- entryOpt match
            case Some(entry) =>
              ZIO.succeed(entry.ctx.close()) *>
              ConnectionRegistry.unregister(imei) *>
              ZIO.logInfo(s"[API] Принудительное отключение IMEI=$imei")
            case None =>
              ZIO.fail(new Exception(s"Connection not found: $imei"))
        yield Response.json(ApiResponse.ok(s"Disconnected: $imei").toJson))
          .catchAll(e => ZIO.succeed(
            Response.json(ApiResponse.error[String](e.getMessage).toJson)
              .status(Status.NotFound)
          ))
      },
      
      // Последняя GPS позиция устройства (из in-memory кэша)
      Method.GET / "api" / "connections" / string("imei") / "last-position" -> handler { (imei: String, _: Request) =>
        for
          entryOpt <- ConnectionRegistry.findByImei(imei)
          response <- entryOpt match
            case Some(entry) =>
              entry.lastPosition match
                case Some(pos) =>
                  ZIO.succeed(Response.json(
                    LastPositionResponse(
                      imei = imei,
                      latitude = pos.latitude,
                      longitude = pos.longitude,
                      speed = pos.speed,
                      angle = pos.angle,
                      timestamp = pos.timestamp,
                      satellites = pos.satellites
                    ).toJson
                  ))
                case None =>
                  ZIO.succeed(
                    Response.json(ApiResponse.error[String](s"No position for: $imei").toJson)
                      .status(Status.NotFound)
                  )
            case None =>
              ZIO.succeed(
                Response.json(ApiResponse.error[String](s"Connection not found: $imei").toJson)
                  .status(Status.NotFound)
              )
        yield response
      }
    )
  
  // ---- Парсеры ----
  
  private def parserRoutes: Routes[AppConfig & ConnectionRegistry, Response] =
    Routes(
      // Список включённых парсеров и их портов
      Method.GET / "api" / "parsers" -> handler {
        for
          config <- ZIO.service[AppConfig]
          connections <- ConnectionRegistry.getAllConnections
          tcp = config.tcp
          // Считаем соединения по протоколам
          protocolCounts = connections.groupBy(_.parser.protocolName).map((k, v) => k -> v.size)
          parsers = List(
            ParserInfoResponse("teltonika", tcp.teltonika.port, tcp.teltonika.enabled, protocolCounts.getOrElse("teltonika", 0)),
            ParserInfoResponse("wialon", tcp.wialon.port, tcp.wialon.enabled, protocolCounts.getOrElse("wialon", 0)),
            ParserInfoResponse("ruptela", tcp.ruptela.port, tcp.ruptela.enabled, protocolCounts.getOrElse("ruptela", 0)),
            ParserInfoResponse("navtelecom", tcp.navtelecom.port, tcp.navtelecom.enabled, protocolCounts.getOrElse("navtelecom", 0)),
            ParserInfoResponse("gosafe", tcp.gosafe.port, tcp.gosafe.enabled, protocolCounts.getOrElse("gosafe", 0)),
            ParserInfoResponse("skysim", tcp.skysim.port, tcp.skysim.enabled, protocolCounts.getOrElse("skysim", 0)),
            ParserInfoResponse("autophone-mayak", tcp.autophoneMayak.port, tcp.autophoneMayak.enabled, protocolCounts.getOrElse("autophone-mayak", 0)),
            ParserInfoResponse("dtm", tcp.dtm.port, tcp.dtm.enabled, protocolCounts.getOrElse("dtm", 0)),
            ParserInfoResponse("multi", tcp.multi.port, tcp.multi.enabled, protocolCounts.getOrElse("multi", 0))
          )
        yield Response.json(parsers.toJson)
      }
    )
  
  // ---- Команды ----
  
  private def commandRoutes: Routes[CommandService, Response] =
    Routes(
      // Отправить команду на трекер (generic)
      Method.POST / "api" / "commands" -> handler { (req: Request) =>
        (for
          body <- req.body.asString.mapError(_.toString)
          command <- ZIO.fromEither(body.fromJson[Command])
                        .mapError(e => s"Invalid command JSON: $e")
          result <- CommandService.sendCommand(command).mapError(_.getMessage)
        yield Response.json(result.toJson))
          .catchAll { (e: String) => ZIO.succeed(
            Response.json(ApiResponse.error[String](e).toJson)
              .status(Status.InternalServerError)
          )}
      },
      
      // Quick reboot
      Method.POST / "api" / "commands" / "reboot" / string("imei") -> handler { (imei: String, _: Request) =>
        val command = RebootCommand(
          commandId = java.util.UUID.randomUUID().toString,
          imei = imei,
          timestamp = Instant.now()
        )
        (for
          result <- CommandService.sendCommand(command)
        yield Response.json(result.toJson))
          .catchAll { (e: Throwable) => ZIO.succeed(
            Response.json(ApiResponse.error[String](e.getMessage).toJson)
              .status(Status.InternalServerError)
          )}
      },
      
      // Quick position request
      Method.POST / "api" / "commands" / "position" / string("imei") -> handler { (imei: String, _: Request) =>
        val command = RequestPositionCommand(
          commandId = java.util.UUID.randomUUID().toString,
          imei = imei,
          timestamp = Instant.now()
        )
        (for
          result <- CommandService.sendCommand(command)
        yield Response.json(result.toJson))
          .catchAll { (e: Throwable) => ZIO.succeed(
            Response.json(ApiResponse.error[String](e.getMessage).toJson)
              .status(Status.InternalServerError)
          )}
      }
    )
  
  // ---- Отладка и диагностика ----
  
  private def debugRoutes: Routes[ConnectionRegistry, Response] =
    Routes(
      // Проверка Redis (связность)
      Method.GET / "api" / "debug" / "redis-ping" -> handler {
        // TODO: реальный PING через RedisClient
        ZIO.succeed(Response.json("""{"status": "ok", "latency_ms": 0}"""))
      },
      
      // Проверка Kafka (связность)
      Method.GET / "api" / "debug" / "kafka-ping" -> handler {
        // TODO: реальный metadata request через KafkaProducer
        ZIO.succeed(Response.json("""{"status": "ok", "latency_ms": 0}"""))
      },
      
      // Очистить in-memory кэши (принудительная инвалидация)
      Method.POST / "api" / "debug" / "clear-cache" -> handler {
        // TODO: пройтись по всем ConnectionHandler и вызвать invalidateContext на stateRef
        ZIO.succeed(
          Response.json(ApiResponse.ok("Cache invalidation requested").toJson)
        )
      }
    )
  
  /**
   * Запуск HTTP Server
   */
  def server(port: Int): ZIO[AppConfig & DynamicConfigService & ConnectionRegistry & CommandService & Server, Throwable, Nothing] =
    Server.serve(routes.toHttpApp)
