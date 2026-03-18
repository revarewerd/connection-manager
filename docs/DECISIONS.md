# Connection Manager — Решения (ADR) v5.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-10` | Версия: `5.0`

## ADR-001: Redis → In-Memory кэш для горячего пути

**Статус:** Принято  
**Дата:** 2026-02-20  

**Контекст:** На каждый GPS-пакет выполнялись 3-4 Redis операции:
- `HGETALL device:{imei}` — контекст (маршрутизация в правильные топики)
- `HMSET position:{imei}` — сохранение позиции
- `SETEX position:{imei}` — TTL обновление
- `HGETALL position:{imei}` — предыдущая позиция для фильтрации

При 10K трекерах × 1 пакет/сек = 864M Redis ops/day.

**Решение:** Перенести в ConnectionState (ZIO Ref):
- `lastPosition` — предыдущая позиция (in-memory)
- `deviceData` — кэш RedisHASH с TTL 1 час
- Redis остаётся только для аутентификации + hourly refresh

**Результат:** ~10K Redis ops/day (×86,400 снижение).

---

## ADR-002: Parser как параметр метода, а не поле

**Статус:** Принято  
**Дата:** 2026-02-20  

**Контекст:** `GpsProcessingService.Live` хранил `parser: ProtocolParser` как поле.
Это требовало создавать отдельный экземпляр сервиса для каждого протокола,
и `TeltonikaParser.live` был хардкодед в `processingServiceLayer`.

**Решение:** Parser передаётся как параметр в методы:
```scala
def processDataPacket(buffer, imei, vehicleId, prevPosition, parser: ProtocolParser, ...)
```

ConnectionHandler имеет свой parser при создании и передаёт его в service.

**Результат:** Один `GpsProcessingService.Live` обслуживает все протоколы.

---

## ADR-003: MultiProtocolParser с auto-detection

**Статус:** Принято  
**Дата:** 2026-02-20  

**Контекст:** При миграции со STELS, некоторые трекеры перенастраиваются на новый сервер
и неизвестно какой протокол они используют.

**Решение:** `MultiProtocolParser.asProtocolParser()`:
1. Первый пакет → detection по magic bytes + fallback перебор
2. Кэширование результата в `@volatile`
3. Все последующие пакеты парсятся кэшированным парсером

**Порт:** 5100 (TCP), `multi.enabled = false` по умолчанию.

---

## ADR-004: 18 GPS-протоколов ★ ОБНОВЛЕНО v4.0

**Статус:** Принято  
**Дата:** 2026-03-01 (обновлено из v3.0)

**Контекст:** Legacy STELS поддерживал 8 протоколов (порты 9082-9089).
CM v2.x — 4 протокола. CM v3.0 — 10 протоколов.
Обнаружены ещё 8 протоколов реализованных в legacy `core/`:
Galileosky, Concox GT06, TK102/103, Arnavi, ADM, Queclink GTLT, МикроМаяк.

**Решение:** Расширить до 18 протоколов. Новые порты: 5009-5015.
Все новые протоколы `enabled = false` по умолчанию.
Все 8 новых протоколов — ReceiveOnly (нет TCP-команд).

**Legacy mapping:**
```
Порт STELS → Порт CM   │ Протокол
9082       → 5008       │ DTM
9083       → 5007       │ AutophoneMayak
9084       → 5006       │ SkySim
9085       → 5004       │ NavTelecom
9086       → 5005       │ GoSafe
9087       → 5002       │ Wialon IPS
9088       → 5001       │ Teltonika
9089       → 5003       │ Ruptela
(new)      → 5009       │ Galileosky
(new)      → 5010       │ Concox GT06
(new)      → 5011       │ TK102/TK103
(new)      → 5012       │ Arnavi
(new)      → 5013       │ ADM
(new)      → 5014       │ Queclink GTLT
(new)      → 5015       │ МикроМаяк
```

---

## ADR-005: HTTP API расширение (20+ endpoints)

**Статус:** Принято  
**Дата:** 2026-02-20

**Контекст:** CM v2.x имел 4 endpoint'а.

