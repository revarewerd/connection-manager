# Connection Manager — Архитектура v6.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-11` | Версия: `6.0`

## Обзор

Connection Manager — микросервис приёма и первичной обработки GPS-данных.
Функционирует как Netty TCP-сервер на 16 протокольных портах + 1 multi-detect.
Поддерживает 18 GPS-протоколов, 10 типов команд для трекеров,
5 Kafka-топиков для маршрутизации GPS-данных.

**Оптимизирован для 100K+ одновременных TCP-соединений** (v5.0):
- Epoll/KQueue native transport (O(1) per event вместо O(n) NIO)
- ConcurrentHashMap + AtomicLong в ConnectionRegistry (0 аллокаций на hot path)
- Async fork + Semaphore в ConnectionHandler (Netty I/O thread никогда не блокируется)
- Кэш JSON сериализации (1× вместо 3× per GPS point)

**Новое в v6.0:**
- **CmMetrics** — 16 метрик (LongAdder + AtomicLong), Prometheus text exposition через `/api/metrics`
- **ФП-аудит фиксы** — `.toOption` → явный match + `ZIO.logWarning` (CommandHandler, RedisClient)
- **Безопасный парсинг** — `.toDoubleOption.getOrElse(0.0)` вместо `.toDouble.toInt` (ArnaviParser, GtltParser, GoSafeParser)
- **Clock.currentTime** в getIdleConnections вместо System.currentTimeMillis (TestClock совместимость)

## Ключевые метрики v6.0

| Компонент | Описание |
|---|---|
| **18 протоколов** | Teltonika, Wialon (3), Ruptela, NavTelecom, GoSafe, SkySim, Mayak, DTM, Galileosky, Concox, TK102/103, Arnavi, ADM, GTLT, MicroMayak |
| **10 типов команд** | Reboot, SetInterval, RequestPosition, SetOutput, SetParameter, Password, DeviceConfig, ChangeServer, Custom |
| **5 энкодеров** | Teltonika (Codec 12), NavTelecom (NTCB FLEX), DTM (binary), Ruptela (binary), Wialon (text) |
| **5 GPS-топиков** | gps-events, gps-events-rules, gps-events-retranslation, gps-parse-errors, unknown-gps-events |
| **16 CmMetrics** | activeConnections, totalConnections, packetsReceived, gpsPointsReceived, gpsPointsPublished, parseErrors, kafkaPublishSuccess, kafkaPublishErrors, redisOperations, unknownDevices, commandsSent, unknownDevicePackets, uptime |
| **560 тестов** | 0 failures, полный regression |

## Общая архитектура

```mermaid
flowchart TB
    subgraph "TCP Layer — Netty (16+1 портов)"
        P1["5001: Teltonika"]
        P2["5002: Wialon IPS"]
        P3["5003: Ruptela"]
        P4["5004: NavTelecom"]
        P5["5005: GoSafe"]
        P6["5006: SkySim"]
        P7["5007: AutophoneMayak"]
        P8["5008: DTM"]
        P9["5009-5016: Galileosky,<br/>Concox, TK102/103,<br/>Arnavi, ADM, GTLT,<br/>MicroMayak"]
        PM["5100: MultiProtocol"]
    end

    subgraph "Handler Layer"
        CH["ConnectionHandler<br/>(per connection, fork + Semaphore)"]
        CS["ConnectionState<br/>(in-memory ZIO Ref)"]
        CR["ConnectionRegistry<br/>(ConcurrentHashMap + AtomicLong)"]
    end

    subgraph "Processing Layer — ZIO Effects"
        GPS["GpsProcessingService"]
        DR["DeadReckoningFilter"]
        SF["StationaryFilter"]
    end

    subgraph "Command System"
        CE["CommandEncoder<br/>(factory)"]
        TE["TeltonikaEncoder"]
        NE["NavTelecomEncoder"]
        DE["DtmEncoder"]
        RE["RuptelaEncoder"]
        WE["WialonEncoder"]
        RO["ReceiveOnlyEncoder<br/>(13 протоколов)"]
    end

    subgraph "Protocol Parsers (18)"
        PP["ProtocolParser trait"]
        MP["MultiProtocolParser<br/>(auto-detect)"]
    end

    subgraph "External Systems"
        REDIS[("Redis 7.0")]
        KAFKA[("Kafka (10 топиков)")]
        HTTP["HTTP API<br/>:10090"]
    end

    P1 & P2 & P3 & P4 & P5 & P6 & P7 & P8 & P9 & PM --> CH
    CH --> CS & CR
    CH -->|"parser param"| GPS
    GPS --> DR & SF
    GPS --> KAFKA
    CH --> REDIS
    CE --> TE & NE & DE & RE & WE & RO
    PP --> MP
    HTTP --> GPS & CR
```

