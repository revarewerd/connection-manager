# 🔍 Аудит ФП-принципов Connection Manager

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-11` | Версия: `1.1`

## Резюме

**Общий балл: 7.0 / 10**

Connection Manager — высоконагруженный TCP-сервис с 17+ протоколами GPS-трекеров. Ядро (service/, config/, domain/, filter/, network/) написано качественно в ФП-стиле на ZIO. Основные отступления от принципов сосредоточены в **парсерах протоколов** (protocol/) и **кодировщиках команд** (command/), где бинарная работа с ByteBuf диктует императивный стиль.

---

## 1. Нарушения ФП-принципов

### 1.1 `throw` внутри `ZIO.attempt {}` — **СИСТЕМНАЯ проблема**

**Серьёзность: 🟡 СРЕДНЯЯ** | **Количество: ~100+ мест в 15 парсерах + 3 encoder-а**

Все парсеры используют паттерн:
```scala
def parseImei(buffer: ByteBuf): Task[String] = ZIO.attempt {
  if condition then throw new RuntimeException("...")
  ...
}.mapError(ProtocolError.ParseFailed(_))
```

**Затронутые файлы:**
| Файл | Кол-во throw |
|---|---|
| TeltonikaParser.scala | 9 |
| WialonParser.scala | 4 |
| RuptelaParser.scala | 2 |
| NavTelecomParser.scala | 5 |
| SkySimParser.scala | 6 |
| AutophoneMayakParser.scala | 8 |
| MicroMayakParser.scala | 7 |
| AdmParser.scala | 10 |
| GtltParser.scala | 5 |
| GalileoskyParser.scala | 9 |
| ConcoxParser.scala | 5 |
| ArnaviParser.scala | 7 |
| TK102Parser.scala | 6 |
| GoSafeParser.scala | 4 |
| WialonBinaryParser.scala | 3 |
| TeltonikaEncoder.scala | 1 |
| RuptelaEncoder.scala | 1 |
| NavTelecomEncoder.scala | 1 |

**Вердикт:** Технически безопасно (throw перехватывается ZIO.attempt + mapError), но нарушает принцип "никогда throw". Рекомендуемый подход — `ZIO.fail(ProtocolError.ParseFailed(...))` или `Either` + `ZIO.fromEither`. На hot-path (~20K пакетов/сек) throw создаёт дополнительный стектрейс, что влияет на производительность.

---

### 1.2 `var` — мутабельность

**Серьёзность: 🟡 СРЕДНЯЯ (парсеры) | 🟢 НИЗКАЯ (CRC/алгоритмы)**

**Всего найдено: ~40 мест**

#### Категория А — CRC/алгоритмические (ДОПУСТИМО)
Локальные `var` в чисто вычислительных функциях без побочных эффектов:
- RuptelaParser.scala:199-204 — CRC16 расчёт (3 var)
- NavTelecomParser.scala:232-237 — CRC16 расчёт (3 var)
- RuptelaEncoder.scala:145 — CRC (1 var)
- NavTelecomEncoder.scala:125 — CRC (1 var)
- TeltonikaEncoder.scala:122 — CRC (1 var)
- ConcoxParser.scala:50 — CRC (1 var)

**Вердикт:** допустимо — это локальные алгоритмические переменные, аналог `foldLeft` для производительности.

#### Категория Б — Состояние парсера (ПРОБЛЕМА)
`@volatile private var` в экземплярах парсеров:
- **MultiProtocolParser.scala:210-211** — `detectedParser`, `detectedProtocolName`
- **MicroMayakParser.scala:63-64** — `lastKvitok`, `lastMarker`
- **AdmParser.scala:49-51** — `imei`, `replyEnabled`, `lastDeviceId`
- **ArnaviParser.scala:46** — `cachedImei`
- **GtltParser.scala:52** — `lastAckPrefix`
- **TK102Parser.scala:48** — `lastSerial`
- **ConcoxParser.scala:43** — `lastSerial`
- **GalileoskyParser.scala:84** — `lastCrc`

**Вердикт:** Это **per-connection** состояние парсера. Каждое TCP-соединение создаёт свой экземпляр парсера, поэтому нет race condition. Но с точки зрения ФП — нарушение: лучше `ZIO.Ref` или передача через функциональный state.

#### Категория В — Мутабельный парсинг данных (МАЖОРНАЯ)
- **GalileoskyParser.scala:103** — `var imei: String = null` + while loop + null check
- **GalileoskyParser.scala:158-165** — 8 var для промежуточных данных GPS-точки
- **WialonBinaryParser.scala:113, 159** — `var course`, `var b`
- **GtltParser.scala:114-115** — `var lat`, `var lon`
- **TK102Parser.scala:96, 123-124** — `var pos`, `var lat`, `var lon`
- **MicroMayakParser.scala:246** — `var prevPoint`

**Вердикт:** `var imei: String = null` в GalileoskyParser — худшее нарушение (null + var + while). Остальные — локальные, но заменяемы на `case class` + `foldLeft`.

#### Категория Г — Известные допустимые
- **ConnectionRegistry.scala:147** — `var wasRemoved` внутри ConcurrentHashMap.compute (OK)

---

### 1.3 `null` — использование null-значений

**Серьёзность: 🟡 СРЕДНЯЯ**

| Файл | Строка | Контекст |
|---|---|---|
| GalileoskyParser.scala | 103, 106, 129 | `var imei: String = null` + `while ... && imei == null` + `if imei == null then throw` |
| KafkaProducer.scala | 83 | `if exception != null` — Java Kafka callback |
| ConnectionRegistry.scala | 149 | `if existing != null` — ConcurrentHashMap.get Java interop |
| ConnectionRegistry.scala | 185 | `if entry != null` — ConcurrentHashMap.get Java interop |

**Вердикт:** KafkaProducer и ConnectionRegistry — **допустимый** Java interop. GalileoskyParser — **нарушение**: нужно `Option[String]` вместо `null`.

---

### 1.4 Отсутствуют Opaque Types

**Серьёзность: 🟡 СРЕДНЯЯ**

Нигде в сервисе не найдено `opaque type`. Согласно архитектурным правилам должны быть:
- `Imei` (String wrapper, 15 цифр)
- `VehicleId` (Long wrapper)
- `OrganizationId` (Long wrapper)
- `DeviceId` (Long wrapper)

Сейчас `imei: String`, `vehicleId: Long`, `organizationId: Long` — везде примитивные типы. Это позволяет случайно передать vehicleId вместо organizationId.

---

### 1.5 `.ignore` — проглатывание ошибок

**Серьёзность: 🟡 СРЕДНЯЯ (2 места) | 🟢 НИЗКАЯ (8 мест)**

| Файл | Строка | Что проглатывается | Оценка |
|---|---|---|---|
| ConnectionHandler.scala | 332 | publishFilteredPoint | 🟡 — потеря GPS данных без логирования |
| ConnectionHandler.scala | 387 | publishFilteredPoint (второй путь) | 🟡 — аналогично |
| ConnectionHandler.scala | 528 | publishParseError | 🟢 — ошибка метрики, не критично |
| ConnectionHandler.scala | 576 | publishParseError | 🟢 — аналогично |
| ConnectionHandler.scala | 602 | onConnect уведомление | 🟢 — best-effort |
| ConnectionHandler.scala | 636 | disconnect уведомление | 🟢 — best-effort |
| ConnectionRegistry.scala | 117 | ctx.close() при дубликате | 🟢 — cleanup |
| IdleConnectionWatcher.scala | 138 | Redis unregister | 🟢 — cleanup |
| IdleConnectionWatcher.scala | 141 | ctx.close() при idle | 🟢 — cleanup |
| IdleConnectionWatcher.scala | 145 | весь disconnect flow | 🟡 — весь эффект проглатывается |

**Критический момент:** строки 332 и 387 — `.ignore` на публикации GPS-точек в Kafka. Если Kafka недоступен, GPS данные теряются **молча**. Нужен хотя бы `logWarning`.

---

### 1.6 `.toOption` без логирования — ✅ ИСПРАВЛЕНО (v6.0)

**Серьёзность: ✅ ИСПРАВЛЕНО**

| Файл | Строка | Контекст | Статус |
|---|---|---|---|
| CommandHandler.scala | 181 | `json.fromJson[PendingCommand].toOption` | ✅ Заменено на ZIO.foreach + match + ZIO.logWarning |
| RedisClient.scala | 189 | `fromJson[VehicleConfig].toOption` | ✅ Заменено на flatMap + match + ZIO.logWarning |
| RedisClient.scala | 196 | `fromJson[GpsPoint].toOption` | ✅ Заменено на flatMap + match + ZIO.logWarning |
| GoSafeParser.scala | 90 | `parseOnePacket(...).toOption` | ⚠️ Оставлено — multi-packet парсинг, частичные ошибки ожидаемы |

**Итог:** 3 из 4 мест исправлены. Невалидные данные теперь логируются через ZIO.logWarning.

---

### 1.7 `asInstanceOf` — unsafe casts

**Серьёзность: 🟢 НИЗКАЯ**

| Файл | Строка | Контекст |
|---|---|---|
| ConnectionHandler.scala | 755 | `remoteAddress().asInstanceOf[InetSocketAddress]` — Netty гарантирует тип |
| TcpServer.scala | 42, 50, 59, 62, 69, 72, 190 | Reflection для Epoll/KQueue — **необходимо** для native transport detection |

**Вердикт:** Все asInstanceOf в контексте Java interop / Netty — **допустимо**.

---

### 1.8 `.orDie` — крах приложения

**Серьёзность: 🟢 НИЗКАЯ**

| Файл | Строка | Контекст |
|---|---|---|
| RedisClient.scala | 307, 316, 325 | shutdown/close в ZIO.acquireRelease finalizer |
| KafkaProducer.scala | 165, 186 | close в ZIO.acquireRelease finalizer |

**Вердикт:** `.orDie` в finalizer — **стандартный ZIO паттерн**. Ресурс должен быть освобождён, если close() падает — это дефект.

---

### 1.9 `getOrThrow` — unsafe extraction

**Серьёзность: 🟢 НИЗКАЯ**

| Файл | Строка | Контекст |
|---|---|---|
| ConnectionHandler.scala | 692 | `runtime.unsafe.run(Ref.make(...)).getOrThrow()` — инициализация per-connection state |
| ConnectionHandler.scala | 698 | `runtime.unsafe.run(Semaphore.make(1)).getOrThrow()` — инициализация |
| TcpServer.scala | 207 | `runtime.unsafe.run(effect).getOrThrowFiberFailure()` — RateLimitHandler |

**Вердикт:** `getOrThrow` при инициализации Netty handler — **допустимо** (Netty callback → ZIO bridge). Ref.make и Semaphore.make не могут реально упасть.

---

### 1.10 `runtime.unsafe` — unsafeExecute

**Серьёзность: 🟢 НИЗКАЯ — все допустимые**

| Файл | Строка | Контекст |
|---|---|---|
| ConnectionHandler.scala | 692, 698 | Инициализация per-connection ZIO Ref/Semaphore |
| ConnectionHandler.scala | 997 | `runtime.unsafe.fork(effect)` — Netty → ZIO bridge |
| TcpServer.scala | 207 | RateLimitHandler — Netty → ZIO bridge |
| RedisClient.scala | 227, 240 | Lettuce Pub/Sub callback → ZIO bridge |

**Вердикт:** Все 6 мест — **мосты Java framework → ZIO**. Неизбежны в Netty/Lettuce интеграции.

---

## 2. Проблемы производительности

### 2.1 `throw` на hot-path — стектрейсы GPS-парсинга

**Серьёзность: 🟡 СРЕДНЯЯ | Влияние: ~5-15% overhead на error-path**

При ~20K пакетов/сек каждый невалидный пакет создаёт full stack trace через `throw new RuntimeException(...)`. Для ожидаемых ошибок (невалидный CRC, недостаток данных) это дорого.

**Рекомендация:** `ZIO.fail(ProtocolError.ParseFailed("msg"))` или `Either.Left` — zero-overhead для ожидаемых ошибок.

### 2.2 GalileoskyParser — while + var + null

GalileoskyParser.scala:103-130 — while-loop с `var imei: String = null` — императивный стиль с null. Функционально это `Iterator.find()`.

### 2.3 `.ignore` на Kafka publish без retry

ConnectionHandler.scala:332, 387 — если Kafka временно недоступен, GPS-точки теряются без повторной попытки. Учитывая SLA "GPS points без потерь" — это расхождение с целями.

---

## 3. Race conditions и утечки ресурсов

### 3.1 Per-connection `@volatile var` в парсерах

**Серьёзность: 🟢 НИЗКАЯ — нет реального race condition**

`@volatile var` в AdmParser, MicroMayakParser, ArnaviParser и т.д. — каждый парсер экземпляр принадлежит одному TCP-соединению, обрабатывается одним Netty Event Loop thread. Race condition невозможен при текущей архитектуре.

**Примечание:** если парсер когда-либо станет shared (пул, переиспользование) — race condition возникнет.

### 3.2 `ctx.close()` без awaiting ChannelFuture

ConnectionHandler.scala:821, 832, 986 — `ctx.close()` возвращает `ChannelFuture`, но результат не проверяется. Потенциально close может не выполниться.

**Серьёзность: 🟢 НИЗКАЯ** — Netty close() в worst case закрывает при GC.

### 3.3 Semaphore в ConnectionHandler

ConnectionHandler.scala:698 — `Semaphore.make(1)` для per-connection сериализации. Если fiber зависает внутри semaphore.withPermit — все последующие пакеты этого соединения блокируются. При timeout — не проблема, но стоит проверить наличие timeout.

---

## 4. Архитектурные замечания

### 4.1 Multi-tenant isolation: organizationId = 0L по умолчанию

ConnectionHandler.scala:311, 432 — `dd.map(_.organizationId).getOrElse(0L)`. Если DeviceData не найден, GPS точка публикуется с `organizationId = 0L`. Downstream-сервисы (HistoryWriter, RuleChecker) должны фильтровать org=0. Потенциальный data leak если не фильтруют.

### 4.2 Дублирование error hierarchies

- `domain/ParseError.scala` — sealed trait ParseError
- `domain/Protocol.scala` — sealed trait ProtocolError, FilterError, RedisError, KafkaError

ParseError кажется legacy. В коде парсеры используют `ProtocolError.ParseFailed`. Нужна одна иерархия.

### 4.3 ProtocolError extends Throwable

`Protocol.scala:37` — все 4 трейта extends Throwable. Это позволяет случайно `throw ProtocolError.ParseFailed(...)` вместо `ZIO.fail(...)`. Лучше не наследовать Throwable и использовать typed errors чисто через ZIO.

### 4.4 CmMetrics — глобальный мутабельный синглтон

`service/CmMetrics.scala` — `object CmMetrics` с `AtomicLong`, `LongAdder`. Не проходит через ZIO Layer. Intentional для performance, но ломает тестируемость (нельзя мокнуть).

---

## 5. Что сделано ХОРОШО ✅

1. **ZIO Layer DI** — Main.scala корректно собирает все зависимости через ZLayer
2. **Ref для state** — RateLimiter, CommandService, DynamicConfigService используют ZIO.Ref
3. **For-comprehension с комментариями** — ConnectionHandler, CommandService хорошо документированы
4. **Typed errors** — ProtocolError, FilterError, RedisError, KafkaError — 4 иерархии
5. **ConcurrentHashMap для hot-path** — ConnectionRegistry осознанно использует lock-free Java structures
6. **Option вместо null** — GpsPoint.fromRedisHash использует `hash.get().flatMap()` повсеместно
7. **Нет println** — 0 найдено, используется ZIO.logInfo/logError/logDebug
8. **Нет Thread.sleep/synchronized** — 0 найдено
9. **ZIO.fromFuture для Java interop** — RedisClient корректно конвертирует CompletionStage
10. **Graceful shutdown** — RedisClient и KafkaProducer используют ZIO.acquireRelease

---

## 6. Суммарная таблица

| Проверка | Статус | Комментарий |
|---|---|---|
| ❌ `var` строго val | 🟡 40 мест | CRC — OK, парсеры — нарушение |
| ❌ `throw` запрещён | 🔴 ~100 мест | Все парсеры и 3 encoder-а |
| ✅ `.get` на Option | 🟢 0 нарушений | Используют `.get` только на Map (безопасно) и ZIO Ref |
| ❌ `null` | 🟡 1 нарушение | GalileoskyParser `var imei: String = null` |
| ✅ `runtime.unsafe` | 🟢 6 мест допустимых | Все — Netty/Lettuce bridge |
| ✅ blocking ops | 🟢 0 нарушений | Нет Thread.sleep/synchronized |
| ✅ `println` | 🟢 0 нарушений | Везде ZIO Logging |
| ❌ `.ignore` проглотка | 🟡 2 критичных | Kafka GPS publish без логирования |
| ❌ opaque types | 🔴 0 | Нет opaque types вообще |
| ✅ `.toOption` тихо | ✅ 3 из 4 исправлены | CommandHandler, RedisClient — match + logWarning |
| ✅ ZIO Layer DI | 🟢 | Main корректно собирает слои |
| ✅ For-comprehension стиль | 🟢 | С комментариями шагов |
| 🟡 Multi-tenant isolation | 🟡 | organizationId defaults to 0L |

---

## 7. Рекомендации (приоритет)

### 🔴 Высокий приоритет

1. **Добавить логирование к `.ignore` на Kafka publish** (ConnectionHandler:332, 387) — GPS точки могут теряться без следов. Минимум: `.tapError(e => ZIO.logWarning(...)).ignore`

2. **~~Добавить логирование к `.toOption` в CommandHandler:181~~** — ✅ ИСПРАВЛЕНО v6.0

### 🟡 Средний приоритет

3. **Ввести opaque types** для Imei, VehicleId, OrganizationId — предотвратит путаницу типов на уровне компиляции

4. **Защитить organizationId = 0L** — не публиковать GPS точки с org=0 в Kafka (или логировать как аномалию)

5. **Унифицировать error hierarchy** — убрать legacy ParseError, оставить только ProtocolError/FilterError/RedisError/KafkaError

6. **Убрать `extends Throwable`** с typed errors — предотвратит случайный throw

### 🟢 Низкий приоритет (рефакторинг)

7. **Заменить throw → ZIO.fail в парсерах** — большой объём работы (~100 мест), но улучшит производительность error-path и ФП-чистоту

8. **Заменить `var imei: String = null`** в GalileoskyParser на Option

9. **Заменить `@volatile var`** в парсерах на Ref или функциональный state-passing (низкий приоритет — нет реального race condition)

10. **Вынести CmMetrics** в ZIO Layer (или trait + live) для тестируемости

---

*Создан: 10 марта 2026 | Обновлён: 11 марта 2026 | AI FP Audit*
