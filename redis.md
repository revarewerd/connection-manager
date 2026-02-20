# Redis в Connection Manager

## 1. СТРУКТУРЫ И СХЕМА ХРАНЕНИЯ

### 1.1 Основная структура: HASH `device:{imei}`

**Redis хранит CONTEXT (конфиг от ДМ) и CONNECTION (аудит).**

```
HASH: device:356123456789012 (в Redis)
├─ CONTEXT FIELDS (Device Manager пишет, СМ читает)
│  ├─ vehicleId: "12345"
│  ├─ organizationId: "999"
│  ├─ name: "Газель АА123"
│  ├─ speedLimit: "100"
│  ├─ hasGeozones: "true"
│  ├─ hasSpeedRules: "true"
│  ├─ hasRetranslation: "true"
│  └─ retranslationTargets: "wialon-42,webhook-7"
└─ CONNECTION FIELDS (для аудита, кто подключен)
   ├─ instanceId: "cm-teltonika-1"
   ├─ protocol: "teltonika"
   ├─ connectedAt: "2026-02-20T12:00:00Z"
   ├─ lastActivity: "2026-02-20T12:30:45Z"
   └─ remoteAddress: "192.168.1.100:54321"
```

**POSITION fields хранятся в памяти (ConnectionState.Ref):**
```
ConnectionState (в памяти, thread-safe Ref)
├─ lastPosition (обновляется на каждый пакет)
│  ├─ lat: "55.7558"
│  ├─ lon: "37.6173"
│  ├─ speed: "65"
│  ├─ course: "180"
│  ├─ altitude: "120"
│  ├─ satellites: "12"
│  ├─ time: "2026-02-20T12:30:45Z"
│  └─ isMoving: "true" (Dead Reckoning/Stationary filter)
├─ cachedContext (TTL 1 час, обновляется при конфиг-чнджд)
│  └─ DeviceContext (vehicleId, organizationId, hasGeozones...)
└─ connectionInfo (CONNECTION FIELDS из Redis)
```

**Операции в Redis:**
```redis
# Первый HGETALL при аутентификации (1 раз за сессию)
HGETALL device:356123456789012
→ Map[vehicleId, organizationId, name, instanceId, protocol, connectedAt, ...]

# Обновление CONTEXT при изменении конфига (ДМ публикует событие)
HMSET device:356123456789012 vehicleId 12345 organizationId 999 hasGeozones true ...

# Обновление CONNECTION при отключении
HDEL device:356123456789012 instanceId protocol connectedAt lastActivity remoteAddress
```

**Операции в памяти (NO Redis):**
```scala
// На каждый GPS пакет (nanoseconds, не Redis!)
stateRef.update(_.copy(
  lastPosition = Some(gpsPoint),  // ← обновляем позицию в памяти
  lastActivityTime = now
))
```

### 1.2 Legacy структуры (обратная совместимость)

#### `connection:{imei}` - HASH (DEPRECATED)
```redis
HASH: connection:356123456789012
├─ connectedAt: "2026-02-20T12:00:00Z"
├─ remoteAddress: "192.168.1.100"
├─ port: "54321"
└─ protocol: "teltonika"
```
**Зачем:** Старый код может искать info по connection:{imei}  
**TODO:** Убрать после миграции всех потребителей

#### `position:{vehicleId}` - УДАЛЕН

**Почему удалён:** 
- POSITION теперь только в памяти (in-memory)
- Client получают позиции через Kafka gps-events (событийная модель)
- Нет необходимости в отдельном Redis ключе

Если нужна текущая позиция:
- Слушайте Kafka gps-events топик
- Или запросите из памяти ConnectionHandler напрямую (если тот же СМ инстанс)

### 1.3 Pub/Sub каналы (NOTIFICATIONS)

```
CHANNEL: device-config-changed:{imei}
└─ Публикуется когда Device Manager обновил конфиг
   (hasGeozones, hasSpeedRules, speedLimit и т.д. изменились)

CHANNEL: commands:{imei}
└─ Устаревший канал для команд (заменен на Kafka device-commands)
```

### 1.4 TTL (Time To Live)

