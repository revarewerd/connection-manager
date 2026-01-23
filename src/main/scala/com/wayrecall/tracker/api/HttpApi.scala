package com.wayrecall.tracker.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.config.{DynamicConfigService, FilterConfig}
import com.wayrecall.tracker.network.{ConnectionRegistry, CommandService}
import com.wayrecall.tracker.domain.*
import java.time.Instant

/**
 * HTTP API для управления Connection Manager
 * 
 * Endpoints:
 * - GET  /api/health              - Health check
 * - GET  /api/config/filters      - Получить текущую конфигурацию фильтров
 * - PUT  /api/config/filters      - Обновить конфигурацию фильтров
 * - GET  /api/connections         - Список активных соединений
 * - GET  /api/connections/:imei   - Информация о соединении
 * - POST /api/commands            - Отправить команду на трекер
 */
object HttpApi:
  
  /**
   * Health check response
   */
  case class HealthResponse(
      status: String,
      timestamp: Long,
      connections: Int
  ) derives JsonCodec
  
  /**
   * Connection info response
   */
  case class ConnectionResponse(
      imei: String,
      connectedAt: Long,
      remoteAddress: String
  ) derives JsonCodec
  
  /**
   * API response wrapper
   */
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
  
  /**
   * Создает HTTP routes
   */
  def routes: Routes[DynamicConfigService & ConnectionRegistry & CommandService, Response] =
    Routes(
      // Health check
      Method.GET / "api" / "health" -> handler {
        for
          connCount <- ConnectionRegistry.connectionCount
          response = HealthResponse(
            status = "ok",
            timestamp = java.lang.System.currentTimeMillis(),
            connections = connCount
          )
        yield Response.json(response.toJson)
      },
      
      // Get filter config
      Method.GET / "api" / "config" / "filters" -> handler {
        for
          config <- DynamicConfigService.getFilterConfig
        yield Response.json(config.toJson)
      },
      
      // Update filter config
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
      
      // List all connections
      Method.GET / "api" / "connections" -> handler {
        for
          connections <- ConnectionRegistry.getAllConnections
          response = connections.map { entry =>
            ConnectionResponse(
              imei = entry.imei,
              connectedAt = entry.connectedAt,
              remoteAddress = entry.ctx.channel().remoteAddress().toString
            )
          }
        yield Response.json(response.toJson)
      },
      
      // Get connection by IMEI
      Method.GET / "api" / "connections" / string("imei") -> handler { (imei: String, req: Request) =>
        for
          entryOpt <- ConnectionRegistry.findByImei(imei)
          response <- entryOpt match
            case Some(entry) =>
              ZIO.succeed(Response.json(
                ConnectionResponse(
                  imei = entry.imei,
                  connectedAt = entry.connectedAt,
                  remoteAddress = entry.ctx.channel().remoteAddress().toString
                ).toJson
              ))
            case None =>
              ZIO.succeed(
                Response.json(ApiResponse.error[String](s"Connection not found: $imei").toJson)
                  .status(Status.NotFound)
              )
        yield response
      },
      
      // Send command to tracker
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
      
      // Quick command shortcuts
      Method.POST / "api" / "commands" / "reboot" / string("imei") -> handler { (imei: String, req: Request) =>
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
      
      Method.POST / "api" / "commands" / "position" / string("imei") -> handler { (imei: String, req: Request) =>
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
  
  /**
   * HTTP Server configuration
   */
  def server(port: Int): ZIO[DynamicConfigService & ConnectionRegistry & CommandService & Server, Throwable, Nothing] =
    Server.serve(routes.toHttpApp)
