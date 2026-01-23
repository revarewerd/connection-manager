# 🏗️ Архитектурный анализ Connection Manager Service

> **Версия:** 2.1 (Pure FP)  
> **Дата анализа:** 16 января 2026  
> **Язык:** Scala 3.4.0 с ZIO 2.x

---

## 📋 Оглавление

1. [Общий обзор системы](#1-общий-обзор-системы)
2. [Архитектурные диаграммы](#2-архитектурные-диаграммы)
3. [Детальное описание компонентов](#3-детальное-описание-компонентов)
4. [Потоки данных](#4-потоки-данных)
5. [Анализ чистоты кода (FP)](#5-анализ-чистоты-кода-fp)
6. [Обнаруженные проблемы и упущения](#6-обнаруженные-проблемы-и-упущения)
7. [Рекомендуемые улучшения](#7-рекомендуемые-улучшения)
8. [Матрица зависимостей](#8-матрица-зависимостей)
9. [Checklist полноты реализации](#9-checklist-полноты-реализации)

---

## 1. Общий обзор системы

### 1.1 Назначение

**Connection Manager Service** — высокопроизводительный сервис для приёма и обработки GPS-данных от трекеров различных производителей. Реализован на принципах чистого функционального программирования (Pure FP).

### 1.2 Ключевые характеристики

| Параметр | Значение |
|----------|----------|
| **Язык** | Scala 3.4.0 |
| **Эффект-система** | ZIO 2.0.20 |
| **TCP-сервер** | Netty 4.1.104 |
| **Хранилище** | Redis (Lettuce 6.3.2) |
| **Очередь событий** | Apache Kafka 3.6.1 |
| **HTTP API** | zio-http 3.0.0-RC4 |
| **Конфигурация** | Typesafe Config + zio-config |

### 1.3 Поддерживаемые протоколы

| Протокол | Порт | Формат | Статус |
|----------|------|--------|--------|
| **Teltonika** (Codec 8/8E) | 5001 | Бинарный | ✅ Полная поддержка |
| **Wialon IPS** | 5002 | Текстовый | ✅ Полная поддержка |
| **Ruptela** | 5003 | Бинарный | ✅ Полная поддержка |
| **NavTelecom FLEX** | 5004 | Бинарный | ✅ Полная поддержка |

---

## 2. Архитектурные диаграммы

### 2.1 Общая архитектура системы (C4 Container)

```mermaid
graph TB
    subgraph Внешние_системы["🌐 Внешние системы"]
        T1["📡 GPS Трекер<br/>Teltonika"]
        T2["📡 GPS Трекер<br/>Wialon"]
        T3["📡 GPS Трекер<br/>Ruptela"]
        T4["📡 GPS Трекер<br/>NavTelecom"]
        ADMIN["👨‍💼 Администратор<br/>(HTTP API)"]
        CONSUMER["📊 Потребители<br/>(Analytics, Alerts)"]
    end

    subgraph ConnectionManager["🖥️ Connection Manager Service v2.1"]
        subgraph TCP_Layer["TCP Слой (Netty)"]
            TS["🔌 TcpServer<br/>EventLoopGroup"]
            CH["📦 ConnectionHandler<br/>(per connection)"]
        end
        
        subgraph Protocol_Layer["Протокольный слой"]
            TP["Teltonika<br/>Parser"]
            WP["Wialon<br/>Parser"]
            RP["Ruptela<br/>Parser"]
            NP["NavTelecom<br/>Parser"]
        end
        
        subgraph Processing_Layer["Слой обработки"]
            GPS["🛰️ GpsProcessingService<br/>(валидация + фильтрация)"]
            DRF["Dead Reckoning<br/>Filter"]
            SF["Stationary<br/>Filter"]
        end
        
        subgraph Management_Layer["Слой управления"]
            CR["📋 ConnectionRegistry<br/>(Ref[Map])"]
            CS["📤 CommandService<br/>(Ref[Map])"]
            ICW["⏱️ IdleConnectionWatcher"]
            DCS["⚙️ DynamicConfigService<br/>(Ref + Redis Pub/Sub)"]
        end
        
        subgraph API_Layer["HTTP API слой"]
            HTTP["🌐 HttpApi<br/>(zio-http)"]
        end
    end

    subgraph Infrastructure["🗄️ Инфраструктура"]
        REDIS[("🔴 Redis<br/>State + Pub/Sub")]
        KAFKA[("📨 Kafka<br/>Events")]
    end

    %% Подключения трекеров
    T1 -->|TCP :5001| TS
    T2 -->|TCP :5002| TS
    T3 -->|TCP :5003| TS
    T4 -->|TCP :5004| TS
    
    %% Внутренние связи TCP
    TS --> CH
    CH --> TP & WP & RP & NP
    
    %% Обработка данных
    TP & WP & RP & NP --> GPS
    GPS --> DRF & SF
    
    %% Управление соединениями
    CH <--> CR
    CH --> ICW
    
    %% Хранилище
    GPS --> REDIS
    GPS --> KAFKA
    DCS <--> REDIS
    CS <--> REDIS
    ICW --> KAFKA
    
    %% HTTP API
    ADMIN -->|HTTP :8080| HTTP
    HTTP <--> DCS & CR & CS
    
    %% Потребители Kafka
    KAFKA --> CONSUMER

    %% Стилизация
    classDef external fill:#e1f5fe,stroke:#01579b,stroke-width:2px
    classDef service fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px
    classDef storage fill:#fff3e0,stroke:#e65100,stroke-width:2px
    classDef protocol fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    
    class T1,T2,T3,T4,ADMIN,CONSUMER external
    class TS,CH,GPS,CR,CS,ICW,DCS,HTTP,DRF,SF service
    class REDIS,KAFKA storage
    class TP,WP,RP,NP protocol
```

### 2.2 Потоковая диаграмма обработки GPS данных

```mermaid
sequenceDiagram
    autonumber
    participant T as 📡 GPS Трекер
    participant N as 🔌 Netty (TcpServer)
    participant CH as 📦 ConnectionHandler
    participant PP as 🔧 ProtocolParser
    participant CR as 📋 ConnectionRegistry
    participant GPS as 🛰️ GpsProcessingService
    participant DRF as 🚫 DeadReckoningFilter
    participant SF as 📍 StationaryFilter
    participant R as 🔴 Redis
    participant K as 📨 Kafka

    Note over T,K: === Фаза 1: Установка соединения ===
    T->>N: TCP Connect
    N->>CH: channelActive()
    CH->>CH: Создать Ref[ConnectionState]
    
    Note over T,K: === Фаза 2: Аутентификация (IMEI) ===
    T->>N: IMEI пакет
    N->>CH: channelRead(ByteBuf)
    CH->>PP: parseImei(buffer)
    PP-->>CH: IMEI (String)
    CH->>R: getVehicleId(imei)
    R-->>CH: vehicleId (Long)
    
    alt IMEI валидный
        CH->>CR: register(imei, ctx, parser)
        Note right of CR: Ref.update:<br/>Map + (imei → entry)
        CH->>R: registerConnection(ConnectionInfo)
        CH->>K: publishDeviceStatus(isOnline=true)
        CH->>T: imeiAck(true)
    else IMEI невалидный
        CH->>T: imeiAck(false)
        CH->>N: ctx.close()
    end
    
    Note over T,K: === Фаза 3: Приём GPS данных ===
    loop Каждый пакет данных
        T->>N: Data пакет
        N->>CH: channelRead(ByteBuf)
        CH->>CR: updateLastActivity(imei)
        Note right of CR: Обновить lastActivityAt<br/>для idle timeout
        CH->>PP: parseData(buffer, imei)
        PP-->>CH: List[GpsRawPoint]
        
        loop Для каждой точки
            CH->>GPS: processPoint(raw, vehicleId, prev)
            GPS->>DRF: validateWithPrev(raw, prev)
            
            alt Точка валидна
                GPS->>SF: shouldPublish(point, prev)
                GPS->>R: setPosition(point)
                
                alt Движение (shouldPublish = true)
                    GPS->>K: publishGpsEvent(point)
                end
            else Точка отфильтрована
                Note right of DRF: Превышена скорость,<br/>телепортация и т.д.
            end
        end
        
        CH->>T: ack(recordCount)
    end
    
    Note over T,K: === Фаза 4: Отключение ===
    T->>N: TCP Close / Timeout
    N->>CH: channelInactive()
    CH->>CR: unregister(imei)
    CH->>R: unregisterConnection(imei)
    CH->>K: publishDeviceStatus(isOnline=false, reason)
```

### 2.3 Диаграмма ZIO Layer композиции

```mermaid
graph BT
    subgraph Base_Layers["📦 Базовые слои"]
        AC["AppConfig.live"]
    end
    
    subgraph Config_Projections["🔧 Проекции конфигурации"]
        TCP["TcpConfig"]
        RC["RedisConfig"]
        KC["KafkaConfig"]
    end
    
    subgraph Infrastructure_Layers["🗄️ Инфраструктурные слои"]
        TS["TcpServer.live"]
        RDS["RedisClient.live"]
        KFK["KafkaProducer.live"]
    end
    
    subgraph Registry_Layers["📋 Слои реестров"]
        CR["ConnectionRegistry.live<br/>(Ref[Map])"]
    end
    
    subgraph Config_Service_Layers["⚙️ Слой конфигурации"]
        DCS["DynamicConfigService.live<br/>(Ref + Pub/Sub)"]
    end
    
    subgraph Filter_Layers["🔍 Слои фильтров"]
        DRF["DeadReckoningFilter.live"]
        SF["StationaryFilter.live"]
    end
    
    subgraph Processing_Layers["🛰️ Слои обработки"]
        GPS["GpsProcessingService.live"]
    end
    
    subgraph Command_Layers["📤 Слои команд"]
        CS["CommandService.live<br/>(Ref[Map])"]
    end
    
    subgraph Watcher_Layers["⏱️ Слои мониторинга"]
        ICW["IdleConnectionWatcher.live"]
    end
    
    %% Зависимости конфигурации
    AC --> TCP & RC & KC
    
    %% Инфраструктура
    TCP --> TS
    RC --> RDS
    KC --> KFK
    
    %% DynamicConfigService
    RDS --> DCS
    AC --> DCS
    
    %% Фильтры
    DCS --> DRF & SF
    
    %% GpsProcessingService
    RDS --> GPS
    KFK --> GPS
    DRF --> GPS
    SF --> GPS
    
    %% CommandService
    RDS --> CS
    CR --> CS
    
    %% IdleConnectionWatcher
    CR --> ICW
    DCS --> ICW
    KFK --> ICW
    RDS --> ICW
    TCP --> ICW

    %% Стилизация
    classDef config fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef infra fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef service fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px
    classDef filter fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    
    class AC,TCP,RC,KC config
    class TS,RDS,KFK infra
    class CR,CS,GPS,ICW service
    class DCS,DRF,SF filter
```

### 2.4 Диаграмма жизненного цикла соединения

```mermaid
stateDiagram-v2
    [*] --> Подключение: TCP Accept
    
    Подключение --> Аутентификация: Получен IMEI пакет
    
    Аутентификация --> Активное: IMEI валиден<br/>vehicleId найден
    Аутентификация --> Отклонено: IMEI невалиден<br/>или неизвестен
    
    Отклонено --> [*]: ctx.close()
    
    state Активное {
        [*] --> Ожидание_данных
        Ожидание_данных --> Обработка: Получен пакет данных
        Обработка --> Ожидание_данных: ACK отправлен
        
        Ожидание_данных --> Получена_команда: Redis Pub/Sub
        Получена_команда --> Ожидание_данных: Команда отправлена
    }
    
    Активное --> GracefulClose: Трекер закрыл соединение
    Активное --> IdleTimeout: 5 мин без данных
    Активное --> ReadTimeout: Netty read timeout (60s)
    Активное --> WriteTimeout: Netty write timeout (30s)
    Активное --> ConnectionReset: TCP Reset
    Активное --> ProtocolError: Ошибка парсинга
    
    GracefulClose --> Уведомление
    IdleTimeout --> Уведомление
    ReadTimeout --> Уведомление
    WriteTimeout --> Уведомление
    ConnectionReset --> Уведомление
    ProtocolError --> Уведомление
    
    state Уведомление {
        [*] --> Удаление_из_реестра
        Удаление_из_реестра --> Удаление_из_Redis
        Удаление_из_Redis --> Публикация_в_Kafka
        Публикация_в_Kafka --> [*]
    }
    
    Уведомление --> [*]: DeviceStatus(isOnline=false, reason)
```

### 2.5 Диаграмма системы команд

```mermaid
sequenceDiagram
    autonumber
    participant ADMIN as 👨‍💼 Админ (HTTP)
    participant HTTP as 🌐 HttpApi
    participant CS as 📤 CommandService
    participant CR as 📋 ConnectionRegistry
    participant R as 🔴 Redis
    participant CH as 📦 ConnectionHandler
    participant PP as 🔧 ProtocolParser
    participant T as 📡 Трекер

    Note over ADMIN,T: === Способ 1: Через HTTP API ===
    ADMIN->>HTTP: POST /api/commands<br/>{imei, command}
    HTTP->>CS: sendCommand(command)
    CS->>CR: findByImei(imei)
    CR-->>CS: ConnectionEntry
    
    alt Трекер подключен
        CS->>PP: encodeCommand(command)
        PP-->>CS: ByteBuf
        CS->>CS: Promise.make[CommandResult]
        CS->>CS: Ref.update (добавить PendingCommand)
        CS->>CH: ctx.writeAndFlush(buffer)
        CH->>T: Command bytes
        
        CS->>R: publish(command-results:imei, Sent)
        
        alt Трекер ответил
            T->>CH: Response bytes
            CH->>CS: handleCommandResponse(imei, bytes)
            CS->>CS: Promise.succeed(Acked)
            CS->>R: publish(command-results:imei, Acked)
        else Timeout 30s
            CS->>CS: Promise timeout
            CS->>R: publish(command-results:imei, Timeout)
        end
    else Трекер не подключен
        CS->>R: publish(command-results:imei, Failed)
    end
    
    CS-->>HTTP: CommandResult
    HTTP-->>ADMIN: JSON Response

    Note over ADMIN,T: === Способ 2: Через Redis Pub/Sub ===
    R->>CS: psubscribe(commands:*)
    Note right of R: Другой сервис<br/>публикует команду
    CS->>CS: parseCommand(JSON)
    CS->>CR: isConnected(imei)
    
    alt Подключен
        CS->>CS: sendCommand(command)
    else Не подключен
        CS->>R: publish(command-results:imei, Failed)
    end
```

### 2.6 Структура модулей и пакетов

```mermaid
graph TB
    subgraph com.wayrecall.tracker["📦 com.wayrecall.tracker"]
        Main["Main.scala<br/>(точка входа)"]
        
        subgraph domain["📁 domain"]
            D1["GpsPoint.scala<br/>• GpsPoint<br/>• GpsRawPoint<br/>• GeoMath<br/>• DeviceStatus<br/>• DisconnectReason"]
            D2["Command.scala<br/>• Command (sealed trait)<br/>• RebootCommand<br/>• SetIntervalCommand<br/>• RequestPositionCommand<br/>• SetOutputCommand<br/>• CustomCommand<br/>• CommandStatus<br/>• CommandResult"]
            D3["Protocol.scala<br/>• ProtocolError<br/>• FilterError<br/>• RedisError<br/>• KafkaError"]
            D4["Vehicle.scala<br/>• VehicleInfo"]
        end
        
        subgraph config["📁 config"]
            C1["AppConfig.scala<br/>• AppConfig<br/>• TcpConfig<br/>• RedisConfig<br/>• KafkaConfig<br/>• FiltersConfig<br/>• HttpConfig"]
            C2["DynamicConfigService.scala<br/>• FilterConfig<br/>• DynamicConfigService"]
        end
        
        subgraph network["📁 network"]
            N1["TcpServer.scala<br/>• TcpServer<br/>• Netty Bootstrap"]
            N2["ConnectionHandler.scala<br/>• ConnectionHandler<br/>• ConnectionState<br/>• GpsProcessingService"]
            N3["ConnectionRegistry.scala<br/>• ConnectionRegistry<br/>• ConnectionEntry"]
            N4["CommandService.scala<br/>• CommandService<br/>• PendingCommand"]
            N5["IdleConnectionWatcher.scala<br/>• IdleConnectionWatcher"]
        end
        
        subgraph protocol["📁 protocol"]
            P0["ProtocolParser.scala<br/>(trait)"]
            P1["TeltonikaParser.scala<br/>Codec 8/8E"]
            P2["WialonParser.scala<br/>IPS текстовый"]
            P3["RuptelaParser.scala<br/>бинарный"]
            P4["NavTelecomParser.scala<br/>FLEX бинарный"]
        end
        
        subgraph filter["📁 filter"]
            F1["DeadReckoningFilter.scala<br/>• валидация скорости<br/>• валидация координат<br/>• детекция телепортации"]
            F2["StationaryFilter.scala<br/>• определение стоянок<br/>• оптимизация трафика Kafka"]
        end
        
        subgraph storage["📁 storage"]
            S1["RedisClient.scala<br/>• CRUD позиций<br/>• Pub/Sub команд<br/>• Hash конфигурации"]
            S2["KafkaProducer.scala<br/>• GPS events<br/>• Device status"]
        end
        
        subgraph api["📁 api"]
            A1["HttpApi.scala<br/>• GET /api/health<br/>• GET/PUT /api/config/filters<br/>• GET /api/connections<br/>• POST /api/commands"]
        end
    end

    %% Стилизация
    classDef entrypoint fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef domain fill:#e3f2fd,stroke:#1565c0
    classDef config fill:#fff9c4,stroke:#f9a825
    classDef network fill:#e8f5e9,stroke:#2e7d32
    classDef protocol fill:#f3e5f5,stroke:#7b1fa2
    classDef filter fill:#ffe0b2,stroke:#ef6c00
    classDef storage fill:#ffccbc,stroke:#bf360c
    classDef api fill:#b2dfdb,stroke:#00695c
    
    class Main entrypoint
    class D1,D2,D3,D4 domain
    class C1,C2 config
    class N1,N2,N3,N4,N5 network
    class P0,P1,P2,P3,P4 protocol
    class F1,F2 filter
    class S1,S2 storage
    class A1 api
```

### 2.7 Диаграмма фильтрации GPS данных

```mermaid
flowchart TD
    subgraph Вход
        RAW["🛰️ GpsRawPoint<br/>из парсера протокола"]
    end
    
    subgraph DeadReckoningFilter["🚫 Dead Reckoning Filter"]
        DRF_START["Получить конфигурацию<br/>из DynamicConfigService<br/>(~10ns, Ref.get)"]
        
        DRF_SPEED{"Скорость ≤<br/>maxSpeedKmh?<br/>(300 км/ч)"}
        DRF_COORDS{"Координаты<br/>валидны?<br/>lat: -90..90<br/>lon: -180..180"}
        DRF_TIME{"Timestamp<br/>не из будущего?<br/>(max +5 мин)"}
        DRF_TELEPORT{"Нет телепортации?<br/>distance ≤ maxJump<br/>(1000м/сек)"}
        
        DRF_ERR1["❌ FilterError<br/>ExcessiveSpeed"]
        DRF_ERR2["❌ FilterError<br/>InvalidCoordinates"]
        DRF_ERR3["❌ FilterError<br/>FutureTimestamp"]
        DRF_ERR4["❌ FilterError<br/>Teleportation"]
    end
    
    subgraph StationaryFilter["📍 Stationary Filter"]
        SF_START["Получить конфигурацию<br/>из DynamicConfigService<br/>(~10ns, Ref.get)"]
        
        SF_FIRST{"Первая точка?<br/>(prev = None)"}
        SF_DIST{"Расстояние ≥<br/>minDistanceMeters?<br/>(20м)"}
        SF_SPEED{"Скорость ≥<br/>minSpeedKmh?<br/>(2 км/ч)"}
        
        SF_PUBLISH["✅ shouldPublish = true<br/>(движение)"]
        SF_SKIP["⏭️ shouldPublish = false<br/>(стоянка)"]
    end
    
    subgraph Результат
        REDIS["💾 Redis<br/>setPosition()<br/>(всегда)"]
        KAFKA["📨 Kafka<br/>publishGpsEvent()<br/>(если движение)"]
    end
    
    RAW --> DRF_START
    DRF_START --> DRF_SPEED
    
    DRF_SPEED -->|Да| DRF_COORDS
    DRF_SPEED -->|Нет| DRF_ERR1
    
    DRF_COORDS -->|Да| DRF_TIME
    DRF_COORDS -->|Нет| DRF_ERR2
    
    DRF_TIME -->|Да| DRF_TELEPORT
    DRF_TIME -->|Нет| DRF_ERR3
    
    DRF_TELEPORT -->|Да| SF_START
    DRF_TELEPORT -->|Нет| DRF_ERR4
    
    SF_START --> SF_FIRST
    
    SF_FIRST -->|Да| SF_PUBLISH
    SF_FIRST -->|Нет| SF_DIST
    
    SF_DIST -->|Да| SF_PUBLISH
    SF_DIST -->|Нет| SF_SPEED
    
    SF_SPEED -->|Да| SF_PUBLISH
    SF_SPEED -->|Нет| SF_SKIP
    
    SF_PUBLISH --> REDIS
    SF_PUBLISH --> KAFKA
    
    SF_SKIP --> REDIS

    %% Стилизация
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef success fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    classDef skip fill:#fff9c4,stroke:#f9a825,stroke-width:2px
    classDef storage fill:#bbdefb,stroke:#1565c0,stroke-width:2px
    
    class DRF_ERR1,DRF_ERR2,DRF_ERR3,DRF_ERR4 error
    class SF_PUBLISH success
    class SF_SKIP skip
    class REDIS,KAFKA storage
```

### 2.8 Диаграмма работы IdleConnectionWatcher

```mermaid
sequenceDiagram
    autonumber
    participant MAIN as 🚀 Main
    participant ICW as ⏱️ IdleConnectionWatcher
    participant DCS as ⚙️ DynamicConfigService
    participant CR as 📋 ConnectionRegistry
    participant R as 🔴 Redis
    participant K as 📨 Kafka
    participant CH as 📦 ConnectionHandler

    Note over MAIN,CH: === Запуск мониторинга ===
    MAIN->>ICW: start
    ICW->>ICW: fork background fiber
    
    loop Каждые 60 секунд
        ICW->>DCS: getFilterConfig
        Note right of DCS: Получаем config<br/>(~10ns, Ref.get)
        DCS-->>ICW: FilterConfig
        
        ICW->>ICW: idleTimeoutMs = 300000<br/>(5 минут)
        
        ICW->>CR: getIdleConnections(idleTimeoutMs)
        Note right of CR: Clock.currentTime<br/>filter by lastActivityAt
        CR-->>ICW: List[ConnectionEntry]
        
        loop Для каждого idle соединения
            ICW->>ICW: Вычислить sessionDurationMs
            ICW->>R: getVehicleId(imei)
            R-->>ICW: Option[vehicleId]
            
            ICW->>ICW: Создать DeviceStatus<br/>reason = IdleTimeout
            
            ICW->>K: publishDeviceStatus(status)
            Note right of K: isOnline=false<br/>reason=IdleTimeout<br/>sessionDurationMs
            
            ICW->>R: unregisterConnection(imei)
            ICW->>CH: ctx.close()
            ICW->>CR: unregister(imei)
        end
        
        ICW->>ICW: Log: "Disconnected N connections"
    end
```

---

## 3. Детальное описание компонентов

### 3.1 Network Layer

#### TcpServer
| Аспект | Описание |
|--------|----------|
| **Ответственность** | Управление Netty ServerBootstrap для каждого протокола |
| **ZIO Layer** | `ZLayer.scoped` с acquireRelease для EventLoopGroup |
| **Потоки** | Boss: 1, Workers: 4 (настраивается) |
| **Опции сокета** | SO_BACKLOG=5000, SO_KEEPALIVE, TCP_NODELAY |
| **Таймауты** | Read: 60s, Write: 30s, Connection: 30s |

#### ConnectionHandler
| Аспект | Описание |
|--------|----------|
| **Ответственность** | Мост между Netty и ZIO, обработка пакетов |
| **Состояние** | `Ref[ConnectionState]` — IMEI, vehicleId, connectedAt, positionCache |
| **Ошибки** | Graceful error handling, logging через ZIO.logError |
| **Lifecycle** | channelActive → channelRead → channelInactive |

#### ConnectionRegistry
| Аспект | Описание |
|--------|----------|
| **Ответственность** | Реестр активных TCP соединений |
| **Хранилище** | `Ref[Map[String, ConnectionEntry]]` — чисто функционально! |
| **Операции** | register, unregister, findByImei, updateLastActivity, getIdleConnections |
| **Сложность** | O(1) для lookup, O(n) для getIdleConnections |

#### CommandService
| Аспект | Описание |
|--------|----------|
| **Ответственность** | Отправка команд на трекеры через соединение |
| **Хранилище** | `Ref[Map[String, PendingCommand]]` для ожидающих ответа |
| **Таймаут** | 30 секунд на ответ трекера |
| **Redis** | Подписка на `commands:*`, публикация результатов |

#### IdleConnectionWatcher
| Аспект | Описание |
|--------|----------|
| **Ответственность** | Отключение неактивных соединений |
| **Интервал** | Проверка каждые 60 секунд (настраивается) |
| **Таймаут** | 300 секунд (5 минут) без данных |
| **Уведомления** | Kafka DeviceStatus с reason=IdleTimeout |

### 3.2 Protocol Layer

#### ProtocolParser (trait)
```scala
trait ProtocolParser:
  def parseImei(buffer: ByteBuf): IO[ProtocolError, String]
  def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]]
  def ack(recordCount: Int): ByteBuf
  def imeiAck(accepted: Boolean): ByteBuf
  def encodeCommand(command: Command): IO[ProtocolError, ByteBuf]
```

| Парсер | Формат IMEI | Формат координат | CRC |
|--------|-------------|------------------|-----|
| **Teltonika** | 2B length + ASCII | degrees × 10⁷ | CRC-16 |
| **Wialon** | `#L#imei;pwd` | DDMM.MMMM | нет |
| **Ruptela** | 8B Long | degrees × 10⁷ | CRC-16 |
| **NavTelecom** | 15B ASCII | degrees × 10⁷ | CRC-16-CCITT |

### 3.3 Filter Layer

#### DeadReckoningFilter
```
Проверки:
1. Скорость ≤ 300 км/ч
2. Координаты: lat ∈ [-90, 90], lon ∈ [-180, 180]
3. Timestamp ≤ now + 5 минут
4. Расстояние от предыдущей точки ≤ 1000м/сек
```

#### StationaryFilter
```
Логика публикации в Kafka:
- Первая точка: всегда публикуем
- Последующие: если distance ≥ 20м ИЛИ speed ≥ 2 км/ч

Оптимизация: снижает трафик Kafka на ~80% при стоянках
```

### 3.4 Storage Layer

#### RedisClient
| Операция | Ключ | TTL |
|----------|------|-----|
| Позиции | `position:{vehicleId}` | 3600s |
| Соединения | `connection:{imei}` | - |
| Vehicles | `vehicle:{imei}` | 3600s |
| Конфигурация | `config:filters` | - |
| Pub/Sub | `commands:*`, `config:updates` | - |

#### KafkaProducer
| Топик | Ключ | Содержимое |
|-------|------|------------|
| `raw-gps-events` | vehicleId | GpsPoint JSON |
| `device-status` | imei | DeviceStatus JSON |

---

## 4. Потоки данных

### 4.1 Входящий поток GPS данных

```
GPS Трекер → TCP (Netty) → ConnectionHandler → ProtocolParser 
          → GpsProcessingService → DeadReckoningFilter → StationaryFilter
          → Redis (позиции) + Kafka (события)
```

**Latency breakdown:**
- TCP accept: ~1ms
- IMEI parsing + Redis lookup: ~2-5ms
- Data parsing: ~0.1ms per record
- Filter validation: ~0.01ms (Ref.get = ~10ns)
- Redis write: ~1-2ms
- Kafka publish: ~5-10ms (async)

### 4.2 Исходящий поток команд

```
HTTP API / Redis Pub/Sub → CommandService → ConnectionRegistry.findByImei
                        → ProtocolParser.encodeCommand → Netty writeAndFlush
                        → GPS Трекер
```

### 4.3 Поток событий отключения

```
Netty (channelInactive / IdleWatcher) → ConnectionHandler/IdleWatcher
     → ConnectionRegistry.unregister → Redis.unregisterConnection
     → Kafka.publishDeviceStatus(reason)
```

---

## 5. Анализ чистоты кода (FP)

### 5.1 ✅ Чистые компоненты

| Компонент | Подход | Оценка |
|-----------|--------|--------|
| **ConnectionRegistry** | `Ref[Map]` вместо ConcurrentHashMap | ⭐⭐⭐⭐⭐ |
| **CommandService** | `Ref[Map]` для pending commands | ⭐⭐⭐⭐⭐ |
| **DynamicConfigService** | `Ref[FilterConfig]` + Pub/Sub | ⭐⭐⭐⭐⭐ |
| **DeadReckoningFilter** | Чистые функции валидации | ⭐⭐⭐⭐⭐ |
| **StationaryFilter** | Чистые предикаты | ⭐⭐⭐⭐⭐ |
| **GeoMath** | Чистый object с haversineDistance | ⭐⭐⭐⭐⭐ |
| **GpsProcessingService** | ZIO effects only | ⭐⭐⭐⭐⭐ |
| **IdleConnectionWatcher** | `Schedule.fixed` + pure effects | ⭐⭐⭐⭐⭐ |
| **ZIO Layer composition** | Декларативная композиция | ⭐⭐⭐⭐⭐ |

### 5.2 ⚠️ Компромиссы (необходимые)

| Компонент | Причина | Оценка |
|-----------|---------|--------|
| **ConnectionHandler.stateRef** | Netty ChannelHandler создаётся вне ZIO | ⭐⭐⭐⭐ |
| **TcpServer (Netty)** | Netty — императивный фреймворк | ⭐⭐⭐⭐ |
| **RedisClient.subscribe** | Callback API Lettuce | ⭐⭐⭐⭐ |

### 5.3 🎯 Использование ZIO Clock

| Файл | Использование |
|------|---------------|
| `ConnectionRegistry` | `Clock.currentTime` в register, updateLastActivity, getIdleConnections |
| `ConnectionHandler` | `Clock.currentTime` в handleImeiPacket |
| `CommandService` | `Clock.instant` в createResult |
| `IdleConnectionWatcher` | `Clock.currentTime` в disconnectWithNotification |
| `GpsProcessingService` | `Clock.currentTime` в onConnect, onDisconnect |
| `DeadReckoningFilter` | `Clock.currentTime` в validateTimestamp |

### 5.4 ❌ Оставшиеся проблемы

| Проблема | Файл | Строка | Исправление |
|----------|------|--------|-------------|
| `System.currentTimeMillis()` | HttpApi.scala | ~64 | Заменить на `Clock.currentTime` |
| `Instant.now()` | HttpApi.scala | ~145, ~157 | Заменить на `Clock.instant` |
| `System.currentTimeMillis()` | StationaryFilterSpec | ~39 | Допустимо в тестах |
| `System.currentTimeMillis()` | WialonParser | ~126 | Fallback при ошибке парсинга |

---

## 6. Обнаруженные проблемы и упущения

### 6.1 🔴 Критические

| # | Проблема | Риск | Рекомендация |
|---|----------|------|--------------|
| 1 | **Нет graceful shutdown для TcpServer** | При SIGTERM могут теряться данные | Добавить ZIO.addFinalizer для закрытия всех соединений |
| 2 | **Нет backpressure для Kafka** | При перегрузке Kafka теряем события | Использовать bounded queue + retry |
| 3 | **Нет circuit breaker для Redis** | Падение Redis → падение сервиса | Добавить Resilience4j или ZIO Circuit Breaker |
| 4 | **ConnectionHandler: Unsafe.unsafe** | Неявная обработка ошибок | Логировать ошибки из runEffect |

### 6.2 🟠 Важные

| # | Проблема | Риск | Рекомендация |
|---|----------|------|--------------|
| 5 | **Нет метрик Prometheus** | Нет observability | Добавить zio-metrics |
| 6 | **Нет health-check Redis/Kafka** | /api/health не проверяет зависимости | Добавить проверки в endpoint |
| 7 | **Instant.now() в HttpApi** | Нарушение чистоты | Заменить на Clock.instant |
| 8 | **Нет rate limiting на HTTP API** | DDoS уязвимость | Добавить middleware |
| 9 | **Hardcoded timeout в CommandService** | Нельзя настроить runtime | Вынести в конфигурацию |
| 10 | **Нет retry при ошибках Kafka** | Потеря событий | Добавить retry с exponential backoff |

### 6.3 🟡 Улучшения

| # | Проблема | Рекомендация |
|---|----------|--------------|
| 11 | **Нет поддержки TLS** | Добавить SSL handler в Netty pipeline |
| 12 | **Нет IMEI whitelist/blacklist** | Добавить проверку в Redis |
| 13 | **Нет логирования в Kafka** | Добавить audit log topic |
| 14 | **Нет compression для Redis** | Включить LZ4 для больших данных |
| 15 | **Тесты только для 2 компонентов** | Добавить тесты для всех парсеров |
| 16 | **Нет integration tests** | Добавить testcontainers |
| 17 | **Нет документации API (OpenAPI)** | Генерировать из zio-http |
| 18 | **IdleTimeout не в DynamicConfig** | Добавить в FilterConfig для runtime изменений |

---

## 7. Рекомендуемые улучшения

### 7.1 Приоритет 1: Надёжность

```scala
// 1. Graceful Shutdown
val program = for
  registry <- ZIO.service[ConnectionRegistry]
  _ <- ZIO.addFinalizer {
    for
      connections <- registry.getAllConnections
      _ <- ZIO.foreachDiscard(connections) { entry =>
        ZIO.attempt(entry.ctx.close()).ignore
      }
      _ <- ZIO.logInfo(s"Gracefully closed ${connections.size} connections")
    yield ()
  }
  // ... rest of program
yield ()

// 2. Circuit Breaker для Redis
val redisWithCircuitBreaker = CircuitBreaker.make(
  maxFailures = 5,
  reset = 30.seconds
).flatMap { cb =>
  redisClient.withCircuitBreaker(cb)
}

// 3. Retry для Kafka
def publishWithRetry(event: GpsPoint): Task[Unit] =
  kafkaProducer.publishGpsEvent(event)
    .retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
```

### 7.2 Приоритет 2: Observability

```scala
// Prometheus metrics
object Metrics:
  val activeConnections = Counter.gauge("tracker_active_connections")
  val gpsPointsReceived = Counter.counter("tracker_gps_points_total")
  val gpsPointsFiltered = Counter.counter("tracker_gps_points_filtered_total")
  val commandsSent = Counter.counter("tracker_commands_sent_total")
  val kafkaPublishLatency = Histogram.histogram("tracker_kafka_publish_seconds")
```

### 7.3 Приоритет 3: Безопасность

```scala
// TLS Support
pipeline.addLast("ssl", SslContextBuilder
  .forServer(certFile, keyFile)
  .build()
  .newHandler(ch.alloc()))

// Rate Limiting
val rateLimitMiddleware = RateLimiter.middleware(
  maxRequests = 100,
  window = 1.minute
)
```

### 7.4 Приоритет 4: Тестируемость

```scala
// TestContainers для интеграционных тестов
val redisContainer = GenericContainer("redis:7")
val kafkaContainer = KafkaContainer("confluentinc/cp-kafka:7.5.0")

def integrationTestLayer = ZLayer.scoped {
  for
    redis <- ZIO.acquireRelease(redisContainer.start)(_.stop)
    kafka <- ZIO.acquireRelease(kafkaContainer.start)(_.stop)
  yield IntegrationTestEnv(redis, kafka)
}
```

---

## 8. Матрица зависимостей

```mermaid
graph LR
    subgraph External["Внешние зависимости"]
        ZIO["zio 2.0.20"]
        ZIO_CFG["zio-config 4.0.1"]
        ZIO_JSON["zio-json 0.6.2"]
        ZIO_HTTP["zio-http 3.0.0-RC4"]
        NETTY["netty-all 4.1.104"]
        LETTUCE["lettuce-core 6.3.2"]
        KAFKA["kafka-clients 3.6.1"]
        LOGBACK["logback 1.4.14"]
    end
    
    subgraph Internal["Внутренние модули"]
        Main --> API & Network & Config
        API --> Config & Network
        Network --> Protocol & Storage & Filter & Config
        Filter --> Config
        Storage --> Config
        Protocol --> Domain
        Network --> Domain
        Filter --> Domain
    end
    
    Internal --> External
```

---

## 9. Checklist полноты реализации

### 9.1 Функциональность

| Категория | Функция | Статус |
|-----------|---------|--------|
| **TCP** | Multi-protocol server | ✅ |
| **TCP** | Connection timeout | ✅ |
| **TCP** | Idle timeout | ✅ |
| **TCP** | Graceful shutdown | ⚠️ Частично |
| **TCP** | TLS/SSL | ❌ |
| **Protocols** | Teltonika Codec 8/8E | ✅ |
| **Protocols** | Wialon IPS | ✅ |
| **Protocols** | Ruptela | ✅ |
| **Protocols** | NavTelecom FLEX | ✅ |
| **Filtering** | Speed validation | ✅ |
| **Filtering** | Coordinate validation | ✅ |
| **Filtering** | Teleportation detection | ✅ |
| **Filtering** | Stationary detection | ✅ |
| **Commands** | Send via HTTP | ✅ |
| **Commands** | Send via Redis Pub/Sub | ✅ |
| **Commands** | Response handling | ✅ |
| **Commands** | Timeout handling | ✅ |
| **Config** | Static (HOCON) | ✅ |
| **Config** | Dynamic (Redis) | ✅ |
| **Config** | Environment override | ✅ |
| **Storage** | Redis positions | ✅ |
| **Storage** | Redis connections | ✅ |
| **Storage** | Kafka GPS events | ✅ |
| **Storage** | Kafka device status | ✅ |
| **API** | Health check | ✅ |
| **API** | Config management | ✅ |
| **API** | Connection list | ✅ |
| **API** | Send command | ✅ |
| **API** | OpenAPI docs | ❌ |
| **API** | Rate limiting | ❌ |

### 9.2 Non-Functional

| Категория | Требование | Статус |
|-----------|------------|--------|
| **Performance** | 5000+ concurrent connections | ✅ Конфиг |
| **Performance** | ~10ns config read | ✅ Ref |
| **Reliability** | Disconnect notifications | ✅ |
| **Reliability** | Redis circuit breaker | ❌ |
| **Reliability** | Kafka retry | ❌ |
| **Observability** | Logging | ✅ SLF4J |
| **Observability** | Metrics | ❌ |
| **Observability** | Tracing | ❌ |
| **Security** | TLS | ❌ |
| **Security** | IMEI whitelist | ❌ |
| **Testing** | Unit tests | ⚠️ 2 файла |
| **Testing** | Integration tests | ❌ |

---

## 10. Заключение

### Сильные стороны архитектуры:
1. ✅ **Чистый FP** — все состояние в ZIO Ref
2. ✅ **Модульность** — четкое разделение на слои
3. ✅ **Масштабируемость** — ZIO Layer для DI
4. ✅ **Динамическая конфигурация** — Redis Pub/Sub
5. ✅ **Множество протоколов** — 4 парсера с общим интерфейсом
6. ✅ **Observability** — события отключения в Kafka

### Области для улучшения:
1. 🔴 Graceful shutdown
2. 🔴 Circuit breaker / retry
3. 🟠 Prometheus метрики
4. 🟠 TLS поддержка
5. 🟠 Расширенное тестирование
6. 🟡 OpenAPI документация

---

*Документ создан: 16 января 2026*  
*Автор: AI Architecture Analyst*