## Архитектура команд v4.0

```mermaid
classDiagram
    class Command {
        <<sealed trait>>
        +commandId: String
        +imei: String
        +delivery: CommandDelivery
        +priority: Int
    }

    class CommandDelivery {
        <<enum>>
        Tcp
        Sms
        Auto
    }

    Command <|-- RebootCommand
    Command <|-- SetIntervalCommand
    Command <|-- RequestPositionCommand
    Command <|-- SetOutputCommand
    Command <|-- SetParameterCommand
    Command <|-- PasswordCommand
    Command <|-- DeviceConfigCommand
    Command <|-- ChangeServerCommand
    Command <|-- CustomCommand

    class CommandEncoder {
        <<trait>>
        +protocolName: String
        +encode(cmd): IO[ProtocolError, ByteBuf]
        +supports(cmd): Boolean
        +capabilities: CommandCapability
    }

    CommandEncoder <|-- TeltonikaEncoder
    CommandEncoder <|-- NavTelecomEncoder
    CommandEncoder <|-- DtmEncoder
    CommandEncoder <|-- RuptelaEncoder
    CommandEncoder <|-- WialonEncoder
    CommandEncoder <|-- ReceiveOnlyEncoder

    class CommandCapability {
        +protocol: String
        +supportedCommands: Set[String]
        +tcpCommands: Set[String]
        +smsCommands: Set[String]
        +requiresAuth: Boolean
    }

    Command --> CommandDelivery
    CommandEncoder --> CommandCapability
```

### Матрица поддержки команд по протоколам

| Команда | Teltonika | NavTelecom | DTM | Ruptela | Wialon | Остальные 13 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| RebootCommand | ✅ TCP | — | — | ✅ TCP | — | — |
| SetIntervalCommand | ✅ TCP | — | — | ✅ TCP | — | — |
| RequestPositionCommand | ✅ TCP | — | — | ✅ TCP | — | — |
| SetOutputCommand | ✅ TCP | ✅ TCP | ✅ TCP | ✅ TCP | — | — |
| SetParameterCommand | ✅ TCP | — | — | — | — | — |
| PasswordCommand | — | ✅ TCP | — | — | — | — |
| DeviceConfigCommand | — | — | — | ✅ TCP | — | — |
| ChangeServerCommand | — | — | — | — | — | — |
| CustomCommand | ✅ TCP | ✅ TCP | — | ✅ TCP | ✅ TCP | — |

### Поток команды (Command Flow)

```mermaid
sequenceDiagram
    participant DM as Device Manager
    participant K as Kafka
    participant CH as CommandHandler
    participant CE as CommandEncoder
    participant T as GPS Tracker

    DM->>K: device-commands (JSON)
    K->>CH: consume (Static Partition)
    CH->>CH: findConnection(imei)
    CH->>CE: CommandEncoder.forProtocol(protocol)
    CE->>CE: encoder.supports(command)?
    alt Поддерживается
        CE->>CE: encoder.encode(command)
        CE-->>CH: ByteBuf (binary frame)
        CH->>T: ctx.writeAndFlush(buffer)
        T-->>CH: ACK/Response
        CH->>K: command-audit (Success)
    else Не поддерживается
        CE-->>CH: UnsupportedProtocol error
        CH->>K: command-audit (Rejected)
    end
```