| Ключ | TTL | Зачем |
|------|-----|-------|
| `device:{imei}` | нет (persistent) | Данные об устройстве хранятся долго |
| `position:{vehicleId}` | 3600s (1 час) | Legacy, для обратной совместимости |
| `connection:{imei}` | нет | Legacy, для обратной совместимости |

---

## 2. КТО И КАК ПОЛУЧАЕТ/ОБНОВЛЯЕТ

### 2.1 Device Manager (внешний сервис)

**Когда:** При CRUD операциях с vehicles в PostgreSQL  
**Что:** Изменения в конфигурации (имя, организация, флаги)

```scala
// Device Manager:
// 1. Обновляет PostgreSQL
UPDATE vehicles SET name = 'Газель АА123', speed_limit = 100 WHERE imei = '356123...'

// 2. Публикует событие в Kafka
PUBLISH(topic: "device-events", message: {
  imei: "356123456789012",
  organizationId: 999,
  vehicleId: 12345,
  name: "Газель АА123",
  speedLimit: 100,
  hasGeozones: true,
  hasSpeedRules: true,
  hasRetranslation: true,
  retranslationTargets: ["wialon-42"]
})
```

### 2.2 DeviceEventConsumer (в Connection Manager)

**Когда:** Получает событие из Kafka device-events  
**Что:** Обновляет Redis + публикует notification

```scala
// ConnectionManager.DeviceEventConsumer:
def start(): Task[Unit] =
  for
    // Слушаем Kafka device-events
    event <- kafkaConsumer.consume("device-events")
    
    // Обновляем Redis HASH
    _ <- redis.hset(
      key = s"device:${event.imei}",
      values = Map(
        "vehicleId" → event.vehicleId.toString,
        "organizationId" → event.organizationId.toString,
        "name" → event.name,
        "speedLimit" → event.speedLimit.toString,
        "hasGeozones" → event.hasGeozones.toString,
        "hasSpeedRules" → event.hasSpeedRules.toString,
        "hasRetranslation" → event.hasRetranslation.toString,
        "retranslationTargets" → event.retranslationTargets.mkString(",")
      )
    )
    
    // Публикуем Pub/Sub уведомление
    _ <- redis.publish(
      channel = s"device-config-changed:${event.imei}",
      message = "config_updated"
    )
    
    _ <- ZIO.logInfo(s"[DEVICE-EVENTS] IMEI=${event.imei} конфиг обновлён в Redis")
  yield ()
```

### 2.3 ConnectionHandler (в Connection Manager)

**При аутентификации (первый пакет):**
```scala
def handleImeiPacket(ctx: ChannelHandlerContext, buffer: ByteBuf): UIO[Unit] =
  for
    now <- Clock.instant
    imei <- parser.parseImei(buffer)
    
    // 💥 ЕДИНСТВЕННЫЙ HGETALL за всю сессию!
    deviceData <- redis.hgetall(s"device:$imei")  // ← ВСЕ данные за раз!
    
    // Сохраняем в ConnectionState.Ref (в памяти на весь сеанс!)
    _ <- stateRef.set(ConnectionState(
      imei = Some(imei),
      vehicleId = Some(deviceData.vehicleId),
      
      // CONTEXT - из Redis, TTL 1 час
      cachedContext = Some(deviceData.toDeviceContext),
      contextCachedAt = now,
      
      // POSITION - будет обновляться на каждый пакет в памяти
      lastPosition = None,
      lastActivityTime = now,
      
      // CONNECTION - из Redis, для аудита
      connectionInfo = deviceData.toConnectionInfo
    ))
    
    // Регистрируем подключение (CONNECTION FIELDS в Redis)
    _ <- redis.hset(
      key = s"device:$imei",
      values = Map(
        "instanceId" → "cm-teltonika-1",    // какой CM инстанс обслуживает
        "protocol" → "teltonika",           // какой протокол
        "connectedAt" → now.toString,        // когда подключился
        "remoteAddress" → ctx.channel().remoteAddress().toString  // откуда
      )
    )
    
    // Слушаем Kafka device-config-changed (ДМ обновил конфиг)
    // или Redis Pub/Sub device-config-changed:{imei} (альтернатива)
    _ <- kafka.subscribe("device-config-changed") { event =>
      if event.imei == imei then
        stateRef.update(_.copy(contextCachedAt = 0))  // Инвалидируем CONTEXT
    }
  yield ()
```

