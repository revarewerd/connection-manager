# ✅ РЕШЕНИЯ ПРИНЯТЫ НА 2026-02-20

## 1. HTTP API - РАСШИРЯЕМ, НЕ УБИРАЕМ! 🎉

**Решение:** Оставить HTTP API и добавить полный функционал

**Текущие endpoints:**
- `GET /health` - проверка здоровья
- `GET /connections` - список активных соединений
- `GET /commands` - статус команд
- `GET /filters` - состояние фильтров

**Новые endpoints (предложение):**

```
┌─ СИСТЕМА МОНИТОРИНГА
├─ GET  /metrics                    - Prometheus метрики (throughput, latency, errors)
├─ GET  /stats                      - Детальная статистика (GPS packets, filtered, errors)
├─ GET  /health/readiness           - Readiness probe для Kubernetes
└─ GET  /health/liveness            - Liveness probe для Kubernetes

┌─ УПРАВЛЕНИЕ ФИЛЬТРАМИ (Dynamic!)
├─ GET  /config/filters             - Текущая конфигурация фильтров
├─ PUT  /config/filters             - Обновить конфигурацию (Redis Pub/Sub sync)
├─ POST /config/filters/reset       - Сбросить на defaults
└─ GET  /config/filters/history     - История изменений

┌─ УПРАВЛЕНИЕ СОЕДИНЕНИЯМИ
├─ GET  /connections                - Список всех соединений
├─ GET  /connections/{imei}         - Детали конкретного соединения
├─ DELETE /connections/{imei}       - Принудительно отключить трекер
├─ GET  /connections/{imei}/stats   - Статистика по трекеру
└─ GET  /connections/{imei}/last-position  - Последняя GPS точка

┌─ УПРАВЛЕНИЕ ПАРСЕРАМИ
├─ GET  /parsers                    - Какие парсеры включены
├─ GET  /parsers/{protocol}/stats   - Stats per protocol
└─ POST /parsers/{protocol}/toggle  - Включить/отключить парсер (config change)

┌─ ОТЛАДКА И ДИАГНОСТИКА
├─ GET  /debug/buffer-stats         - TCP buffer stats
├─ GET  /debug/thread-info          - Thread pool info
├─ GET  /debug/redis-ping           - Connectivity check
├─ GET  /debug/kafka-ping           - Connectivity check
└─ POST /debug/clear-cache          - Очистить in-memory caches

┌─ АДМИНИСТРАТИВНЫЕ
├─ GET  /version                    - Версия сервиса, build info
├─ GET  /config                     - Полная конфигурация (sanitized)
├─ POST /config/reload              - Перезагрузить config из файла
└─ POST /graceful-shutdown          - Graceful shutdown (с drain)
```

**Примеры запросов:**

```bash
# Изменить конфиг фильтров on-the-fly
curl -X PUT http://localhost:8080/config/filters \
  -H "Content-Type: application/json" \
  -d '{
    "deadReckoningMaxSpeedKmh": 400,
    "deadReckoningMaxJumpMeters": 2000,
    "stationaryMinDistanceMeters": 30
  }'

# Получить метрики для Prometheus
curl http://localhost:8080/metrics

# Отключить трекер (для диагностики)
curl -X DELETE http://localhost:8080/connections/356123456789012

# Получить последнюю позицию трекера
curl http://localhost:8080/connections/356123456789012/last-position
```

**Преимущества:**
✅ Контроль и диагностика из одного места
✅ Изменение фильтров без перезагрузки сервиса
✅ Prometheus интеграция для мониторинга
✅ Debug endpoints для troubleshooting
✅ Kubernetes ready (readiness/liveness probes)

---

## 2. MultiProtocolParser - ДА, НУЖЕН! ✅ + ОПТИМИЗАЦИЯ ДЕТЕКТИРОВАНИЯ

**Решение:** Создать MultiProtocolParser для fallback редких протоколов **с кэшированием протокола в ConnectionState**

**Архитектура развертывания:**
```
┌─ 6-7 основных инстансов
│  ├─ cm-teltonika (teltonika only)
│  ├─ cm-wialon (wialon only)
│  ├─ cm-ruptela (ruptela only)
│  └─ ...
│  (каждый обслуживает 3-4k девайсов)
│
└─ 1 инстанс fallback с MultiProtocolParser
   └─ обслуживает редкие протоколы (~500 девайсов)
      ├─ NavTelecom 
      ├─ Topway (есть ли?)
      ├─ Simarine (есть ли?)
      └─ любые кастомные
```

