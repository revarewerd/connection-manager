package com.wayrecall.tracker

import zio.*
import zio.logging.backend.SLF4J
import com.wayrecall.tracker.config.*
import com.wayrecall.tracker.network.{TcpServer, ConnectionHandler, GpsProcessingService, ConnectionRegistry, CommandService, IdleConnectionWatcher, RateLimiter, DeviceConfigListener}
import com.wayrecall.tracker.service.CommandHandler
import com.wayrecall.tracker.service.DeviceEventConsumer
import com.wayrecall.tracker.protocol.{ProtocolParser, TeltonikaParser, WialonParser, WialonAdapterParser, RuptelaParser, NavTelecomParser}
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
    AppConfig & TcpServer & GpsProcessingService & ConnectionRegistry & CommandService & DynamicConfigService & IdleConnectionWatcher & DeviceConfigListener & RateLimiter & CommandHandler & DeviceEventConsumer,
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
      deviceConfigListener <- ZIO.service[DeviceConfigListener]
      rateLimiter <- ZIO.service[RateLimiter]
      commandHandler <- ZIO.service[CommandHandler]
      deviceEventConsumer <- ZIO.service[DeviceEventConsumer]
      runtime <- ZIO.runtime[Any]
      
      _ <- ZIO.logInfo("=== Connection Manager Service v2.1 (Pure FP) ===")
      _ <- ZIO.logInfo(s"Redis: ${config.redis.host}:${config.redis.port}")
      _ <- ZIO.logInfo(s"Kafka: ${config.kafka.bootstrapServers}")
      
      // ============================================================
      // ПОРЯДОК ЗАПУСКА (критично для корректной работы!)
      // ============================================================
      
      // Шаг 1: Проверяем подключение к Redis (ZLayer уже создал клиент)
      _ <- ZIO.logInfo("[1/6] Проверка Redis...")
      // Redis клиент уже инициализирован через ZLayer
      _ <- ZIO.logInfo("      ✓ Redis подключен")
      
      // Шаг 2: Запуск Kafka Consumer для команд (Static Partition Assignment)
      _ <- ZIO.logInfo("[2/8] Запуск Kafka Consumer для device-commands...")
      commandHandlerFiber <- commandHandler.start
      _ <- ZIO.logInfo(s"      ✓ Kafka Consumer запущен (instance=${config.instanceId})")
      
      // Шаг 2.5: Запуск Kafka Consumer для device-events (обновление конфигурации)
      _ <- ZIO.logInfo("[2.5/8] Запуск Kafka Consumer для device-events...")
      deviceEventFiber <- deviceEventConsumer.start
      _ <- ZIO.logInfo(s"      ✓ Device Event Consumer запущен")
      
      // Шаг 3: Подписываемся на Redis pub/sub для динамической конфигурации
      _ <- ZIO.logInfo("[3/7] Запуск слушателей Redis...")
      _ <- deviceConfigListener.start.forkDaemon
      _ <- ZIO.logInfo("      ✓ Device config listener (device-config-changed)")
      _ <- commandService.startCommandListener.forkDaemon
      _ <- ZIO.logInfo("      ✓ Command listener (commands:*) [DEPRECATED - для совместимости]")
      
      // Шаг 4: Запуск мониторинга idle соединений
      _ <- ZIO.logInfo("[4/7] Запуск мониторинга...")
      _ <- idleWatcher.start
      _ <- ZIO.logInfo(s"      ✓ Idle watcher (timeout: ${config.tcp.idleTimeoutSeconds}s)")
      
      // Шаг 5: Получаем текущую конфигурацию фильтров
      filterConfig <- dynamicConfig.getFilterConfig
      _ <- ZIO.logInfo(s"[5/7] Конфигурация фильтров: maxSpeed=${filterConfig.deadReckoningMaxSpeedKmh}km/h, minDistance=${filterConfig.stationaryMinDistanceMeters}m")
      
      // Шаг 6: Запуск TCP серверов (параллельно, т.к. независимы)
      _ <- ZIO.logInfo("[6/7] Запуск TCP серверов...")
      
      // Создаем фабрики обработчиков для каждого протокола
      teltonikaFactory = ConnectionHandler.factory(service, new TeltonikaParser, registry, runtime)
      wialonFactory = ConnectionHandler.factory(service, WialonAdapterParser, registry, runtime)
      ruptelaFactory = ConnectionHandler.factory(service, RuptelaParser, registry, runtime)
      navtelecomFactory = ConnectionHandler.factory(service, NavTelecomParser, registry, runtime)
      
      // Запускаем TCP серверы параллельно
      _ <- ZIO.collectAllParDiscard(
        List(
          startServerIfEnabled("Teltonika", config.tcp.teltonika, server, teltonikaFactory),
          startServerIfEnabled("Wialon", config.tcp.wialon, server, wialonFactory),
          startServerIfEnabled("Ruptela", config.tcp.ruptela, server, ruptelaFactory),
          startServerIfEnabled("NavTelecom", config.tcp.navtelecom, server, navtelecomFactory)
        )
      )
      
      // Шаг 7: Запуск HTTP API (последним, чтобы /health возвращал OK только когда всё готово)
      _ <- ZIO.logInfo("[7/7] Запуск HTTP API...")
      httpFiber <- HttpApi.server(config.http.port)
        .provideSome[DynamicConfigService & ConnectionRegistry & CommandService](
          zio.http.Server.defaultWithPort(config.http.port)
        )
        .forkDaemon
      _ <- ZIO.logInfo(s"      ✓ HTTP API на порту ${config.http.port}")
      
      // ============================================================
      _ <- ZIO.logInfo("=== Все компоненты запущены ===")
      _ <- ZIO.logInfo(s"Teltonika: порт ${config.tcp.teltonika.port} (enabled: ${config.tcp.teltonika.enabled})")
      _ <- ZIO.logInfo(s"Wialon: порт ${config.tcp.wialon.port} (enabled: ${config.tcp.wialon.enabled})")
      _ <- ZIO.logInfo(s"Ruptela: порт ${config.tcp.ruptela.port} (enabled: ${config.tcp.ruptela.enabled})")
      _ <- ZIO.logInfo(s"NavTelecom: порт ${config.tcp.navtelecom.port} (enabled: ${config.tcp.navtelecom.enabled})")
      _ <- ZIO.logInfo("Нажмите Ctrl+C для остановки (graceful shutdown)")
      
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
    AppConfig & TcpServer & GpsProcessingService & ConnectionRegistry & CommandService & DynamicConfigService & IdleConnectionWatcher & DeviceConfigListener & RateLimiter & CommandHandler & DeviceEventConsumer
  ] =
    // Базовые слои
    val configLayer = AppConfig.live
    
    // Слой конфигурации для подкомпонентов
    val tcpConfigLayer = configLayer.project(_.tcp)
    val redisConfigLayer = configLayer.project(_.redis)
    val kafkaConfigLayer = configLayer.project(_.kafka)
    
    // Инфраструктурные слои
    val redisLayer = redisConfigLayer >>> RedisClient.live
    val kafkaLayer = kafkaConfigLayer >>> KafkaProducer.live
    
    // Rate limiter (для защиты от flood атак)
    val rateLimiterLayer = configLayer >>> RateLimiter.live
    
    // TCP сервер (теперь с rate limiter)
    val tcpServerLayer = (tcpConfigLayer ++ rateLimiterLayer) >>> TcpServer.liveWithRateLimiter
    
    // Реестр соединений
    val registryLayer = ConnectionRegistry.live
    
    // Динамическая конфигурация
    val dynamicConfigLayer = (redisLayer ++ configLayer) >>> DynamicConfigService.live
    
    // Слушатель конфигурации устройств
    val deviceConfigListenerLayer = (redisLayer ++ registryLayer ++ kafkaLayer) >>> DeviceConfigListener.live
    
    // Слои фильтров (теперь используют DynamicConfigService)
    val deadReckoningLayer = dynamicConfigLayer >>> DeadReckoningFilter.live
    val stationaryLayer = dynamicConfigLayer >>> StationaryFilter.live
    
    // Слой сервиса обработки GPS
    val processingServiceLayer = 
      (TeltonikaParser.live ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
        GpsProcessingService.live
    
    // Слой сервиса команд (legacy Redis Pub/Sub)
    val commandServiceLayer = (redisLayer ++ registryLayer) >>> CommandService.live
    
    // Слой обработчика команд (Kafka Static Partition Assignment)
    val commandHandlerLayer = (configLayer ++ registryLayer ++ redisLayer ++ kafkaLayer) >>> CommandHandler.live
    
    // Слой консьюмера событий устройств (Kafka Consumer Group)
    val deviceEventConsumerLayer = (configLayer ++ redisLayer) >>> DeviceEventConsumer.live
    
    // Слой мониторинга idle соединений (теперь с Kafka и Redis для уведомлений)
    val idleWatcherLayer = (registryLayer ++ dynamicConfigLayer ++ kafkaLayer ++ redisLayer ++ tcpConfigLayer) >>> IdleConnectionWatcher.live
    
    // Финальная композиция
    configLayer ++ tcpServerLayer ++ processingServiceLayer ++ registryLayer ++ commandServiceLayer ++ commandHandlerLayer ++ deviceEventConsumerLayer ++ dynamicConfigLayer ++ idleWatcherLayer ++ deviceConfigListenerLayer ++ rateLimiterLayer
  
  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](appLayer)
      .tapError(e => ZIO.logError(s"Критическая ошибка: ${e.getMessage}"))