**На каждый GPS пакет (обновление позиции БЕЗ Redis):**
```scala
def handleDataPacket(ctx: ChannelHandlerContext, buffer: ByteBuf, state: ConnectionState): UIO[Unit] =
  for
    now <- Clock.instant
    
    // Парсим точки из бинарного пакета
    points <- parser.parseData(buffer, state.imei)
    
    // Обновляем позицию В ПАМЯТИ (nanoseconds!)
    _ <- ZIO.foreach(points.lastOption) { lastPoint =>
      stateRef.update(s => s.copy(
        lastPosition = Some(lastPoint),
        lastActivityTime = now
      ))
    }
    
    // Применяем фильтры и публикуем в Kafka
    _ <- ZIO.foreach(points) { point =>
      for
        context <- stateRef.get.map(_.cachedContext)
        filtered <- applyFilters(point, Some(context))  // Dead Reckoning, Stationary
        // публикуем только если прошла фильтры
        _ <- ZIO.whenCase(filtered) {
          case Some(gpsPoint) =>
            val event = GpsEventMessage.from(gpsPoint, context)
            kafka.publish("gps-events", event)
        }
      yield ()
    }
  yield ()
  // ⚠️ НЕ ПИШЕМ В REDIS! Экономим 864M операций в день!
```

**При отключении:**
```scala
override def channelInactive(ctx: ChannelHandlerContext): Unit =
  for
    state <- stateRef.get
    imei <- ZIO.fromOption(state.imei)...
    
    // Очищаем connection поля
    _ <- redis.hdel(
      key = s"device:$imei",
      fields = List("instanceId", "protocol", "connectedAt", "lastActivity", "remoteAddress")
    )
    
    // Legacy: удаляем connection:{imei}
    _ <- redis.del(s"connection:$imei")
  yield ()
```

---

## 3. ОПТИМИЗАЦИЯ

### 3.1 Старая архитектура (проблема)

```
Предыдущий подход: HMSET на каждый пакет
  HMSET device:{imei} lat lon speed time
    10k трекеров × 1 packet/sec = 10k операций/сек
                                    864M операций/день!
                                    = ~10 дней только на Redis запросы
```

### 3.2 Новая архитектура: Разделение ответственности

```
Разделение данных по хранилищам:

┌─────────────────────────────────────────────────────────────────┐
│  ConnectionHandler (thread-safe Ref)                            │
├─────────────────────────────────────────────────────────────────┤
│ ConnectionState:                                                │
│ ├─ lastPosition (POSITION FIELDS) ← быстрая in-memory кэш       │
│ │  ├─ lat, lon, speed, course, altitude, satellites            │
│ │  ├─ time, isMoving                                           │
│ │  └─ обновляется на КАЖДЫЙ пакет (nanoseconds!)               │
│ │                                                               │
│ ├─ cachedContext (CONTEXT FIELDS) ← TTL 1 час                  │
│ │  ├─ vehicleId, organizationId, name                          │
│ │  ├─ speedLimit, hasGeozones, hasSpeedRules                   │
│ │  ├─ hasRetranslation, retranslationTargets                   │
│ │  └─ обновляется при получении Kafka device-config-changed    │
│ │                                                               │
│ └─ connectionInfo (CONNECTION FIELDS) ← аудит                  │
│    ├─ instanceId, protocol, connectedAt, lastActivity         │
│    └─ копия из Redis, обновляется при изменениях             │
└─────────────────────────────────────────────────────────────────┘
          
          ↓ На каждый GPS пакет (NO REDIS!)
          
  ConnectionState.Ref.update(_.copy(lastPosition = ...))
          
          ↓ При изменении конфига (Kafka событие)
          
  Redis.HGETALL device:{imei} → cachedContext.update(...)

┌─────────────────────────────────────────────────────────────────┐
│  Redis (источник истины для CONTEXT и CONNECTION)              │
├─────────────────────────────────────────────────────────────────┤
│ HASH device:{imei}:                                             │
│ ├─ CONTEXT: vehicleId, organizationId, name, speedLimit,       │
│ │           hasGeozones, hasSpeedRules, hasRetranslation,      │
│ │           retranslationTargets                               │
│ │           (пишет Device Manager)                             │
│ │                                                               │
│ └─ CONNECTION: instanceId, protocol, connectedAt,              │
│              lastActivity, remoteAddress                        │
│              (пишет Connection Manager при подключении)        │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 Оптимальный поток обработки

```
════════════════════════════════════════════════════════════════
 ПЕРВОЕ ПОДКЛЮЧЕНИЕ (аутентификация)