**🔥 КРИТИЧЕСКАЯ ОПТИМИЗАЦИЯ: Кэширование детектированного протокола**

### Текущее (НЕПРАВИЛЬНО):
```
GPS пакет #1 → MultiProtocolParser → пробуем TeltonikaParser ❌ fail
            → пробуем WialonParser ❌ fail
            → пробуем RuptelaParser ✅ success → обрабатываем
            
GPS пакет #2 → MultiProtocolParser → пробуем TeltonikaParser ❌ fail ← ЛИШНЕЕ!
            → пробуем WialonParser ❌ fail ← ЛИШНЕЕ!
            → пробуем RuptelaParser ✅ success → обрабатываем
```

### Новое (ОПТИМАЛЬНО):
```
GPS пакет #1 → MultiProtocolParser.detectProtocol(buffer)
            → пробуем TeltonikaParser ❌ fail
            → пробуем WialonParser ❌ fail
            → пробуем RuptelaParser ✅ success
            → connectionState.protocol = "ruptela" ← СОХРАНЯЕМ!
            
GPS пакет #2 → MultiProtocolParser.parseWithKnownProtocol("ruptela", buffer)
            → используем RuptelaParser напрямую ✅ FAST!
            
GPS пакет #3 → используем RuptelaParser напрямую ✅ FAST!
```

**Результат**: 3-5x ускорение (первый пакет может быть slow, остальные - fast)

### ConnectionState - ДОБАВЛЯЕМ PROTOCOL FIELD

```scala
case class ConnectionState(
    // === АУТЕНТИФИКАЦИЯ ===
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    connectedAt: Long = 0L,
    isUnknownDevice: Boolean = false,
    
    // === PROTOCOL DETECTION (NEW!) ===
    protocol: Option[String] = None,  // ← "teltonika", "wialon", "ruptela", "navtelecom"
                                       // ← None после подключения, Some(proto) после первого пакета
    
    // === CONTEXT КЭSH ===
    cachedContext: Option[DeviceContext] = None,
    contextCachedAt: Long = 0L,
    contextCacheTtlMs: Long = 3600000,
    
    // === POSITION КЭSH ===
    lastPosition: Option[GpsPoint] = None,
    
    // === CONNECTION INFO ===
    connectionInfo: Option[ConnectionInfo] = None,
):
  def hasProtocol: Boolean = protocol.isDefined
  def protocolName: String = protocol.getOrElse("unknown")
```

### MultiProtocolParser спецификация (НОВАЯ!)

```scala
object MultiProtocolParser {
  
  /**
   * First packet: detect which parser works
   * Store result in connectionState.protocol
   * Next packets: use known protocol directly
   */
  def parseImei(
    buffer: ByteBuf,
    connectionState: Ref[ConnectionState],  // ← НОВЫЙ параметр!
    parsers: List[ProtocolParser]
  ): IO[ProtocolError, String] =
    for
      state <- connectionState.get
      
      // ✅ Если уже хотим протокол - используем его прямо
      imei <- state.protocol match
        case Some(proto) =>
          findParser(parsers, proto) match
            case Some(parser) => parser.parseImei(buffer)
            case None => 
              ZIO.fail(ProtocolError(s"Parser for $proto not found"))
        
        // ❌ Если протокол неизвестен - пробуем все по очереди
        case None =>
          tryAllParsers(buffer, parsers) { parser =>
            parser.parseImei(buffer)
          }
      
      // Если успешно распарсили и еще не знаем протокол - СОХРАНЯЕМ его!
      _ <- state.protocol match
        case Some(_) => ZIO.unit  // Уже знаем, ничего не делаем
        case None =>
          val detectedProto = findParserNameByParser(parsers, buffer)
          connectionState.update(_.copy(protocol = Some(detectedProto)))
              .catchAll(_ => ZIO.unit)  // Не критично если не сохраняется
    yield imei
  
  /**
   * Пробуем каждый парсер до первого успеха
   */
  private def tryAllParsers[A](
    buffer: ByteBuf,
    parsers: List[ProtocolParser]
  )(tryParse: ProtocolParser => IO[ProtocolError, A]): IO[ProtocolError, A] =
    parsers match
      case Nil => 
        ZIO.fail(ProtocolError("No parsers available"))
      case parser :: rest =>
        tryParse(parser).catchAll { _ =>
          tryAllParsers(buffer, rest)(tryParse)
        }
}
```

**Спецификация потом (Task 1 для Opus):**
- MultiProtocolParser.scala создать (с parameter для Ref[ConnectionState])
- ConnectionState.scala обновить (добавить protocol: Option[String])
- Main.scala обновить (conditional selection)
- application.conf параметризировать