## Kafka-маршрутизация GPS-данных v4.0

```mermaid
flowchart LR
    subgraph "GPS Processing Pipeline"
        RAW["Raw GPS Packet"]
        PARSE["parser.parseData()"]
        DR["Dead Reckoning<br/>Filter"]
        SF["Stationary<br/>Filter"]
    end

    subgraph "Kafka Topics"
        GE["gps-events<br/>(ВСЕ точки)"]
        GR["gps-events-rules<br/>(только moving)"]
        GT["gps-events-retranslation<br/>(только moving)"]
        PE["gps-parse-errors<br/>(ошибки парсинга)"]
        UE["unknown-gps-events<br/>(незарегистрированные)"]
    end

    subgraph "Consumers"
        HW["History Writer<br/>(TimescaleDB)"]
        RC["Rule Checker<br/>(геозоны, скорость)"]
        IS["Integration Service<br/>(Wialon, webhooks)"]
        MON["Monitoring<br/>(Grafana)"]
    end

    RAW --> PARSE
    PARSE -->|"ошибка"| PE
    PARSE -->|"OK"| DR
    DR -->|"отфильтровано<br/>(isValid=false)"| GE
    DR -->|"OK"| SF
    SF -->|"стоянка<br/>(isMoving=false)"| GE
    SF -->|"движение<br/>(isMoving=true)"| GE
    SF -->|"moving + hasGeozones"| GR
    SF -->|"moving + hasRetranslation"| GT

    GE --> HW
    GR --> RC
    GT --> IS
    PE --> MON
    UE --> HW
```

### Правила маршрутизации

| Топик | Что попадает | Условие |
|---|---|---|
| **gps-events** | ВСЕ точки (valid + invalid + stationary) | Всегда. isValid/isMoving как маркеры |
| **gps-events-rules** | Только валидные + движущиеся | `isValid && isMoving && (hasGeozones \|\| hasSpeedRules)` |
| **gps-events-retranslation** | Только валидные + движущиеся | `isValid && isMoving && hasRetranslation` |
| **gps-parse-errors** | Ошибки парсинга | `parser.parseData()` вернул ProtocolError |
| **unknown-gps-events** | Точки от незарегистрированных | IMEI не найден в Redis `device:{imei}` |

## Горячий путь: GPS-пакет → Kafka

```mermaid
sequenceDiagram
    participant T as GPS Tracker
    participant N as Netty (TCP)
    participant CH as ConnectionHandler
    participant CS as ConnectionState
    participant GPS as GpsProcessingService
    participant F as Filters
    participant K as Kafka

    T->>N: TCP Data Packet
    N->>CH: channelRead(buffer)
    CH->>CS: get state (Ref.get)
    Note over CH: TTL check<br/>(1 час)
    CH->>GPS: processDataPacket(buffer, parser)
    GPS->>GPS: parser.parseData(buffer, imei)
    alt Ошибка парсинга
        GPS->>K: gps-parse-errors ❌
    else Успешно
        loop Для каждой точки
            GPS->>F: DeadReckoningFilter
            alt Отфильтровано (invalid)
                GPS->>K: gps-events (isValid=false) ⚠️
            else Прошла
                GPS->>F: StationaryFilter
                GPS->>K: gps-events (isValid=true) ✅
                opt isMoving=true
                    GPS->>K: gps-events-rules (если нужно)
                    GPS->>K: gps-events-retranslation (если нужно)
                end
            end
        end
    end
    GPS-->>CH: (validPoints, totalCount)
    CH->>CS: withLastPosition (Ref.update)
    CH->>N: ACK(totalCount)
    N->>T: ACK
```

## Холодный путь: IMEI-аутентификация