════════════════════════════════════════════════════════════════

Tracker отправляет IMEI пакет:
  ├─ ConnectionHandler.handleImeiPacket()
  ├─ Парсим IMEI
  ├─ HGETALL device:{imei} из Redis ← ОДИН раз за сессию!
  ├─ Сохраняем все данные в ConnectionState:
  │  ├─ cachedContext = DeviceContext(...)
  │  ├─ lastPosition = Optional.empty (еще нет GPS)
  │  └─ connectionInfo = CONNECTION fields
  ├─ HMSET device:{imei} (обновляем lastActivity)
  └─ Слушаем Redis Pub/Sub device-config-changed:{imei}

════════════════════════════════════════════════════════════════
 КАЖДЫЙ GPS ПАКЕТ (1 раз в секунду x 10k трекеров)
════════════════════════════════════════════════════════════════

Tracker отправляет GPS пакет:
  ├─ ConnectionHandler.handleDataPacket()
  ├─ Парсим GPS точку
  ├─ Берём cachedContext из ConnectionState ← IN MEMORY! (nanoseconds)
  ├─ Применяем фильтры (Dead Reckoning, Stationary)
  ├─ Обновляем ConnectionState.lastPosition ← IN MEMORY! (nanoseconds)
  ├─ Публикуем в Kafka gps-events
  └─ ⚠️  НЕ пишем в Redis! (экономим 864M операций!)

════════════════════════════════════════════════════════════════
 КОГДА DEVICE MANAGER ОБНОВИЛ КОНФИГ
════════════════════════════════════════════════════════════════

Device Manager:
  ├─ Обновил vehicleId, speedLimit, hasGeozones в PostgreSQL
  ├─ HMSET device:{imei} в Redis (CONTEXT fields)
  ├─ Публикует в Kafka device-config-changed:{imei}
  └─ "есть новые данные отди свежи в Redis"

Connection Manager (получил Kafka событие):
  ├─ "Ок, надо обновить кэш"
  ├─ HGETALL device:{imei} из Redis ← свежие данные!
  ├─ Обновляем ConnectionState.cachedContext
  └─ Применяем новую конфигурацию к уже собранным GPS точкам

════════════════════════════════════════════════════════════════
 БОЛЕЕ РЕДКО: Pub/Sub инвалидация (если система использует)
════════════════════════════════════════════════════════════════

CM слушает Redis Pub/Sub device-config-changed:{imei}:
  ├─ Получает сообщение "config_updated"
  ├─ ConnectionState.contextCachedAt = 0 (инвалидируем немедленно)
  ├─ На следующий GPS пакет → HGETALL device:{imei}
  └─ (Альтернатива Kafka device-config-changed)
```

### 3.4 Результаты оптимизации

| Метрика | Было | Стало | Улучшение |
|---------|------|-------|-----------|
| **HMSET операций/день** | 864M | **0** | **864M операций УДАЛЕНЫ** |
| **HGETALL операций/день** | 0 | ~10k (только config changes) | Минимально |
| **Redis операций/день** | 864M | ~10k | **86,400x** |
| **Latency на пакет** | 1-5ms (Redis) | **nanoseconds** (in-memory) | **1000x+ быстро** |
| **Пропускная способность** | 10k req/sec | **in-memory (бесплатно)** | Сеть free |
| **Память (Ref)** | нет | ~1KB на соединение | 10k × 1KB = 10MB ✅ |
| **Резервная копия** | HMSET каждый пакет | CONNECTION fields в Redis | Достаточно для аудита |

---

## 4. МОДЕЛИ ДАННЫХ

### 4.1 GPS Point Models (эволюция данных)

```
Парсер (ProtocolParser)
    ↓
GpsRawPoint (сырая точка из протокола)
    ↓ (валидация, фильтры)
