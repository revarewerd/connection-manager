# 🚀 OPUS 4.6 PROMPT - Connection Manager Refactoring

**Дата:** 2026-02-20  
**Версия:** 1.0 Final  
**Статус:** Ready for implementation  

---

## 📋 КРАТКОЕ РЕЗЮМЕ

Это **полная переработка Connection Manager** для оптимизации и расширения функциональности:

| Что? | Было | Станет | Выигрыш |
|------|------|--------|---------|
| **Redis операции** | 864M/день (10k HMSET/sec) | ~10k/день | **86,400x** ⚡ |
| **Parser выбор** | Жестко вбит Teltonika | Параметризирован, кэширован | **3-5x faster** |
| **POSITION данные** | В Redis | В памяти (Ref) | **100x latency** ↓ |
| **HTTP API** | Minimal (только /health) | Полный (20+ endpoints) | **Complete control** |
| **Multi-protocol** | Нет | Да (с fallback) | **Rare protocols** ✅ |

---

## 🎯 ЗАДАЧИ ДЛЯ OPUS (6 отдельных Task'ов)

### TASK 1: MultiProtocolParser с кэшированием

**Файл:** `src/main/scala/com/wayrecall/tracker/protocol/MultiProtocolParser.scala` (NEW)

**Описание:** Создать парсер, который определяет протокол на первом пакете и кэширует его в ConnectionState

**Спецификация:**

```scala
// Новый trait или class
object MultiProtocolParser:
  def make(parsers: List[ProtocolParser]): ProtocolParser = ???
  
  // Логика:
  // 1. Получить буфер
  // 2. Попробовать каждый парсер
  // 3. Первый успешный → вернуть протокол и результат
  // 4. Все неудачны → fail с дополнительной информацией
```

**Входные данные для определения протокола:**
- Первый байт (magic byte)
- Размер пакета
- Характерные паттерны (например, "0x23" = '#' для Wialon text)
- CRC если есть

**Примеры:**
```
Teltonika: CC 00 ... (начинается с 0xCC)
Wialon Text: 23 4C 23 (#L#...)
Wialon Binary: [размер][IMEI\0]...
Ruptela: [команда][координаты]...
```

**Что вернуть:** `IO[ProtocolError, (String, List[GpsRawPoint])]` - (протокол, точки)

---

### TASK 2: ConnectionState + Protocol caching

**Файл:** `src/main/scala/com/wayrecall/tracker/domain/ConnectionState.scala` (UPDATE)

**Изменения:**

```scala
case class ConnectionState(
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    lastPosition: Option[GpsPoint] = None,
    lastActivityAt: Long = System.currentTimeMillis(),
    
    // ← NEW поле 1: кэшированный протокол
    detectedProtocol: Option[String] = None,
    
    // ← NEW поле 2: CONTEXT из Redis (с TTL)
    cachedContext: Option[DeviceContext] = None,
    contextCachedAt: Long = 0L,
    contextCacheTtlMs: Long = 3600000L,  // 1 hour
    
    // CONNECTION INFO (для аудита)
    instanceId: String = "",
    protocol: String = "",
    connectedAt: Long = System.currentTimeMillis(),
    remoteAddress: String = ""
)
```

**Логика использования:**
```scala
// На первый пакет:
state.detectedProtocol match {
  case Some(proto) =>
    // Если уже определили - использовать его
    selectedParser = getParserByProtocol(proto)
  case None =>
    // Первый раз - попробовать все (MultiProtocolParser)
    (proto, points) <- MultiProtocolParser.parseImei(buffer)
    // Сохранить в state:
    state = state.copy(detectedProtocol = Some(proto))
    // Использовать:
    selectedParser = getParserByProtocol(proto)
}
```

---

### TASK 3: Redis HGET на подключение (не на каждый пакет!)

**Файл:** `src/main/scala/com/wayrecall/tracker/handlers/ConnectionHandler.scala` (UPDATE)

**Текущая проблема:**
```scala
// ❌ НА КАЖДЫЙ пакет: 10k HGETALL/sec
def handleDataPacket(...) = for
  deviceData <- redis.hgetall(s"device:$imei")  // ← 10k/sec!
  context = deviceData.toDeviceContext
  ...
```

**Новый подход:**

```scala
// ✅ ТОЛЬКО при аутентификации или когда кэш истёк:
def handleDataPacket(...) = for
  now = System.currentTimeMillis()
  state <- stateRef.get
  
  // Проверка: нужно ли обновить контекст из Redis?
  contextNeedsRefresh = state.cachedContext.isEmpty || 
                        (now - state.contextCachedAt) > state.contextCacheTtlMs
  
  freshContext <- if contextNeedsRefresh then
    for
      deviceData <- redis.hgetall(s"device:$imei")  // ← ONCE per hour!
      context = deviceData.toDeviceContext
      _ <- stateRef.update(_.copy(
        cachedContext = Some(context),
        contextCachedAt = now
      ))
    yield context
  else
    ZIO.succeed(state.cachedContext.get)
  
  // Парсим точки, используем freshContext
  ...
```