**Решение:** Расширить до 20+:
- Prometheus metrics (`GET /api/metrics`)
- K8s probes (`/health/readiness`, `/health/liveness`)
- Управление соединениями (list, detail, disconnect, last-position)
- Парсеры (list с статистикой)
- Фильтры (get, set, reset)
- Отладка (redis-ping, kafka-ping, clear-cache)

**Порт:** 10090 (был 8080 — конфликт с API Gateway).

---

## ADR-006: Context TTL refresh

**Статус:** Принято  
**Дата:** 2026-03-01

**Контекст:** DeviceData кэшируется при IMEI-аутентификации. Если данные устройства
изменились (например, добавлены геозоны), CM узнаёт об этом только после переподключения.

**Решение:** Двухуровневая инвалидация:
1. **TTL-based refresh:** `ConnectionState.isContextExpired(now)` проверяется при каждом пакете.
   Если прошло > 1 час → `refreshDeviceContext(imei)` → HGETALL из Redis.
2. **Pub/Sub-based invalidation:** При изменении device → `invalidateContext` (contextCachedAt = 0).

**Результат:** Максимальная задержка обновления = 1 час (или мгновенно через Pub/Sub).

---

## ADR-007: Незарегистрированные трекеры

**Статус:** Принято  
**Дата:** 2026-02-20

**Контекст:** Что делать если трекер с неизвестным IMEI подключается?

**Решение:** НЕ закрывать соединение! Вместо:
1. Принять ACK → трекер продолжит слать данные
2. Опубликовать `UnknownDeviceEvent` в `unknown-devices`
3. GPS-точки публиковать в `unknown-gps-events` (без фильтрации)
4. Device Manager может автоматически зарегистрировать устройство

**Флаг:** `ConnectionState.isUnknownDevice = true`

---

## ADR-008: Command Encoder Architecture ★ NEW v4.0

**Статус:** Принято  
**Дата:** 2026-03-01

**Контекст:** В v3.0 каждый ProtocolParser содержал inline код для encodeCommand.
Логика энкодирования была дублирована (CRC, packetizing) и не тестировалась отдельно.
Некоторые парсеры (DTM, Ruptela) имели 40-50 строк inline-кода прямо в методе encodeCommand.

**Решение:** Создать отдельный пакет `command/` с иерархией:
```
command/
├── CommandEncoder.scala         # trait + factory + ReceiveOnlyEncoder
├── TeltonikaEncoder.scala       # Codec 12 (7 команд)
├── NavTelecomEncoder.scala      # NTCB FLEX (3 команды)
├── DtmEncoder.scala             # Binary IOSwitch (1 команда)
├── RuptelaEncoder.scala         # Binary 0x65-0x67 (6 команд)
└── WialonEncoder.scala          # Text #M# (1 команда)
```

- **CommandEncoder.forProtocol(name)** — factory, возвращает нужный encoder
- **ReceiveOnlyEncoder** — для 13 протоколов без TCP-команд (возвращает UnsupportedProtocol)
- **Парсеры делегируют:** `encodeCommand(cmd) = CommandEncoder.forProtocol(protocolName).encode(cmd)`

**Результат:** Чистое разделение ответственности. Энкодеры тестируются отдельно.

---

## ADR-009: ВСЕ точки → gps-events ★ NEW v4.0

**Статус:** Принято  
**Дата:** 2026-03-01

**Контекст:** В v3.0 в `gps-events` публиковались только валидные + moving точки.
Точки отфильтрованные Dead Reckoning или подавленные Stationary Filter терялись.
History Writer записывал в TimescaleDB только точки движения.
При анализе истории были пробелы: не видно когда машина стояла.

**Решение:** Публиковать ВСЕ точки в gps-events:
- **isValid=true, isMoving=true** — нормальное движение
- **isValid=true, isMoving=false** — стоянка (GPS-дрожание подавлено)
- **isValid=false, isMoving=false** — отфильтрована Dead Reckoning (телепортация)

Маршрутизация в gps-events-rules и gps-events-retranslation — **только moving**:
```
processPoint:
  publish to gps-events (ALWAYS)
  if isMoving:
    publish to gps-events-rules (if hasGeozones)
    publish to gps-events-retranslation (if hasRetranslation)
```