GpsPoint (валидированная точка с vehicleId)
    ↓ (обогащение из DeviceContext)
GpsEventMessage (для публикации в Kafka)
```

### 4.2 GpsRawPoint (из парсера)

```scala
/**
 * Сырая GPS точка, полученная из бинарного пакета протокола
 * 
 * Не содержит vehicleId (парсер не знает о нём)
 * Не валидирована (координаты могут быть невалидными)
 * Используется для парсинга, это промежуточное представление
 */
case class GpsRawPoint(
    latitude: Double,      // из пакета как есть (может быть невалидной)
    longitude: Double,     // из пакета как есть
    altitude: Int,         // метры
    speed: Int,            // км/ч
    angle: Int,            // градусы (0-360)
    satellites: Int,       // количество спутников
    timestamp: Long        // миллисекунды
)
```

**Где создаётся:**
```scala
TeltonikaParser.parseData() → List[GpsRawPoint]
WialonAdapterParser.parseData() → List[GpsRawPoint]
// и т.д.
```

### 4.3 GpsPoint (валидированная)

```scala
/**
 * Валидированная GPS точка с vehicleId
 * 
 * Прошла проверки:
 * - Координаты в допустимом диапазоне (-90..90, -180..180)
 * - Timestamp не в будущем
 * - Целостность данных
 * 
 * Содержит vehicleId - связь с транспортным средством
 * Используется после фильтров
 */
case class GpsPoint(
    vehicleId: Long,       // ← главное отличие от GpsRawPoint
    latitude: Double,      // (-90..90) валидирована
    longitude: Double,     // (-180..180) валидирована
    altitude: Int,         // метры
    speed: Int,            // км/ч
    angle: Int,            // градусы (0-360)
    satellites: Int,       // количество спутников
    timestamp: Long        // миллисекунды
) derives JsonCodec
```

**Где создаётся:**
```scala
GpsRawPoint.toValidated(vehicleId) → GpsPoint
```

**Используется для:**
- Dead Reckoning фильтра (сравнение prevPosition)
- Stationary фильтра (определение движения)
- Сохранения в Redis
- Публикации в Kafka

### 4.4 DeviceContext (in-memory кэш)

```scala
/**
 * Контекст устройства - хранится в памяти (ConnectionState.Ref)
 * 
 * Содержит данные которые:
 * - Кэшируются на 1 час
 * - Обновляются через Redis Pub/Sub (instant)
 * - Используются для маршрутизации GPS событий
 */
case class DeviceContext(
    // === идентификаторы ===
    organizationId: Long,  // мультитенантность
    vehicleId: Long,       // (опционально - может быть неизвестное устройство)
    name: String,          // "Газель АА123"
    
    // === скорость ===
    speedLimit: Option[Int],  // км/ч, None = нет ограничения
    
    // === ФЛАГИ МАРШРУТИЗАЦИИ (ключевые!) ===
    hasGeozones: Boolean,       // есть привязанные геозоны
    hasSpeedRules: Boolean,     // есть правила скорости
    hasRetranslation: Boolean,  // пересылать в внешние системы?
    
    // === ретрансляция ===
    retranslationTargets: List[String],  // ["wialon-42", "webhook-7"]
    
    // === опции ===
    fuelTankVolume: Option[Double] = None  // литры, для датчика топлива
)
```

**Где хранится:**
```
В памяти:
  ConnectionState.Ref {
    cachedContext: Option[DeviceContext],
    contextCachedAt: Long
  }

В Redis:
  HASH device:{imei} (CONTEXT FIELDS)
```

**Используется для:**
```scala
if context.hasGeozones || context.hasSpeedRules then
  kafka.publish("gps-events-rules", point)  // → Rules Engine

if context.hasRetranslation then
  kafka.publish("gps-events-retranslation", point)  // → External systems
```

### 4.5 GpsEventMessage (для Kafka)

```scala
/**
 * Обогащенное GPS событие для публикации в Kafka
 * 
 * Содержит:
 * - GPS точку
 * - Контекст из DeviceContext
 * - Metadata
 * 
 * Отправляется в Kafka топики:
 * - gps-events (базовые события)
 * - gps-events-rules (с геозонами и правилами)
 * - gps-events-retranslation (ретрансляция)
 */