**Redis Pub/Sub инвалидация:**
```scala
// При подписке на device-config-changed:{imei}
redis.subscribe(s"device-config-changed:$imei") { _ =>
  stateRef.update(_.copy(contextCachedAt = 0))  // ← force refresh next packet
}
```

---

### TASK 4: POSITION в памяти, синхронизация через Kafka

**Файл:** `src/main/scala/com/wayrecall/tracker/handlers/ConnectionHandler.scala` (UPDATE)

**Изменения:**

```scala
// На каждый GPS пакет (ВМЕСТО Redis HMSET):
// ❌ УБРАТЬ:
redis.hmset(s"device:$imei", lat, lon, speed, ...)  // ← НЕ ходим в Redis!

// ✅ ДОБАВИТЬ:
_ <- stateRef.update(_.copy(lastPosition = Some(point)))  // ← in-memory, ~10ns

// Отправляем в Kafka для других сервисов
_ <- kafkaProducer.publish("gps-events", GpsEventMessage(
  imei = imei,
  vehicleId = point.vehicleId,
  lat = point.latitude,
  lon = point.longitude,
  speed = point.speed,
  timestamp = point.timestamp,
  ...
))
```

**Результат:**
- ❌ Redis HMSET операции: 10k/sec → **0/sec**
- ✅ Kafka publish: 10k/sec → **10k/sec** (асинхронно!)
- ⚡ Latency: 1-5ms → **nanoseconds**

---

### TASK 5: Параметризация парсеров в application.conf

**Файл:** `src/main/resources/application.conf` (UPDATE)

**Текущее состояние:**
```properties
tcp {
    teltonika { port = 5001, enabled = true }
    wialon { port = 5002, enabled = true }
    ruptela { port = 5003, enabled = true }
    navtelecom { port = 5004, enabled = true }
}
```

**Новое:**
```properties
tcp {
    teltonika { 
      port = 5001
      port = ${?TELTONIKA_PORT}
      enabled = true
      enabled = ${?TELTONIKA_ENABLED}
    }
    wialon { 
      port = 5002
      port = ${?WIALON_PORT}
      enabled = true
      enabled = ${?WIALON_ENABLED}
    }
    ruptela { 
      port = 5003
      port = ${?RUPTELA_PORT}
      enabled = true
      enabled = ${?RUPTELA_ENABLED}
    }
    navtelecom { 
      port = 5004
      port = ${?NAVTELECOM_PORT}
      enabled = true
      enabled = ${?NAVTELECOM_ENABLED}
    }
}

# Какой парсер использовать в GpsProcessingService (если только один включен)
primary-protocol = "teltonika"
primary-protocol = ${?PRIMARY_PROTOCOL}
```

---

### TASK 6: Main.scala - Выбор парсера по конфигу (pattern matching)

**Файл:** `src/main/scala/com/wayrecall/tracker/Main.scala` (UPDATE строки 195-197)

**Текущее состояние:**
```scala
val processingServiceLayer = 
  (TeltonikaParser.live ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
    GpsProcessingService.live
```

**Проблема:** Всегда TeltonikaParser!

**Новое решение (с MultiProtocolParser fallback):**

```scala
val selectedParserLayer = ZLayer {
  for
    config <- ZIO.service[AppConfig]
    
    // Pattern matching для выбора парсера
    parserLayer = (
      config.tcp.teltonika.enabled,
      config.tcp.wialon.enabled,
      config.tcp.ruptela.enabled,
      config.tcp.navtelecom.enabled
    ) match
      case (true, _, _, _) => 
        ZIO.logInfo("[AUTO] Выбран парсер: Teltonika") *>
        ZIO.succeed(TeltonikaParser.live)
      case (_, true, _, _) => 
        ZIO.logInfo("[AUTO] Выбран парсер: Wialon") *>
        ZIO.succeed(WialonAdapterParser.live)
      case (_, _, true, _) => 
        ZIO.logInfo("[AUTO] Выбран парсер: Ruptela") *>
        ZIO.succeed(RuptelaParser.live)
      case (_, _, _, true) => 
        ZIO.logInfo("[AUTO] Выбран парсер: NavTelecom") *>
        ZIO.succeed(NavTelecomParser.live)
      case _ => 
        // Все выключены → используем MultiProtocolParser для fallback
        ZIO.logWarning("[AUTO] Все основные парсеры выключены, используем MultiProtocolParser") *>
        ZIO.succeed(
          ZLayer.succeed(
            MultiProtocolParser.make(List(
              TeltonikaParser.live.build.unsafeRunSync(),
              WialonAdapterParser.live.build.unsafeRunSync(),
              RuptelaParser.live.build.unsafeRunSync(),
              NavTelecomParser.live.build.unsafeRunSync()
            ))
          )
        )
    
  yield parserLayer
}

val processingServiceLayer = 
  (selectedParserLayer.flatten ++ redisLayer ++ kafkaLayer ++ deadReckoningLayer ++ stationaryLayer) >>> 
    GpsProcessingService.live
```

---