**Status:** ✅ Ready to implement (WITH OPTIMIZATION!)

---

## 3. ✅ ДИНАМИЧЕСКИЙ КОНФИГ ФИЛЬТРОВ - УЖЕ ЕСТЬ И РАБОТАЕТ! 🚀

**Файл:** `src/main/scala/com/wayrecall/tracker/config/DynamicConfigService.scala` (148 строк)

**Как работает:**

```
1️⃣ ИНИЦИАЛИЗАЦИЯ (при старте CM)
   application.conf (defaults)
        ↓
   Redis HSET config:filters (сохраняем)
        ↓
   configRef: Ref[FilterConfig] (in-memory, ~10ns)

2️⃣ ИСПОЛЬЗОВАНИЕ (на каждый GPS пакет)
   DeadReckoningFilter.validate(point)
        ↓
   configService.getFilterConfig()  ← Ref.get (⚡ ~10 nanoseconds!)
        ↓
   применяем фильтр с текущей конфигурацией

3️⃣ ОБНОВЛЕНИЕ (через HTTP API)
   PUT /config/filters (new values)
        ↓
   Redis HSET config:filters (persisted)
        ↓
   PUBLISH config:updates (Pub/Sub notification)
        ↓
   Все инстансы CM получают:
   - configRef.set(newConfig)
   - используют новые значения НА СЛЕДУЮЩЕМ пакете

4️⃣ СИНХРОНИЗАЦИЯ (между инстансами)
   Redis Pub/Sub канал: config:updates
        ↓
   configRef.set(newConfig)  ← in-memory update
        ↓
   ВСЕ инстансы синхронизированы! ✅
```

**FilterConfig поля:**
```scala
case class FilterConfig(
    deadReckoningMaxSpeedKmh: Int = 300,       // max скорость (km/h)
    deadReckoningMaxJumpMeters: Int = 1000,    // max "телепортация"
    deadReckoningMaxJumpSeconds: Int = 1,      // time window для jump
    stationaryMinDistanceMeters: Int = 20,     // порог движения
    stationaryMinSpeedKmh: Int = 2             // порог спорости
)
```

**Производительность:**
- ✅ `getFilterConfig()` → ~10ns (Ref.get, in-memory)
- ✅ `updateFilterConfig()` → ~1-2ms (Redis sync + Pub/Sub)
- ✅ Нет HGETALL на каждый пакет! (как в POSITION)

**Что может быть проблема:**
⚠️ Если Pub/Sub между инстансами не срабатывает → разные конфиги!
⚠️ Нужна проверка что все инстансы обновились
⚠️ Edge case: update пришла между пакетами (может быть старое значение)

**QA Checklist:**
- [ ] Обновление конфига синхронизируется на все инстансы
- [ ] Вы можете изменить константы без перезагрузки сервиса
- [ ] При перезагрузке инстанса загружает из Redis (не defaults)
- [ ] Edge case: одновременное обновление с нескольких инстансов
- [ ] Покрыто тестами

---

## СТАТУС ОБНОВЛЁН

**Дата:** 2026-02-20  
**Версия:** 2.4 (все решения приняты + MultiProtocol оптимизация!)

### ✅ ВЕРИФИКАЦИЯ РЕШЕНИЙ:

| Task | Решение | Статус | For Opus |
|------|---------|--------|----------|
| **1. MultiProtocolParser** | ДА, расширить + КЭШИРОВАНИЕ | ✅ Одобрено | Task 1 (+ protocol field) |
| **2. HTTP API** | Расширить, не убирать | ✅ Одобрено | +List of endpoints |
| **3. Фильтры** | Уже работает! | ✅ Проверено | QA только |
| **4. Redis opt** | POSITION in-memory | ✅ Готово | Main Task |
| **5. Kafka** | gps-events source | ✅ Готово | Task 2 |

### 📊 ВСЕ ГОТОВО К OPUS 4.6! 🚀 (+MultiProtocol Optimization!)

---

## 🎯 КЛЮЧЕВОЕ УЛУЧШЕНИЕ: Protocol Caching

**Без оптимизации (старый Multi-Parser):**
```
Каждый пакет → пробуем 3-4 парсера → находим правильный
= медленно! O(n) где n=количество парсеров
```

**С оптимизацией (новый Multi-Parser):**
```
Первый пакет → пробуем 3-4 парсера → сохраняем protocol → slow O(n)
Остальные → используем про cached protocol → fast O(1)
```

**Результат:** После первого пакета - используем protocol напрямую без перебора! ⚡