case class GpsEventMessage(
    vehicleId: Long,
    organizationId: Long,
    imei: String,
    latitude: Double,
    longitude: Double,
    altitude: Int,
    speed: Int,
    course: Int,              // угол
    satellites: Int,
    deviceTime: Long,         // время на трекере
    serverTime: Long,         // время на сервере
    
    // === Контекст (из DeviceContext) ===
    hasGeozones: Boolean,
    hasSpeedRules: Boolean,
    hasRetranslation: Boolean,
    retranslationTargets: Option[List[String]],
    
    // === Статус ===
    isMoving: Boolean,        // результат Stationary Filter
    isValid: Boolean,         // прошла все фильтры
    protocol: String          // teltonika, wialon и т.д.
) derives JsonCodec
```

**Где создаётся:**
```scala
GpsProcessingService.processPoint()
  // Берёт GpsPoint + DeviceContext
  // Создаёт GpsEventMessage
  // Публикует в Kafka
```

### 4.6 DeviceData (полный объект из Redis)

```scala
/**
 * Полные данные устройства, прочитанные из Redis HASH device:{imei}
 * 
 * Содержит все три типа полей:
 * - CONTEXT (Device Manager пишет)
 * - POSITION (Connection Manager пишет)
 * - CONNECTION (Connection Manager пишет)
 * 
 * Используется ТОЛЬКО при аутентификации (первый HGETALL)
 * Затем разбирается на:
 * - cachedContext (в ConnectionState)
 * - lastPosition (в ConnectionState)
 */
case class DeviceData(
    // === CONTEXT ===
    vehicleId: Long,
    organizationId: Long,
    name: String = "",
    speedLimit: Option[Int] = None,
    hasGeozones: Boolean = false,
    hasSpeedRules: Boolean = false,
    hasRetranslation: Boolean = false,
    retranslationTargets: List[String] = List.empty,
    fuelTankVolume: Option[Double] = None,
    
    // === POSITION ===
    lat: Option[Double] = None,
    lon: Option[Double] = None,
    speed: Option[Int] = None,
    course: Option[Int] = None,
    altitude: Option[Int] = None,
    satellites: Option[Int] = None,
    time: Option[String] = None,
    isMoving: Option[Boolean] = None,
    
    // === CONNECTION ===
    instanceId: Option[String] = None,
    protocol: Option[String] = None,
    connectedAt: Option[String] = None,
    lastActivity: Option[String] = None,
    remoteAddress: Option[String] = None
):
  /** Конвертация в DeviceContext для сохранения в ConnectionState */
  def toDeviceContext: DeviceContext = DeviceContext(
    organizationId = organizationId,
    vehicleId = vehicleId,
    name = name,
    speedLimit = speedLimit,
    hasGeozones = hasGeozones,
    hasSpeedRules = hasSpeedRules,
    hasRetranslation = hasRetranslation,
    retranslationTargets = retranslationTargets,
    fuelTankVolume = fuelTankVolume
  )
  
  /** Конвертация в GpsPoint из позиции для Dead Reckoning */
  def previousPosition: Option[GpsPoint] =
    for
      la <- lat
      lo <- lon
    yield GpsPoint(
      vehicleId = vehicleId,
      latitude = la,
      longitude = lo,
      altitude = altitude.getOrElse(0),
      speed = speed.getOrElse(0),
      angle = course.getOrElse(0),
      satellites = satellites.getOrElse(0),
      timestamp = time.flatMap(s => Try(Instant.parse(s).toEpochMilli).toOption).getOrElse(0L)
    )
```

---

## 5. SUMMARY

### Ключевые моменты

1. **Единая структура** - `device:{imei}` HASH хранит ВСЁ об устройстве
2. **Три слоя данных** - CONTEXT (редко меняется), POSITION (часто), CONNECTION (при подключении)
3. **In-Memory кэш** - DeviceContext кэшируется на 1 час в ConnectionState.Ref
4. **Instant sync** - Redis Pub/Sub уведомляет об изменениях (Pub/Sub)
5. **Оптимизация** - 864M операций → 10k (HGETALL) + 864M (HMSET)
6. **Модели** - GpsRawPoint → GpsPoint → GpsEventMessage (эволюция обогащения)