**Результат:** TimescaleDB получает полную историю. Dashboard показывает стоянки.
Rule Checker по-прежнему не тратит ресурсы на стоянки.

---

## ADR-010: gps-parse-errors topic ★ NEW v4.0

**Статус:** Принято  
**Дата:** 2026-03-01

**Контекст:** При ошибке парсинга GPS-пакета (InvalidChecksum, ParseError и др.)
ранее данные просто логировались в stderr. Не было простого способа мониторить
частоту ошибок по протоколам, находить проблемные трекеры.

**Решение:** Новый Kafka-топик `gps-parse-errors`:
- Публикуется при каждой ошибке `parser.parseData()`
- Включает hex-dump первых 512 байт пакета
- Поля: imei, protocol, errorType, errorMessage, rawPacketHex, rawPacketSize, remoteAddress, instanceId, timestamp
- Retention: 3 дня (достаточно для отладки)
- Consumer: admin-service, Grafana (мониторинг)

**Обработка ошибок в processDataPacket:**
```scala
parser.parseData(buffer, imei).foldZIO(
  failure = error => publishParseError(error, ...) *> ZIO.succeed(List.empty),
  success = points => ...
)
```

**Результат:** Мониторинг ошибок парсинга через Grafana. Быстрая диагностика проблем.

---

## ADR-011: AwaitingCommand vs PendingCommand ★ NEW v4.0

**Статус:** Принято  
**Дата:** 2026-03-01

**Контекст:** Конфликт имён: `domain.PendingCommand` (для сериализации) и
`CommandService.PendingCommand` (для отслеживания TCP-ответов с ZIO Promise).

**Решение:** Переименование:
- `domain.PendingCommand` — остаётся (serializable, Kafka-совместимый)
- `CommandService.PendingCommand` → `AwaitingCommand` (internal, содержит ZIO Promise)

```scala
// domain/ — для сериализации
case class PendingCommand(command, status, createdAt, lastAttemptAt, lastError, attemptCount)

// network/CommandService.scala — для TCP response tracking
private[network] case class AwaitingCommand(command, promise: Promise[...], sentAt)
```

**Результат:** Нет конфликта имён. Ясное разделение domain vs network concerns.

---

## ADR-012: fork() + Semaphore вместо unsafe.run() ★ NEW v5.0

**Статус:** Принято  
**Дата:** 2026-03-10

**Контекст:** `ConnectionHandler.runEffect()` использовал `runtime.unsafe.run(effect)` —
это **синхронно блокировало Netty I/O поток** на 5-20ms (время Redis + Kafka операций).
При 8 I/O потоках и 20K соединениях → thread starvation, TCP buffer overflow, reconnect storm.

**Решение:**
```scala
// Было (v4.0) — блокировало I/O
private def runEffect(effect: Task[Unit]): Unit =
  runtime.unsafe.run(effect)

// Стало (v5.0) — не блокирует
private def forkEffect(effect: Task[Unit]): Unit =
  runtime.unsafe.fork(semaphore.withPermit(effect))
```

- `runtime.unsafe.fork()` — запускает обработку в ZIO fiber, Netty callback возвращается СРАЗУ
- `Semaphore(1)` per connection — гарантирует порядок пакетов для одного IMEI

**Результат:** Netty I/O thread **никогда не блокируется**. Масштабирование до 100K+ стало возможным.

---

## ADR-013: Оптимизации для 100K TCP соединений ★ NEW v5.0

**Статус:** Принято  
**Дата:** 2026-03-10

**Контекст:** После исправления блокировки (ADR-012), проведён аудит производительности
для целевых 100K одновременных TCP-соединений. Найдено 19 проблем, 15 исправлено.

**Решения (по компонентам):**

