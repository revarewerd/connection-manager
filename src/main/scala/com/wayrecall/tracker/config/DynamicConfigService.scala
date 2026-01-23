package com.wayrecall.tracker.config

import zio.*
import zio.json.*
import com.wayrecall.tracker.storage.RedisClient

/**
 * Динамическая конфигурация фильтров.
 * 
 * Хранится в in-memory Ref для быстрого доступа (~10ns).
 * Redis используется только для синхронизации между инстансами через Pub/Sub.
 */
final case class FilterConfig(
    deadReckoningMaxSpeedKmh: Int = 300,
    deadReckoningMaxJumpMeters: Int = 1000,
    deadReckoningMaxJumpSeconds: Int = 1,
    stationaryMinDistanceMeters: Int = 20,
    stationaryMinSpeedKmh: Int = 2
) derives JsonCodec

/**
 * Сервис для динамической конфигурации через Redis Pub/Sub
 * 
 * ⚡ ПРОИЗВОДИТЕЛЬНОСТЬ:
 * - getFilterConfig() - ~5-10ns (Ref.get, in-memory)
 * - updateFilterConfig() - ~1-2ms (Redis Pub/Sub + Ref.set)
 */
trait DynamicConfigService:
  /**
   * Получить текущую конфигурацию фильтров.
   * ⚡ КРИТИЧНО: это Ref.get (in-memory), НЕ запрос в Redis!
   * Latency: ~5-10 наносекунд
   */
  def getFilterConfig: UIO[FilterConfig]
  
  /**
   * Обновить конфигурацию фильтров.
   * Сохраняет в Redis и публикует событие для синхронизации.
   */
  def updateFilterConfig(config: FilterConfig): Task[Unit]
  
  /**
   * Подписаться на изменения конфигурации.
   */
  def subscribeToChanges: Task[Unit]

object DynamicConfigService:
  
  private val CONFIG_KEY = "config:filters"
  private val CONFIG_CHANNEL = "config:updates"
  
  final case class Live(
      redisClient: RedisClient,
      configRef: Ref[FilterConfig]
  ) extends DynamicConfigService:
    
    // ⚡ ~5-10 наносекунд (атомарное чтение из памяти)
    override def getFilterConfig: UIO[FilterConfig] =
      configRef.get
    
    override def updateFilterConfig(config: FilterConfig): Task[Unit] =
      for
        // 1. Сохраняем в Redis Hash
        _ <- redisClient.hset(CONFIG_KEY, configToMap(config))
        
        // 2. Публикуем событие обновления через Pub/Sub
        _ <- redisClient.publish(CONFIG_CHANNEL, config.toJson)
        
        // 3. Обновляем локальный кеш
        _ <- configRef.set(config)
        
        _ <- ZIO.logInfo(s"Filter config updated: $config")
      yield ()
    
    override def subscribeToChanges: Task[Unit] =
      redisClient.subscribe(CONFIG_CHANNEL) { message =>
        ZIO.fromEither(message.fromJson[FilterConfig])
          .flatMap(config => configRef.set(config) *> ZIO.logInfo(s"Config updated via Pub/Sub: $config"))
          .catchAll(e => ZIO.logError(s"Failed to parse config update: $e"))
      }
    
    /**
     * Загрузка конфигурации из Redis при старте
     */
    def loadFromRedis: Task[Unit] =
      (for
        values <- redisClient.hgetall(CONFIG_KEY)
        config <- ZIO.attempt(mapToConfig(values))
        _ <- configRef.set(config)
        _ <- ZIO.logInfo(s"Loaded config from Redis: $config")
      yield ()).catchAll { e =>
        ZIO.logWarning(s"No config in Redis, using defaults: ${e.getMessage}")
      }
    
    private def configToMap(config: FilterConfig): Map[String, String] =
      Map(
        "deadReckoningMaxSpeedKmh" -> config.deadReckoningMaxSpeedKmh.toString,
        "deadReckoningMaxJumpMeters" -> config.deadReckoningMaxJumpMeters.toString,
        "deadReckoningMaxJumpSeconds" -> config.deadReckoningMaxJumpSeconds.toString,
        "stationaryMinDistanceMeters" -> config.stationaryMinDistanceMeters.toString,
        "stationaryMinSpeedKmh" -> config.stationaryMinSpeedKmh.toString
      )
    
    private def mapToConfig(values: Map[String, String]): FilterConfig =
      FilterConfig(
        deadReckoningMaxSpeedKmh = values.get("deadReckoningMaxSpeedKmh").map(_.toInt).getOrElse(300),
        deadReckoningMaxJumpMeters = values.get("deadReckoningMaxJumpMeters").map(_.toInt).getOrElse(1000),
        deadReckoningMaxJumpSeconds = values.get("deadReckoningMaxJumpSeconds").map(_.toInt).getOrElse(1),
        stationaryMinDistanceMeters = values.get("stationaryMinDistanceMeters").map(_.toInt).getOrElse(20),
        stationaryMinSpeedKmh = values.get("stationaryMinSpeedKmh").map(_.toInt).getOrElse(2)
      )
  
  /**
   * Live layer для DynamicConfigService
   */
  val live: ZLayer[RedisClient & AppConfig, Throwable, DynamicConfigService] =
    ZLayer.scoped {
      for
        redis <- ZIO.service[RedisClient]
        appConfig <- ZIO.service[AppConfig]
        
        // Начальная конфигурация из application.conf
        initialConfig = FilterConfig(
          deadReckoningMaxSpeedKmh = appConfig.filters.deadReckoning.maxSpeedKmh,
          deadReckoningMaxJumpMeters = appConfig.filters.deadReckoning.maxJumpMeters,
          deadReckoningMaxJumpSeconds = appConfig.filters.deadReckoning.maxJumpSeconds,
          stationaryMinDistanceMeters = appConfig.filters.stationary.minDistanceMeters,
          stationaryMinSpeedKmh = appConfig.filters.stationary.minSpeedKmh
        )
        
        configRef <- Ref.make(initialConfig)
        service = Live(redis, configRef)
        
        // Загружаем из Redis если есть
        _ <- service.loadFromRedis
        
        // Подписываемся на обновления в фоне
        _ <- service.subscribeToChanges.forkScoped
        
      yield service
    }
  
  // Accessor methods
  def getFilterConfig: URIO[DynamicConfigService, FilterConfig] =
    ZIO.serviceWithZIO(_.getFilterConfig)
  
  def updateFilterConfig(config: FilterConfig): RIO[DynamicConfigService, Unit] =
    ZIO.serviceWithZIO(_.updateFilterConfig(config))