```mermaid
sequenceDiagram
    participant T as GPS Tracker
    participant CH as ConnectionHandler
    participant GPS as GpsProcessingService
    participant R as Redis
    participant K as Kafka
    participant CS as ConnectionState

    T->>CH: IMEI Packet
    CH->>GPS: processImeiPacket(buffer, parser)
    GPS->>GPS: parser.parseImei(buffer)
    GPS->>R: HGETALL device:{imei}
    alt Устройство найдено
        R-->>GPS: DeviceData (vehicleId, orgId, flags)
        GPS-->>CH: (imei, vehicleId, prevPos, deviceData)
        CH->>CS: withImeiAndDeviceData(...)
        CH->>GPS: onConnect(imei, protocolName)
        GPS->>R: HMSET connection fields
        GPS->>K: device-status (ONLINE)
        CH->>T: ACK (success)
    else Устройство НЕ найдено
        R-->>GPS: None
        GPS-->>CH: UnknownDevice error
        CH->>CS: withUnknownDevice(true)
        CH->>GPS: onUnknownDevice(imei, protocol)
        GPS->>K: unknown-devices
        CH->>T: ACK (продолжаем приём данных)
    end
```

## Жизненный цикл соединения

```mermaid
stateDiagram-v2
    [*] --> Connected: TCP connect
    Connected --> WaitingImei: channelActive()
    WaitingImei --> Authenticating: IMEI packet received
    Authenticating --> Authenticated: device found in Redis
    Authenticating --> UnknownDevice: device NOT in Redis

    Authenticated --> Receiving: GPS packets
    UnknownDevice --> ReceivingUnknown: GPS packets

    Receiving --> Receiving: more data
    Receiving --> ContextRefresh: TTL expired (1 hour)
    ContextRefresh --> Receiving: HGETALL device:{imei}
    ReceivingUnknown --> ReceivingUnknown: more data

    Receiving --> Disconnected: idle timeout / error / admin
    ReceivingUnknown --> Disconnected: idle timeout / error
    
    Disconnected --> [*]: cleanup
    
    note right of Authenticated: Redis: 1× HGETALL\nIn-memory: all subsequent reads
    note right of ContextRefresh: Обновляет DeviceData\nкаждый час
```

## Redis → In-Memory миграция (v3.0+)

| Операция | v2.x (Redis) | v3.0+ (In-Memory) |
|---|---|---|
| Позиция трекера | HMSET + SETEX на каждую точку | `ConnectionState.lastPosition` |
| DeviceData | HGETALL на каждый пакет | Кэш в ConnectionState (TTL 1 час) |
| Предыдущая позиция | HGETALL `position:{imei}` | `state.getPreviousPosition` |
| **Итого ops/день** | **~864M** | **~10K** |

**Что осталось в Redis:**
- `device:{imei}` HASH — 1× при аутентификации + refresh по TTL
- `connection:*` HASH — регистрация/разрегистрация
- Pub/Sub — команды, инвалидация конфигурации

## MultiProtocolParser — автодетект

```mermaid
flowchart TB
    subgraph "Quick Detect (O(1))"
        B1{"byte[0:2]"}
        B1 -->|"0x000F"| TEL[Teltonika]
        B1 -->|"0x2A3E"| NAV[NavTelecom]
        B1 -->|"0xF0xx"| SKY[SkySim]
        B1 -->|"0x4D0x"| MAY[AutophoneMayak]
        B1 -->|"0x7B0x"| DTM[DTM]
        B1 -->|"0x2324"| WIA[Wialon '#']
        B1 -->|"0x2A47"/ "0x2447"| GOS[GoSafe]
    end

    subgraph "Full Detect (fallback)"
        B1 -->|"не определён"| FD["рекурсивный перебор<br/>16 парсеров"]
        FD --> RESULT["detectedParser"]
    end
```

## Структура файлов v6.0

