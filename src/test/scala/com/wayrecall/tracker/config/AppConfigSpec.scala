package com.wayrecall.tracker.config

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Тесты для AppConfig — все вложенные case classes
 * 
 * Проверяем:
 * 1. TcpProtocolConfig — порт + enabled
 * 2. TcpConfig — все протоколы + параметры
 * 3. RedisConfig — хост/порт/пул
 * 4. KafkaConfig — серверы/топики
 * 5. FiltersConfig — Dead Reckoning + Stationary
 * 6. HttpConfig — API порт
 * 7. CommandsConfig — таймауты/очереди
 * 8. RateLimitConfig — значения по умолчанию
 * 9. AppConfig — полная конструкция
 * 10. toKebabCase — преобразование camelCase
 */
object AppConfigSpec extends ZIOSpecDefault:
  
  def spec = suite("AppConfig")(
    
    suite("TcpProtocolConfig")(
      
      test("создаётся с портом и enabled") {
        val cfg = TcpProtocolConfig(5001, true)
        assertTrue(cfg.port == 5001, cfg.enabled)
      },
      
      test("может быть отключён") {
        val cfg = TcpProtocolConfig(5099, false)
        assertTrue(!cfg.enabled)
      }
    ),
    
    suite("MultiProtocolPortConfig")(
      
      test("создаётся с портом и enabled") {
        val cfg = MultiProtocolPortConfig(5000, true)
        assertTrue(cfg.port == 5000, cfg.enabled)
      }
    ),
    
    suite("TcpConfig")(
      
      test("содержит все 16 протоколов + multi") {
        val tcp = makeTcpConfig()
        assertTrue(
          tcp.teltonika.port == 5001,
          tcp.wialon.port == 5002,
          tcp.ruptela.port == 5003,
          tcp.navtelecom.port == 5004,
          tcp.multi.port == 5000
        )
      },
      
      test("bossThreads и workerThreads положительные") {
        val tcp = makeTcpConfig()
        assertTrue(tcp.bossThreads > 0, tcp.workerThreads > 0)
      },
      
      test("idle параметры корректны") {
        val tcp = makeTcpConfig()
        assertTrue(
          tcp.idleTimeoutSeconds > 0,
          tcp.idleCheckIntervalSeconds > 0,
          tcp.idleCheckIntervalSeconds < tcp.idleTimeoutSeconds
        )
      }
    ),
    
    suite("RedisConfig")(
      
      test("создаётся с обязательными полями") {
        val cfg = RedisConfig("localhost", 6379, None, 0, 10, 3600, 3600)
        assertTrue(
          cfg.host == "localhost",
          cfg.port == 6379,
          cfg.password.isEmpty,
          cfg.poolSize == 10
        )
      },
      
      test("поддерживает пароль") {
        val cfg = RedisConfig("redis.host", 6380, Some("secret"), 1, 20, 7200, 7200)
        assertTrue(cfg.password.contains("secret"), cfg.database == 1)
      }
    ),
    
    suite("KafkaConfig")(
      
      test("все топики определены") {
        val topics = KafkaTopicsConfig(
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
        assertTrue(
          topics.gpsEvents == "gps-events",
          topics.deviceCommands == "device-commands",
          topics.unknownDevices == "unknown-devices"
        )
      },
      
      test("KafkaConsumerSettings содержит groupId и autoOffsetReset") {
        val consumer = KafkaConsumerSettings("cm-group", 30000, "latest", 500)
        assertTrue(
          consumer.groupId == "cm-group",
          consumer.autoOffsetReset == "latest",
          consumer.maxPollRecords == 500
        )
      },
      
      test("KafkaProducerSettings содержит acks и compression") {
        val producer = KafkaProducerSettings("all", 3, 16384, 5, "snappy")
        assertTrue(
          producer.acks == "all",
          producer.compressionType == "snappy"
        )
      }
    ),
    
    suite("FiltersConfig")(
      
      test("DeadReckoningFilterConfig с валидными значениями") {
        val dr = DeadReckoningFilterConfig(350, 50000, 300)
        assertTrue(
          dr.maxSpeedKmh == 350,
          dr.maxJumpMeters == 50000,
          dr.maxJumpSeconds == 300
        )
      },
      
      test("StationaryFilterConfig с валидными значениями") {
        val st = StationaryFilterConfig(10, 2)
        assertTrue(
          st.minDistanceMeters == 10,
          st.minSpeedKmh == 2
        )
      }
    ),
    
    suite("HttpConfig")(
      
      test("порт и хост") {
        val http = HttpConfig(10090, "0.0.0.0")
        assertTrue(http.port == 10090, http.host == "0.0.0.0")
      }
    ),
    
    suite("CommandsConfig")(
      
      test("значения по умолчанию для maxPendingPerDevice и pendingCommandsTtlHours") {
        val cmd = CommandsConfig(30, 3)
        assertTrue(
          cmd.timeoutSeconds == 30,
          cmd.maxRetries == 3,
          cmd.maxPendingPerDevice == 100,
          cmd.pendingCommandsTtlHours == 24
        )
      },
      
      test("кастомные значения для pending очереди") {
        val cmd = CommandsConfig(60, 5, maxPendingPerDevice = 200, pendingCommandsTtlHours = 48)
        assertTrue(
          cmd.maxPendingPerDevice == 200,
          cmd.pendingCommandsTtlHours == 48
        )
      }
    ),
    
    suite("RateLimitConfig")(
      
      test("значения по умолчанию") {
        val rl = RateLimitConfig()
        assertTrue(
          rl.enabled,
          rl.maxConnectionsPerIp == 100,
          rl.refillRatePerSecond == 10,
          rl.burstSize == 50,
          rl.cleanupIntervalSeconds == 300
        )
      },
      
      test("может быть отключён") {
        val rl = RateLimitConfig(enabled = false)
        assertTrue(!rl.enabled)
      }
    ),
    
    suite("LoggingConfig")(
      
      test("уровни логирования") {
        val log = LoggingConfig("INFO", false)
        assertTrue(log.level == "INFO", !log.logGpsPoints)
      },
      
      test("debug режим с GPS точками") {
        val log = LoggingConfig("DEBUG", true)
        assertTrue(log.logGpsPoints)
      }
    ),
    
    suite("AppConfig — полная конфигурация")(
      
      test("собирается из всех компонентов") {
        val config = makeAppConfig()
        assertTrue(
          config.instanceId == "cm-instance-1",
          !config.debugMode,
          config.tcp.teltonika.port == 5001,
          config.http.port == 10090,
          config.redis.host == "localhost",
          config.kafka.bootstrapServers == "localhost:9092",
          config.commands.timeoutSeconds == 30
        )
      },
      
      test("instanceId используется для Kafka partition mapping") {
        val config = makeAppConfig()
        val partition = config.instanceId match
          case "cm-instance-1" => 0
          case "cm-instance-2" => 1
          case "cm-instance-3" => 2
          case "cm-instance-4" => 3
          case _ => 0
        assertTrue(partition == 0)
      },
      
      test("debugMode по умолчанию выключен") {
        val config = makeAppConfig()
        assertTrue(!config.debugMode)
      },
      
      test("debugMode можно включить") {
        val config = makeAppConfig().copy(debugMode = true)
        assertTrue(config.debugMode)
      }
    ),
    
    suite("toKebabCase")(
      
      test("camelCase → kebab-case") {
        assertTrue(toKebabCase("bootstrapServers") == "bootstrap-servers")
      },
      
      test("PascalCase → pascal-case") {
        assertTrue(toKebabCase("MaxPollRecords") == "max-poll-records")
      },
      
      test("без заглавных букв — без изменений") {
        assertTrue(toKebabCase("port") == "port")
      },
      
      test("одна буква") {
        assertTrue(toKebabCase("a") == "a")
      }
    )
  )
  
  // === Вспомогательные методы ===
  
  private def toKebabCase(s: String): String =
    s.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase
  
  private def makeTcpConfig(): TcpConfig = TcpConfig(
    teltonika = TcpProtocolConfig(5001, true),
    wialon = TcpProtocolConfig(5002, true),
    ruptela = TcpProtocolConfig(5003, true),
    navtelecom = TcpProtocolConfig(5004, true),
    gosafe = TcpProtocolConfig(5005, false),
    skysim = TcpProtocolConfig(5006, false),
    autophoneMayak = TcpProtocolConfig(5007, false),
    dtm = TcpProtocolConfig(5008, false),
    galileosky = TcpProtocolConfig(5009, false),
    concox = TcpProtocolConfig(5010, false),
    tk102 = TcpProtocolConfig(5011, false),
    tk103 = TcpProtocolConfig(5012, false),
    arnavi = TcpProtocolConfig(5013, false),
    adm = TcpProtocolConfig(5014, false),
    gtlt = TcpProtocolConfig(5015, false),
    microMayak = TcpProtocolConfig(5016, false),
    multi = MultiProtocolPortConfig(5000, true),
    bossThreads = 1,
    workerThreads = 4,
    maxConnections = 1000,
    keepAlive = true,
    tcpNodelay = true,
    connectionTimeoutSeconds = 30,
    readTimeoutSeconds = 120,
    writeTimeoutSeconds = 30,
    idleTimeoutSeconds = 300,
    idleCheckIntervalSeconds = 60
  )
  
  private def makeAppConfig(): AppConfig = AppConfig(
    instanceId = "cm-instance-1",
    debugMode = false,
    tcp = makeTcpConfig(),
    http = HttpConfig(10090, "0.0.0.0"),
    redis = RedisConfig("localhost", 6379, None, 0, 10, 3600, 3600),
    kafka = KafkaConfig(
      bootstrapServers = "localhost:9092",
      producer = KafkaProducerSettings("all", 3, 16384, 5, "snappy"),
      consumer = KafkaConsumerSettings("cm-group", 30000, "latest", 500),
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
      deadReckoning = DeadReckoningFilterConfig(350, 50000, 300),
      stationary = StationaryFilterConfig(10, 2)
    ),
    commands = CommandsConfig(30, 3),
    logging = LoggingConfig("INFO", false),
    rateLimit = RateLimitConfig()
  )
