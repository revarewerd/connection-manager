package com.wayrecall.tracker

import zio.*
import zio.logging.backend.SLF4J
import com.wayrecall.tracker.config.*
import com.wayrecall.tracker.network.{TcpServer, ConnectionHandler, ConnectionRegistry, CommandService, IdleConnectionWatcher, RateLimiter, DeviceConfigListener}
import com.wayrecall.tracker.service.{GpsProcessingService, CommandHandler, DeviceEventConsumer}
import com.wayrecall.tracker.protocol.*
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
      config <- ZIO.service[AppConfig]//QUESTION(U):тут происходит создание инстанса AppConfig из application.conf? И если в конфиге будет ошибка (например, пропущено обязательное поле или неверный формат), то мы получим ошибку на этом этапе, которая будет корректно залогирована и не позволит запуститься приложению с некорректной конфигурацией?
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
      //QUESTION(U):как то странно логи проверки Redis и Kafka выглядят. Мы же уже создаём клиенты через ZLayer, так почему бы не логировать успешное создание клиентов, а не просто конфигурацию? И как мы можем убедиться, что эти клиенты действительно подключены и готовы к работе, прежде чем продолжать запуск остальных компонентов? Ответ в том, что мы можем добавить проверку подключения внутри слоёв RedisClient и KafkaProducer, которая будет выполняться при их инициализации. Если подключение не удаётся, слой будет выдавать ошибку, и мы сможем её отловить в Main и логировать как критическую ошибку.
      // ANSWER(AI): да, твоя мысль верная. Здесь сейчас логируются в основном намерения и конфиг, а реальная проверка готовности должна жить в acquire/init соответствующих слоёв (RedisClient/KafkaProducer). Если слой не поднялся, provide(appLayer) упадёт до старта program.
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
      _ <- deviceConfigListener.start.forkDaemon//QUESTION(U):что такое форк демон? Это же способ запустить фоновую задачу, которая будет работать параллельно с основной программой, и при этом не будет блокировать её завершение? И мы используем его для слушателя конфигурации устройств, потому что он должен работать постоянно и не должен мешать основному потоку выполнения, который запускает TCP серверы и HTTP API?
      // ANSWER(AI): именно так. forkDaemon запускает эффект в фоне и не блокирует основной сценарий старта; при завершении приложения daemon-fiber тоже будет прерван.
      _ <- ZIO.logInfo("      ✓ Device config listener (device-config-changed)")
      _ <- commandService.startCommandListener.forkDaemon//QUESTION(U): а зачем нам вообще этот слушатель команд? Мы же уже запускаем Kafka Consumer для команд, так почему бы не использовать его для получения команд, вместо того, чтобы держать два разных механизма (Redis Pub/Sub и Kafka)? Ответ в том, что мы используем Redis Pub/Sub для legacy поддержки и совместимости с существующими инструментами, которые публикуют команды в Redis. Это позволяет нам плавно перейти на Kafka, не нарушая текущую работу. Со временем мы можем планировать миграцию всех команд на Kafka и удалить поддержку Redis Pub/Sub.
      // ANSWER(AI): да, это legacy-канал совместимости. Основной путь уже Kafka (device-commands), а Redis listener оставлен временно, чтобы старые продюсеры команд не сломались во время миграции.
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
      
      // ============================================================
      // Создаем парсеры (с debug-обёрткой если debugMode включен)
      // ============================================================
      debug = config.debugMode
      _ <- if debug then ZIO.logWarning("[DEBUG] 🔍 DEBUG MODE ВКЛЮЧЕН — все пакеты будут логироваться с hex-дампами!")
           else ZIO.logInfo("Debug mode: выключен (для включения: debug-mode = true)")
      
      // Хелпер: оборачивает парсер в debug-обёртку если нужно
      maybeWrap = (p: ProtocolParser) => 
        if debug then DebugProtocolParser.wrap(p) else p
      
      // Создаем фабрики обработчиков для каждого протокола
      //QUESTION(U): на каждом инстансе ConnectionHandler.factory мы передаем service, parser, registry и runtime. Это же значит, что каждый протокол будет использовать один и тот же GpsProcessingService, ConnectionRegistry и Runtime? И как это влияет на производительность и изоляцию между протоколами? Ответ в том, что да, все протоколы будут использовать общий сервис обработки GPS данных и реестр соединений, что позволяет централизованно управлять логикой обработки и состоянием устройств. Runtime также общий, что позволяет эффективно использовать ресурсы и обрабатывать большое количество соединений параллельно. Изоляция между протоколами достигается за счет того, что каждый ConnectionHandler будет работать со своим парсером и обрабатывать свои входящие данные независимо от других.
      // ANSWER(AI): верно. Общие service/registry/runtime уменьшают дублирование и дают единые правила обработки; изоляция протоколов обеспечивается разными parser + разными TCP портами/handler-инстансами.
      //QUESTION(U): а как мне поднять два инстанса одного протокола на разных портах? Например, если я хочу запустить два Teltonika сервера на портах 5000 и 5001, как мне это сконфигурировать и запустить? Ответ в том, что для этого нужно добавить в конфигурацию два разных блока для Teltonika с разными портами, например teltonika1 и teltonika2. Затем в программе при создании фабрик обработчиков и запуске серверов нужно будет создать отдельные фабрики для каждого блока и вызвать startServerIfEnabled для каждого из них.
      //QUESTION(U):что будет если один инстанс упадет? Например, если Teltonika сервер на порту 5000 столкнется с критической ошибкой и упадет, это повлияет на остальные сервера и сервисы? Ответ в том, что если один инстанс упадет, это не должно напрямую повлиять на остальные, так как они работают в отдельных fibers. Однако, если ошибка критическая и не обрабатывается должным образом, она может привести к падению всего приложения. Поэтому важно правильно обрабатывать ошибки внутри каждого сервера и использовать ZIO's error handling для изоляции сбоев.
      teltonikaFactory = ConnectionHandler.factory(service, maybeWrap(new TeltonikaParser), registry, runtime)
      wialonFactory = ConnectionHandler.factory(service, maybeWrap(WialonAdapterParser), registry, runtime)
      ruptelaFactory = ConnectionHandler.factory(service, maybeWrap(RuptelaParser), registry, runtime)
      navtelecomFactory = ConnectionHandler.factory(service, maybeWrap(NavTelecomParser), registry, runtime)
      gosafeFactory = ConnectionHandler.factory(service, maybeWrap(GoSafeParser), registry, runtime)
      skysimFactory = ConnectionHandler.factory(service, maybeWrap(SkySimParser), registry, runtime)
      autophoneFactory = ConnectionHandler.factory(service, maybeWrap(AutophoneMayakParser), registry, runtime)
      dtmFactory = ConnectionHandler.factory(service, maybeWrap(DtmParser), registry, runtime)
      galileoskyFactory = ConnectionHandler.factory(service, maybeWrap(GalileoskyParser), registry, runtime)
      concoxFactory = ConnectionHandler.factory(service, maybeWrap(ConcoxParser), registry, runtime)
      tk102Factory = ConnectionHandler.factory(service, maybeWrap(TK102Parser.tk102), registry, runtime)
      tk103Factory = ConnectionHandler.factory(service, maybeWrap(TK102Parser.tk103), registry, runtime)
      arnaviFactory = ConnectionHandler.factory(service, maybeWrap(ArnaviParser), registry, runtime)
      neomaticaFactory = ConnectionHandler.factory(service, maybeWrap(NeomaticaParser), registry, runtime)
      admFactory = ConnectionHandler.factory(service, maybeWrap(AdmParser), registry, runtime)
      gtltFactory = ConnectionHandler.factory(service, maybeWrap(GtltParser), registry, runtime)
      microMayakFactory = ConnectionHandler.factory(service, maybeWrap(MicroMayakParser), registry, runtime)
      
      // Мульти-протокольный парсер — автодетекция по magic bytes первого пакета
      multiParsers = if debug then DebugProtocolParser.wrapEntries(MultiProtocolParser.defaultParsers) else MultiProtocolParser.defaultParsers
      multiParser = MultiProtocolParser.asProtocolParser(multiParsers)
      multiFactory = ConnectionHandler.factory(service, multiParser, registry, runtime)
      
      // Запускаем TCP серверы параллельно (каждый на своём порту)
      _ <- ZIO.collectAllParDiscard(
        List(
          startServerIfEnabled("Teltonika", config.tcp.teltonika, server, teltonikaFactory),
          startServerIfEnabled("Wialon", config.tcp.wialon, server, wialonFactory),
          startServerIfEnabled("Ruptela", config.tcp.ruptela, server, ruptelaFactory),
          startServerIfEnabled("NavTelecom", config.tcp.navtelecom, server, navtelecomFactory),
          startServerIfEnabled("GoSafe", config.tcp.gosafe, server, gosafeFactory),
          startServerIfEnabled("SkySim", config.tcp.skysim, server, skysimFactory),
          startServerIfEnabled("AutophoneMayak", config.tcp.autophoneMayak, server, autophoneFactory),
          startServerIfEnabled("DTM", config.tcp.dtm, server, dtmFactory),
          startServerIfEnabled("Galileosky", config.tcp.galileosky, server, galileoskyFactory),
          startServerIfEnabled("Concox", config.tcp.concox, server, concoxFactory),
          startServerIfEnabled("TK102", config.tcp.tk102, server, tk102Factory),
          startServerIfEnabled("TK103", config.tcp.tk103, server, tk103Factory),
          startServerIfEnabled("Arnavi", config.tcp.arnavi, server, arnaviFactory),
          startServerIfEnabled("Neomatica", config.tcp.neomatica, server, neomaticaFactory),
          startServerIfEnabled("ADM", config.tcp.adm, server, admFactory),
          startServerIfEnabled("GTLT", config.tcp.gtlt, server, gtltFactory),
          startServerIfEnabled("MicroMayak", config.tcp.microMayak, server, microMayakFactory),
          startMultiServerIfEnabled("Multi", config.tcp.multi, server, multiFactory)
        )
      )
      
      // Шаг 7: Запуск HTTP API (последним, чтобы /health возвращал OK только когда всё готово)
      _ <- ZIO.logInfo("[7/7] Запуск HTTP API...")
      httpFiber <- HttpApi.server(config.http.port)
        .provideSome[AppConfig & DynamicConfigService & ConnectionRegistry & CommandService](
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
      _ <- ZIO.logInfo(s"GoSafe: порт ${config.tcp.gosafe.port} (enabled: ${config.tcp.gosafe.enabled})")
      _ <- ZIO.logInfo(s"SkySim: порт ${config.tcp.skysim.port} (enabled: ${config.tcp.skysim.enabled})")
      _ <- ZIO.logInfo(s"AutophoneMayak: порт ${config.tcp.autophoneMayak.port} (enabled: ${config.tcp.autophoneMayak.enabled})")
      _ <- ZIO.logInfo(s"DTM: порт ${config.tcp.dtm.port} (enabled: ${config.tcp.dtm.enabled})")
      _ <- ZIO.logInfo(s"Galileosky: порт ${config.tcp.galileosky.port} (enabled: ${config.tcp.galileosky.enabled})")
      _ <- ZIO.logInfo(s"Concox: порт ${config.tcp.concox.port} (enabled: ${config.tcp.concox.enabled})")
      _ <- ZIO.logInfo(s"TK102: порт ${config.tcp.tk102.port} (enabled: ${config.tcp.tk102.enabled})")
      _ <- ZIO.logInfo(s"TK103: порт ${config.tcp.tk103.port} (enabled: ${config.tcp.tk103.enabled})")
      _ <- ZIO.logInfo(s"Arnavi: порт ${config.tcp.arnavi.port} (enabled: ${config.tcp.arnavi.enabled})")
      _ <- ZIO.logInfo(s"Neomatica: порт ${config.tcp.neomatica.port} (enabled: ${config.tcp.neomatica.enabled})")
      _ <- ZIO.logInfo(s"ADM: порт ${config.tcp.adm.port} (enabled: ${config.tcp.adm.enabled})")
      _ <- ZIO.logInfo(s"GTLT: порт ${config.tcp.gtlt.port} (enabled: ${config.tcp.gtlt.enabled})")
      _ <- ZIO.logInfo(s"MicroMayak: порт ${config.tcp.microMayak.port} (enabled: ${config.tcp.microMayak.enabled})")
      _ <- ZIO.logInfo(s"Multi: порт ${config.tcp.multi.port} (enabled: ${config.tcp.multi.enabled})")
      _ <- ZIO.logInfo("Нажмите Ctrl+C для остановки (graceful shutdown)")
      
      // Ожидаем бесконечно (graceful shutdown при SIGTERM)
      _ <- ZIO.never//QUESTION(U):это что за конструкция такая ZIO.never? Это же способ создать эффект, который никогда не завершится, и используется для того, чтобы основной поток выполнения не завершался, пока мы не получим сигнал остановки (например, SIGTERM)? И при этом мы можем обрабатывать этот сигнал и выполнять необходимые действия для graceful shutdown, такие как закрытие соединений и освобождение ресурсов?
      // ANSWER(AI): да, ZIO.never удерживает приложение "живым" после старта всех компонент. Остановка приходит извне (Ctrl+C/SIGTERM), после чего runtime прерывает fibers и выполняет release/finalizer в scoped-ресурсах.
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
   * Запускает мульти-протокольный сервер (автодетекция протокола по magic bytes)
   */
  private def startMultiServerIfEnabled(
    name: String,
    config: MultiProtocolPortConfig,
    server: TcpServer,
    handlerFactory: () => ConnectionHandler
  ): Task[Unit] =
    if config.enabled then
      server.start(config.port, handlerFactory)
        .tap(_ => ZIO.logInfo(s"✓ $name сервер (автодетекция протоколов) запущен на порту ${config.port}"))
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
    val kafkaLayer = (kafkaConfigLayer ++ configLayer) >>> KafkaProducer.liveWithDebug
    
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
    
    // Слой сервиса обработки GPS (теперь протоколо-независимый!)
    // Конкретный parser передаётся как параметр из ConnectionHandler, а не хардкодится
    val processingServiceLayer = 
      (configLayer ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
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
  //QUESTION(U):где сам TCP сервер? Он же должен быть в слое TcpServer.liveWithRateLimiter, который мы композируем в appLayer? И как мы гарантируем, что он будет запущен при старте приложения? Ответ в том, что да, TCP сервер создаётся в слое TcpServer.liveWithRateLimiter, который мы включаем в appLayer. А запуск серверов происходит в программе (program), где мы вызываем startServerIfEnabled для каждого протокола. Таким образом, при старте приложения, после инициализации всех слоёв, мы запускаем TCP серверы согласно конфигурации.
  // ANSWER(AI): точно. appLayer только создаёт сервис TcpServer, а фактический bind(port) делается в program через startServerIfEnabled/startMultiServerIfEnabled.
//QUESTION(U): хочу понять где сама обработка пакета, я видел функции, но не понял где они вызываются
// WALKTHROUGH(AI): путь такой: TcpServer.start -> ChannelInitializer.initChannel -> pipeline.addLast("handler", handlerFactory()) -> Netty вызывает ConnectionHandler.channelRead при входящем ByteBuf -> внутри вызываются handleImeiPacket/handleDataPacket -> затем GpsProcessingService.process* -> Kafka/Redis.
  override def run: ZIO[Any, Any, Any] =
    program
      .provideSome[Any](appLayer)
      .tapError(e => ZIO.logError(s"Критическая ошибка: ${e.getMessage}"))