```
src/main/scala/com/wayrecall/tracker/
├── Main.scala                          # Точка входа, ZIO Layers
├── api/
│   └── HttpApi.scala                   # HTTP API (20+ endpoints) + CmMetrics output
├── command/                            # ★ NEW v4.0 — Command Encoders
│   ├── CommandEncoder.scala            # Базовый trait + factory + ReceiveOnlyEncoder
│   ├── TeltonikaEncoder.scala          # Codec 12 (TCP text commands)
│   ├── NavTelecomEncoder.scala         # NTCB FLEX (binary auth + IOSwitch)
│   ├── DtmEncoder.scala               # Binary IOSwitch (0x7B frame)
│   ├── RuptelaEncoder.scala            # Binary commands (0x65-0x67)
│   └── WialonEncoder.scala             # Text #M# commands
├── config/
│   ├── AppConfig.scala                 # HOCON конфигурация
│   └── DynamicConfigService.scala      # Динамическая конфигурация фильтров
├── domain/
│   ├── Protocol.scala                  # Enum протоколов (18), ошибки
│   ├── GpsPoint.scala                  # GpsPoint, GpsRawPoint, GpsEventMessage,
│   │                                   # GpsParseErrorEvent ★ NEW v4.0
│   ├── ParseError.scala                # ADT ошибок парсинга (10 типов)
│   ├── Command.scala                   # 10 типов команд, CommandDelivery,
│   │                                   # CommandCapability ★ REWRITTEN v4.0
│   └── Vehicle.scala                   # VehicleInfo
├── filter/
│   ├── DeadReckoningFilter.scala       # Фильтр телепортаций
│   └── StationaryFilter.scala          # Фильтр стоянок
├── network/
│   ├── ConnectionHandler.scala         # Netty handler + GpsProcessingService
│   │                                   # ★ v5.0: fork + Semaphore, zipPar Kafka, JSON cache
│   │                                   # ★ v6.0: CmMetrics instrumentation
│   ├── ConnectionRegistry.scala        # ConcurrentHashMap + AtomicLong (lock-free)
│   │                                   # ★ v5.0+v6.0: Clock.currentTime в getIdleConnections
│   ├── IdleConnectionWatcher.scala     # Фоновый fiber, параллельный disconnect (32)
│   ├── RateLimiter.scala               # Token Bucket (O(1) prepend)
│   ├── CommandService.scala            # Отправка команд (Redis Pub/Sub)
│   │                                   # ★ UPDATED v4.0: AwaitingCommand
│   └── TcpServer.scala                 # Netty bootstrap: Epoll/KQueue + socket tuning
│                                       # ★ REWRITTEN v5.0: SO_RCVBUF=4K, WaterMark, Pooled
├── protocol/                           # 18 протоколов
│   ├── ProtocolParser.scala            # Общий trait парсера
│   ├── TeltonikaParser.scala           # Teltonika Codec 8/8E
│   ├── WialonParser.scala              # Wialon IPS (text)
│   ├── WialonBinaryParser.scala        # Wialon Binary
│   ├── WialonAdapterParser.scala       # Auto-detect Wialon text/binary
│   ├── RuptelaParser.scala             # Ruptela GPS
│   ├── NavTelecomParser.scala          # NavTelecom FLEX
│   ├── GoSafeParser.scala              # GoSafe ASCII
│   ├── SkySimParser.scala              # SkySim (SkyPatrol)
│   ├── AutophoneMayakParser.scala      # Автофон Маяк
│   ├── DtmParser.scala                 # ДТМ (DTM)
│   ├── GalileoskyParser.scala          # Galileosky
│   ├── ConcoxParser.scala              # Concox GT06
│   ├── TK102Parser.scala               # TK102/TK103
│   ├── ArnaviParser.scala              # Arnavi
│   ├── AdmParser.scala                 # ADM
│   ├── GtltParser.scala                # Queclink GTLT
│   ├── MicroMayakParser.scala          # МикроМаяк
│   └── MultiProtocolParser.scala       # AutoDetect парсер
├── service/                            # ★ NEW v6.0
│   └── CmMetrics.scala                 # 16 метрик: LongAdder + AtomicLong, Prometheus output
└── storage/
    ├── KafkaProducer.scala             # 10 publish-методов, buffer.memory=256MB
    │                                   # ★ v5.0: buildProducerProps, max.in.flight=10
    └── RedisClient.scala               # Lettuce Redis операции (async fork)
                                        # ★ v6.0: .toOption → explicit match + logWarning
```
