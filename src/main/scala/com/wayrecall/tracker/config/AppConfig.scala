package com.wayrecall.tracker.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import com.typesafe.config.ConfigFactory

/**
 * Конфигурация TCP соединений
 */
final case class TcpProtocolConfig(
    port: Int,
    enabled: Boolean
)

final case class TcpConfig(
    teltonika: TcpProtocolConfig,
    wialon: TcpProtocolConfig,
    ruptela: TcpProtocolConfig,
    navtelecom: TcpProtocolConfig,
    bossThreads: Int,
    workerThreads: Int,
    maxConnections: Int,
    keepAlive: Boolean,
    tcpNodelay: Boolean,
    connectionTimeoutSeconds: Int,
    readTimeoutSeconds: Int,
    writeTimeoutSeconds: Int,
    idleTimeoutSeconds: Int,
    idleCheckIntervalSeconds: Int
)

/**
 * Конфигурация Redis
 */
final case class RedisConfig(
    host: String,
    port: Int,
    password: Option[String],
    database: Int,
    poolSize: Int,
    vehicleTtlSeconds: Long,
    positionTtlSeconds: Long
)

/**
 * Конфигурация Kafka продюсера
 */
final case class KafkaProducerSettings(
    acks: String,
    retries: Int,
    batchSize: Int,
    lingerMs: Int,
    compressionType: String
)

final case class KafkaTopicsConfig(
    rawGpsEvents: String,
    deviceStatus: String
)

final case class KafkaConfig(
    bootstrapServers: String,
    producer: KafkaProducerSettings,
    topics: KafkaTopicsConfig
)

/**
 * Конфигурация фильтров
 */
final case class DeadReckoningFilterConfig(
    maxSpeedKmh: Int,
    maxJumpMeters: Int,
    maxJumpSeconds: Int
)

final case class StationaryFilterConfig(
    minDistanceMeters: Int,
    minSpeedKmh: Int
)

final case class FiltersConfig(
    deadReckoning: DeadReckoningFilterConfig,
    stationary: StationaryFilterConfig
)

/**
 * Конфигурация HTTP API
 */
final case class HttpConfig(
    port: Int,
    host: String
)

/**
 * Конфигурация команд
 */
final case class CommandsConfig(
    enabled: Boolean,
    timeoutSeconds: Int,
    maxRetries: Int,
    redisChannelPattern: String,
    resultsChannelPrefix: String
)

/**
 * Конфигурация логирования
 */
final case class LoggingConfig(
    level: String,
    logGpsPoints: Boolean
)

/**
 * Основная конфигурация приложения
 */
final case class AppConfig(
    tcp: TcpConfig,
    http: HttpConfig,
    redis: RedisConfig,
    kafka: KafkaConfig,
    filters: FiltersConfig,
    commands: CommandsConfig,
    logging: LoggingConfig
)

object AppConfig:
  
  /**
   * Конфигурационный дескриптор с автоматическим выводом через magnolia
   */
  private val configDescriptor: Config[AppConfig] = 
    deriveConfig[AppConfig]
  
  /**
   * ZIO Layer для загрузки конфигурации из application.conf
   * Использует zio-config для декларативного парсинга
   */
  val live: ZLayer[Any, Throwable, AppConfig] = ZLayer {
    for
      rawConfig <- ZIO.attempt(ConfigFactory.load().getConfig("connection-manager"))
      config <- TypesafeConfigProvider.fromTypesafeConfig(rawConfig)
                  .load(configDescriptor.mapKey(toKebabCase))
                  .mapError(e => new RuntimeException(s"Ошибка загрузки конфигурации: $e"))
    yield config
  }
  
  /**
   * Преобразует camelCase в kebab-case для HOCON
   */
  private def toKebabCase(s: String): String =
    s.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase
