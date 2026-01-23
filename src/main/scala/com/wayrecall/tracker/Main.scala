package com.wayrecall.tracker

import zio.*
import zio.logging.backend.SLF4J
import com.wayrecall.tracker.config.*
import com.wayrecall.tracker.network.{TcpServer, ConnectionHandler, GpsProcessingService, ConnectionRegistry, CommandService, IdleConnectionWatcher}
import com.wayrecall.tracker.protocol.{ProtocolParser, TeltonikaParser, WialonParser, RuptelaParser, NavTelecomParser}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}
import com.wayrecall.tracker.api.HttpApi

/**
 * Точка входа Connection Manager Service
 * 
 * ✅ Чисто функциональный - ZIO Layer для композиции зависимостей
 * ✅ Нет mutable state - используем Ref
 * ✅ Graceful shutdown при SIGTERM
 */
object Main extends ZIOAppDefault:
  
  // Настройка логирования через SLF4J
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j
  
  /**
   * Основная программа - чисто декларативная
   */
  val program: ZIO[
    AppConfig & TcpServer & GpsProcessingService & ConnectionRegistry & CommandService & DynamicConfigService & IdleConnectionWatcher,
    Throwable, Unit
  ] =
    for
      config <- ZIO.service[AppConfig]
      server <- ZIO.service[TcpServer]
      service <- ZIO.service[GpsProcessingService]
      registry <- ZIO.service[ConnectionRegistry]
      commandService <- ZIO.service[CommandService]
      dynamicConfig <- ZIO.service[DynamicConfigService]
      idleWatcher <- ZIO.service[IdleConnectionWatcher]
      runtime <- ZIO.runtime[Any]
      
      _ <- ZIO.logInfo("=== Connection Manager Service v2.1 (Pure FP) ===")
      _ <- ZIO.logInfo(s"Teltonika: порт ${config.tcp.teltonika.port} (enabled: ${config.tcp.teltonika.enabled})")
      _ <- ZIO.logInfo(s"Wialon: порт ${config.tcp.wialon.port} (enabled: ${config.tcp.wialon.enabled})")
      _ <- ZIO.logInfo(s"Ruptela: порт ${config.tcp.ruptela.port} (enabled: ${config.tcp.ruptela.enabled})")
      _ <- ZIO.logInfo(s"NavTelecom: порт ${config.tcp.navtelecom.port} (enabled: ${config.tcp.navtelecom.enabled})")
      _ <- ZIO.logInfo(s"HTTP API: порт ${config.http.port}")
      _ <- ZIO.logInfo(s"Redis: ${config.redis.host}:${config.redis.port}")
      _ <- ZIO.logInfo(s"Kafka: ${config.kafka.bootstrapServers}")
      _ <- ZIO.logInfo(s"Idle timeout: ${config.tcp.idleTimeoutSeconds}s, check interval: ${config.tcp.idleCheckIntervalSeconds}s")
      
      // Получаем текущую конфигурацию фильтров
      filterConfig <- dynamicConfig.getFilterConfig
      _ <- ZIO.logInfo(s"Filter config: maxSpeed=${filterConfig.deadReckoningMaxSpeedKmh}km/h, minDistance=${filterConfig.stationaryMinDistanceMeters}m")
      
      // Создаем фабрики обработчиков для каждого протокола
      teltonikaFactory = ConnectionHandler.factory(service, new TeltonikaParser, registry, runtime)
      wialonFactory = ConnectionHandler.factory(service, WialonParser, registry, runtime)
      ruptelaFactory = ConnectionHandler.factory(service, RuptelaParser, registry, runtime)
      navtelecomFactory = ConnectionHandler.factory(service, NavTelecomParser, registry, runtime)
      
      // Запускаем TCP серверы
      _ <- ZIO.collectAllParDiscard(
        List(
          startServerIfEnabled("Teltonika", config.tcp.teltonika, server, teltonikaFactory),
          startServerIfEnabled("Wialon", config.tcp.wialon, server, wialonFactory),
          startServerIfEnabled("Ruptela", config.tcp.ruptela, server, ruptelaFactory),
          startServerIfEnabled("NavTelecom", config.tcp.navtelecom, server, navtelecomFactory)
        )
      )
      
      // Запускаем слушатель команд из Redis
      _ <- commandService.startCommandListener.forkDaemon
      _ <- ZIO.logInfo("✓ Command listener started")
      
      // Запускаем мониторинг idle соединений
      _ <- idleWatcher.start
      _ <- ZIO.logInfo("✓ Idle connection watcher started")
      
      // Запускаем HTTP API (в отдельном fiber)
      httpFiber <- HttpApi.server(config.http.port)
        .provideSome[DynamicConfigService & ConnectionRegistry & CommandService](
          zio.http.Server.defaultWithPort(config.http.port)
        )
        .forkDaemon
      _ <- ZIO.logInfo(s"✓ HTTP API started on port ${config.http.port}")
      
      _ <- ZIO.logInfo("=== Все серверы запущены ===")
      _ <- ZIO.logInfo("Нажмите Ctrl+C для остановки")
      
      // Ожидаем бесконечно (graceful shutdown при SIGTERM)
      _ <- ZIO.never
    yield ()
  
  /**
   * Запускает сервер если он включен в конфигурации
   */
  private def startServerIfEnabled(
    name: String,
    config: TcpProtocolConfig,
    server: TcpServer,
    handlerFactory: () => ConnectionHandler
  ): Task[Unit] =
    if config.enabled then
      server.start(config.port, handlerFactory)
        .tap(_ => ZIO.logInfo(s"✓ $name сервер запущен на порту ${config.port}"))
        .unit
    else
      ZIO.logInfo(s"✗ $name сервер отключен")
  
  /**
   * Композиция всех слоёв приложения
   */
  val appLayer: ZLayer[Any, Throwable, 
    AppConfig & TcpServer & GpsProcessingService & ConnectionRegistry & CommandService & DynamicConfigService & IdleConnectionWatcher
  ] =
    // Базовые слои
    val configLayer = AppConfig.live
    
    // Слой конфигурации для подкомпонентов
    val tcpConfigLayer = configLayer.project(_.tcp)
    val redisConfigLayer = configLayer.project(_.redis)
    val kafkaConfigLayer = configLayer.project(_.kafka)
    
    // Инфраструктурные слои
    val tcpServerLayer = tcpConfigLayer >>> TcpServer.live
    val redisLayer = redisConfigLayer >>> RedisClient.live
    val kafkaLayer = kafkaConfigLayer >>> KafkaProducer.live
    
    // Реестр соединений
    val registryLayer = ConnectionRegistry.live
    
    // Динамическая конфигурация
    val dynamicConfigLayer = (redisLayer ++ configLayer) >>> DynamicConfigService.live
    
    // Слои фильтров (теперь используют DynamicConfigService)
    val deadReckoningLayer = dynamicConfigLayer >>> DeadReckoningFilter.live
    val stationaryLayer = dynamicConfigLayer >>> StationaryFilter.live
    
    // Слой сервиса обработки GPS
    val processingServiceLayer = 
      (TeltonikaParser.live ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
        GpsProcessingService.live
    
    // Слой сервиса команд
    val commandServiceLayer = (redisLayer ++ registryLayer) >>> CommandService.live
    
    // Слой мониторинга idle соединений (теперь с Kafka и Redis для уведомлений)
    val idleWatcherLayer = (registryLayer ++ dynamicConfigLayer ++ kafkaLayer ++ redisLayer ++ tcpConfigLayer) >>> IdleConnectionWatcher.live
    
    // Финальная композиция
    configLayer ++ tcpServerLayer ++ processingServiceLayer ++ registryLayer ++ commandServiceLayer ++ dynamicConfigLayer ++ idleWatcherLayer
  
  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](appLayer)
      .tapError(e => ZIO.logError(s"Критическая ошибка: ${e.getMessage}"))