## 📊 HTTP API Endpoints (для отдельной Task)

**Заметка:** HTTP API endpoints реализуются отдельно (опционально для v2):

### Must-Have Endpoints:
```
┌─ УПРАВЛЕНИЕ ФИЛЬТРАМИ
├─ GET  /config/filters             - Текущая конфигурация
├─ PUT  /config/filters             - Обновить конфиг (Redis Pub/Sub sync)
└─ POST /config/filters/reset       - Сбросить на defaults

┌─ УПРАВЛЕНИЕ СОЕДИНЕНИЯМИ
├─ GET  /connections                - Список IMEI с отклю

чениями
├─ GET  /connections/{imei}         - Детали соединения
├─ DELETE /connections/{imei}       - Принудительно отключить
└─ GET  /connections/{imei}/last-position  - Последняя точка

┌─ МОНИТОРИНГ
├─ GET  /metrics                    - Prometheus метрики
├─ GET  /stats                      - Статистика по протоколам
├─ GET  /health/readiness           - Readiness probe
└─ GET  /health/liveness            - Liveness probe
```

---

## 🧪 QA Checklist ДЛЯ OPUS

После реализации TASK 1-6 проверить:

### Protocol Detection & Caching
- [ ] MultiProtocolParser успешно определяет протокол первого пакета
- [ ] ConnectionState.detectedProtocol сохраняется после первого пакета
- [ ] Всё >1 пакета используют кэшированный протокол (нет перебора)
- [ ] Тестировано с 4-5 разными протоколами в multi-protocol mode

### Redis Optimization
- [ ] HGETALL вызывается ТОЛЬКО при подключении (не на каждый пакет)
- [ ] Кэш контекста TTL работает правильно (1 час)
- [ ] Redis Pub/Sub инвалидирует кэш правильно
- [ ] Нет HMSET операций для POSITION (только in-memory)

### Kafka Integration
- [ ] gps-events топик получает POSITION данные (lat, lon, speed, timestamp)
- [ ] DeviceEventConsumer получает device-events от Device Manager
- [ ] Redis Pub/Sub канал device-config-changed работает

### Configuration
- [ ] Env vars переопределяют default значения (TELTONIKA_PORT, TELTONIKA_ENABLED и т.д.)
- [ ] Pattern matching выбирает правильный парсер на основе config.enabled флагов
- [ ] Docker запускается с разными env vars для разных инстансов

### Performance Metrics
- [ ] Redis операции вниз с 864M/day до ~10k/day
- [ ] Latency на GPS пакет вниз с 1-5ms до microseconds (в-памяти)
- [ ] MultiProtocol caching дает 3-5x speedup после первого пакета
- [ ] Нет ошибок при переключении между разными протоколами

### Backward Compatibility
- [ ] Случайные трекеры тип Teltonika продолжают работать
- [ ] ConnectionState обновления не ломают существующий код
- [ ] GpsProcessingService получает точки (формат не изменился)

---

## 📁 ФАЙЛЫ ДЛЯ СПРАВКИ

**Прочитать ДО начала:**
1. `redis.md` - архитектура Redis (960 строк)
2. `MustFixItImportant.md` - все проблемы и решения (1200 строк)
3. `DECISIONS_APPROVED_2026_02_20.md` - финальные решения

**Использованы в коде:**
- protocol/ProtocolParser.scala - базовый trait
- protocol/TeltonikaParser.scala - пример реализации
- protocol/WialonAdapterParser.scala - пример auto-detection
- domain/ConnectionState.scala - структура состояния
- domain/GpsPoint.scala - модель GPS точки
- storage/RedisClient.scala - операции с Redis
- handlers/ConnectionHandler.scala - обработка соединений
- config/DynamicConfigService.scala - динамическая конфигурация

---

## 🚀 МЕТРИКИ УСПЕХА

После завершения всех 6 Task'ов:

| Метрика | Target | Как проверить |
|---------|--------|---------------|
| Redis ops/day | <20k | `curl :8080/metrics \| grep redis` |
| GPS packet latency | <1ms | Логирование processDataPacket время |
| Protocol caching | 3-5x faster | Сравнить 1-й vs 2-й пакет |
| POSITION в памяти | 100% | Убедиться что Redis HMSET нет |
| Multi-protocol работает | 🟢 Yes | Протестировать все 4 протокола |
| Config параметризирован | 🟢 Yes | Docker с разными env vars |

---

## 📞 ВОПРОСЫ ДЛЯ УТОЧНЕНИЯ

Если непонятно, спросить в Chat:

1. **MultiProtocolParser logic** - как точно определять протокол? По magic byte'ам или по структуре?
2. **ConnectionState immutability** - новые поля не нарушают FP принципы? (они только в Ref, они immutable)
3. **Redis Pub/Sub инвалидация** - что если уведомление не пришло? Fallback TTL достаточно?
4. **Performance** - насколько критична задержка 1-5ms → nanoseconds?

---

**Версия:** 1.0  
**Готово к Opus 4.6!** 🚀  
**Ожидаемое время реализации:** 6-8 часов