| Компонент | Изменение | Эффект |
|-----------|-----------|--------|
| TcpServer | Epoll/KQueue native transport | O(1) per event вместо O(n) NIO |
| TcpServer | SO_RCVBUF=4K, SO_SNDBUF=4K, WaterMark(16K,32K) | Контроль памяти при 100K |
| TcpServer | PooledByteBufAllocator, SO_BACKLOG=4096 | Меньше GC, большая очередь accept |
| ConnectionRegistry | `ConcurrentHashMap` + `AtomicLong` (вместо `Ref[Map]`) | 0 аллокаций, lock-free |
| ConnectionRegistry | `unregisterIfSame(imei, channel)` | Устранение race condition |
| ConnectionHandler | 1× JSON сериализация → 3 топика (кэш) | -66% CPU на serialize |
| ConnectionHandler | `rulesEffect.zipPar(retranslationEffect)` | -33% latency |
| ConnectionHandler | `java.lang.System.currentTimeMillis()` | 0 аллокаций Clock |
| KafkaProducer | buffer.memory=256MB, max.in.flight=10 | Не блокируется при burst |
| RateLimiter | `now :: timestamps` (O(1) prepend) | Без деградации |
| IdleConnectionWatcher | `foreachParDiscard(32)` | 32× быстрее cleanup |
| build.sbt | `netty-all` → отдельные модули + epoll/kqueue | Меньше classpath |

**Осталось (0 задач):** Все 19 пунктов аудита закрыты — 15 исправлено, 4 проанализировано и закрыто.

**Подробности:** [PERFORMANCE_AUDIT_100K.md](PERFORMANCE_AUDIT_100K.md)

**Результат:** CM готов к нагрузочному тестированию на 100K соединений.

---

## ADR-014: Prometheus метрики без внешних зависимостей (CmMetrics)

**Статус:** Принято  
**Дата:** 2026-03-10

**Контекст:** Аудитом M-5 выявлено отсутствие метрик. Варианты: (1) `zio-metrics-connectors` — тянет тяжёлые зависимости; (2) собственный lightweight объект.

**Решение:** `CmMetrics` singleton с `java.util.concurrent.atomic.LongAdder` (counters) и `AtomicLong` (gauges). Метод `prometheusOutput` — Prometheus text exposition format.

**Метрики (16):** `activeConnections`, `totalConnections`, `totalDisconnections`, `packetsReceived`, `gpsPointsReceived`, `gpsPointsPublished`, `parseErrors`, `kafkaPublishErrors`, `kafkaPublishSuccess`, `redisOperations`, `unknownDevices`, `commandsSent`, `unknownDevicePackets`, `uptime`, `startedAt`.

**Инструментированы:** ConnectionRegistry (register/unregister), ConnectionHandler (packets, points, errors), HttpApi (/metrics, /stats).

**Обоснование:** LongAdder — lock-free, O(1), zero allocation. Нет runtime overhead для hot path. Достаточно для Prometheus scraping.

---

## ADR-015: Redis — одно Lettuce соединение вместо пула

**Статус:** Принято  
**Дата:** 2026-03-10

**Контекст:** Аудитом M-3 рекомендовался Redis connection pool. Анализ показал:
- Lettuce 6.x **автоматически пайплайнит** команды через одно TCP соединение
- Пропускная способность одного соединения: ~100-200K ops/sec
- Наша нагрузка (steady-state): ~6.6K-20K Redis ops/sec
- Запас: 5-10× от текущей нагрузки

**Решение:** Оставить одно соединение. Параметр `RedisConfig.poolSize` существует в конфиге, но не используется — зарезервирован на случай роста нагрузки.

**Обоснование:** Over-engineering. Connection pool добавляет сложность (checkout/return, health check, eviction) без измеримой пользы при текущей нагрузке.

---

## ADR-016: FP аудит — замена .toOption на явный pattern matching с логированием

**Статус:** Принято  
**Дата:** 2026-03-10

**Контекст:** FP аудит (score 7.0/10) выявил 4 использования `.toOption` без логирования — ошибки десериализации JSON молча проглатывались.

**Решение:** Замена `.toOption` на explicit match:
- `CommandHandler.processPendingCommands` — `ZIO.foreach` + match с `ZIO.logWarning`
- `RedisClient.getVehicleConfig` — `flatMap` + match с `ZIO.logWarning`
- `RedisClient.getPosition` — `flatMap` + match с `ZIO.logWarning`

**Исключения:** `GoSafeParser.parseData` и `GpsPoint.parseNmea` — `.toOption` допустимо (multi-packet парсинг, частичные отказы ожидаемы).

**Обоснование:** Молчаливое проглатывание ошибок десериализации маскирует проблемы с данными в Redis.
