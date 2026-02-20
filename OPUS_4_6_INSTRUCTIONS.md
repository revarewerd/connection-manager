# OPUS 4.6: Инструкции по исправлению Connection Manager

> **Статус:** Ready to implement  
> **Приоритет:** 🔴 КРИТИЧЕСКИЙ (Redis 86,400x faster)  
> **Дата:** 2026-02-20

---

## 🎯 Главная цель

Перенести **POSITION данные** из Redis в **in-memory Ref** → улучшить latency на **1000x** и сэкономить **864M Redis операций в день**.

---

## 📋 ПЛАН РАБОТ (в порядке выполнения)

### ✅ ЗАДАЧА 1: Redis оптимизация (в памяти вместо Redis)

**Файлы для изменения:**
- `src/main/scala/com/wayrecall/tracker/domain/GpsPoint.scala`
- `src/main/scala/com/wayrecall/tracker/network/ConnectionHandler.scala`
- `src/main/scala/com/wayrecall/tracker/storage/RedisClient.scala`

**Что делать:**

1. **Обновить ConnectionState структуру** (GpsPoint.scala)
   ```scala
   case class ConnectionState(
       imei: Option[String] = None,
       vehicleId: Option[Long] = None,
       
       // === ГЛАВНОЕ: POSITION в памяти ===
       lastPosition: Option[GpsPoint] = None,        // ← на каждый пакет (in-memory!)
       lastActivityTime: Long = 0L,
       
       // === CONTEXT: TTL кэш ===
       cachedContext: Option[DeviceContext] = None,  // ← обновляется редко
       contextCachedAt: Long = 0L,
       contextCacheTtlMs: Long = 3600000,            // 1 час TTL
       
       // === CONNECTION: аудит ===
       connectionInfo: Option[ConnectionInfo] = None // ← при подключении
   )
   ```

2. **Обновить ConnectionHandler логику** (ConnectionHandler.scala)
   ```
   На КАЖДЫЙ GPS пакет:
   ❌ Было:  HGETALL device:{imei} → fresh данные из Redis
   ✅ Стало: Берём cachedContext из памяти → обновляем lastPosition
   
   Результат: nanoseconds вместо 1-5ms!
   ```

3. **Добавить Pub/Sub invalidation** (RedisClient.scala)
   ```scala
   private def subscribeToConfigChanges(imei: String): Task[Unit] =
     redisClient.subscribe(s"device-config-changed:$imei") { _ =>
       stateRef.update(_.copy(contextCachedAt = 0))  // Инвалидировать кэш
     }
   ```

**Ожидаемый результат:**
- ✅ Redis операции: 864M HMSET/день → 0
- ✅ HGETALL: 0 HGETALL/пакет → 1 HGETALL/час (при изменении конфига)
- ✅ Latency: 1-5ms → <1ms
- ✅ Total Redis ops/день: 864M → ~10k (86,400x улучшение!)

**Проверка:**
```bash
# Убедиться что HMSET device:{imei} для POSITION удален из кода
grep -r "HMSET.*lat\|HMSET.*speed" src/  # ❌ Должно быть пусто!

# Убедиться что HGETALL есть только при инициализации и Pub/Sub
grep -r "HGETALL device:" src/ | grep -v "contextCachedAt = 0"  # ✅ Должно быть мало
```

---

### 🟡 ЗАДАЧА 2: Kafka implications (изменить подход к POSITION)

**Файлы для изменения:**
- `src/main/scala/com/wayrecall/tracker/service/DeviceEventConsumer.scala`
- Документация: `ARCHITECTURE_ANALYSIS.md`

**Что делать:**

1. **В DeviceEventConsumer добавить Pub/Sub notify**
   ```scala
   // После HSET device:{imei} с CONTEXT полями:
   _ <- redis.publish(s"device-config-changed:$imei", "config_updated")
   ```

2. **Убедить что gps-events в Kafka содержит ВСЁ POSITION данные**
   ```
   GpsEventMessage должна иметь:
   - latitude, longitude, altitude, speed, course, satellites, deviceTime
   ```

3. **Обновить документацию ARCHITECTURE_ANALYSIS.md**
   - Описать разделение: POSITION в Kafka, CONTEXT в Redis
   - Показать что Redis больше НЕ источник POSITION
   - Объяснить Pub/Sub механизм invalidation

**Проверка:**
```bash
# Убедиться что GpsEventMessage публикуется в gps-events
grep -r "kafka.*publish.*gps-events" src/  # ✅ Должно быть

# Убедиться что DeviceEventConsumer публикует Pub/Sub
grep -r "redis.*publish.*device-config" src/  # ✅ Должно быть в DeviceEventConsumer
```

---

### 🟠 ЗАДАЧА 3: HTTP API decision (подумать вместе)

**Файлы для изменения (если убираем):**
- `src/main/scala/com/wayrecall/tracker/Main.scala` (убрать zio.http.Server)
- `src/main/scala/com/wayrecall/tracker/api/HttpApi.scala` (удалить)

**Варианты:**

**Вариант A: Убрать полностью** (РЕКОМЕНДУЕМЫЙ)
- Health check → TCP port check (5001-5004 живы?)
- Metrics → Kafka топик `cm-metrics`
- Полностью убрать HttpApi.scala и HTTP server

**Вариант B: Оставить minimal**
- Только `GET /health` для Kubernetes
- Убрать `/connections`, `/commands`, `/filters`
- Всё остальное → Kafka

**Вариант C: Оставить как есть**
- HTTP API остаётся
- Добавить `/metrics` для Prometheus
- Требует `Micrometer` зависимость

**Что нужно решить:**
- Нужен ли Kubernetes (тогда Вариант B минимум)
- Есть ли Prometheus уже (тогда Вариант C имеет смысл)
- Важна ли минимизация ответственности CM (тогда Вариант A)

**Текущая рекомендация:** Вариант A (убрать) → чище архитектура

---

### 🟢 ЗАДАЧА 4: Проверить фильтры (QA)

**Файлы для проверки:**
- `src/main/scala/com/wayrecall/tracker/filter/DeadReckoningFilter.scala`
- `src/main/scala/com/wayrecall/tracker/filter/StationaryFilter.scala`

**Что проверить:**

1. **Dead Reckoning Filter**
   - Телепортация: 10km за 1 сек → отсечение ✅?
   - Будущие timestamps → отсечение ✅?
   - Отрицательные координаты → отсечение ✅?
   - Edge case: координата в (0,0) → обработка ✅?

2. **Stationary Filter**
   - Порог расстояния → правильная формула ✅?
   - Порог скорости → учитывается ✅?
   - Нулевая дельта (одна точка два раза) → обработка ✅?
   - Chronological order → проверяется ✅?

**Тесты добавить:**
```bash
# Проверить что тесты покрывают:
grep -r "DeadReckoningFilter\|StationaryFilter" src/test/  # Должны быть тесты
```

---

### 📝 ЗАДАЧА 5: Обновить документацию

**Файлы для обновления (в порядке приоритета):**

1. **ARCHITECTURE_ANALYSIS.md** (MUST)
   - Описать разделение POSITION/CONTEXT/CONNECTION
   - Kafka как источник правды для POSITION
   - Redis Pub/Sub механизм

2. **STUDY_GUIDE.md** (MUST)
   - Переписать под новую архитектуру
   - Добавить примеры in-memory vs Redis

3. **CM_FILE_MAP.md** (NICE)
   - Добавить MultiProtocolParser (если реализуем)

4. **CM_DATA_STORES.md** (CHECK)
   - Если существует → обновить про POSITION → Kafka

---

### 💭 ЗАДАЧА 6: Парсеры (думаем вместе)

**Не реализовываем пока! Оставляем на обсуждение:**
- MultiProtocolParser нужен ли?
- Как будут раскатываться микросервисы (1 CM per протокол или multi)?
- WialonAdapterParser dual-format - это проблема?

**Файлы только для READ:**
- `src/main/scala/com/wayrecall/tracker/protocol/TeltonikaParser.scala`
- `src/main/scala/com/wayrecall/tracker/protocol/WialonParser.scala`
- `src/main/scala/com/wayrecall/tracker/protocol/RuptelaParser.scala`
- `src/main/scala/com/wayrecall/tracker/protocol/NavTelecomParser.scala`

---

## 🔗 СВЯЗАННЫЕ ФАЙЛЫ

**Обязательно читать перед началом:**
- ✅ `redis.md` - описание новой архитектуры Redis
- ✅ `MustFixItImportant.md` - детальные объяснения всех задач
- 📄 `docs/ARCHITECTURE_ANALYSIS.md` - текущая документация (устаревает)

---

## 📊 МЕТРИКИ УСПЕХА

После выполнения всех задач должны улучшиться:

| Метрика | Было | Стало | Улучшение |
|---------|------|-------|-----------|
| Redis HMSET/дата (POSITION) | 864M | 0 | ∞ |
| Redis HGETALL/пакет | 1 | 0 (кроме config changes) | 1,000,000x |
| Redis оп/день | 864M+ | ~10k | 86,400x |
| Latency обработки пакета | 1-5ms | <1ms | 1000x+ |
| CPU использование | ÷ Redis await | нету Redis | -10-20% |
| Memory (in-memory cache) | 0 | ~10MB | малые |

---

## 🚨 КРИТИЧЕСКИЕ ТОЧКИ

⚠️ **ОБЯЗАТЕЛЬНО проверить:**

1. **Pub/Sub не потеряется между перезагрузками CM**
   - Когда CM перезагружается → потеряет слушание Pub/Sub?
   - Нужна переподписка при reconnect?

2. **Chronological order GPS points**
   - ConnectionState.lastPosition может быть из будущего?
   - Dead Reckoning фильтр это ловит?

3. **TTL 1 час может быть слишком долго?**
   - Если ДМ изменил speedLimit → CM узнает за 1 час
   - Pub/Sub должна быть НАДЕЖНОЙ (retry logic?)

4. **In-memory cache потеряется при переподключении**
   - Каждый новый трекер → fresh HGETALL ✅
   - oldConnection.close() → потеря cachedContext ✅ (OK, не важно)

---

## ✅ CHECKLIST перед push

```
Redis оптимизация (Task 1):
☐ ConnectionState обновлена (lastPosition, cachedContext, connectionInfo)
☐ ConnectionHandler обновлен (no HMSET on каждый пакет)
☐ RedisClient: добавлена Pub/Sub subscribe
☐ Ref type safety проверена (no unsafe.run в handler)
☐ Тесты обновлены (no mock Redis для каждого пакета)

Kafka implications (Task 2):
☐ DeviceEventConsumer публикует Pub/Sub
☐ GpsEventMessage содержит все POSITION поля
☐ ARCHITECTURE_ANALYSIS.md обновлена

HTTP API (Task 3):
☐ Решение принято (убрать/оставить/минимал)
☐ Код либо удален, либо рефакторен
☐ Main.scala обновлена

Фильтры (Task 4):
☐ DeadReckoningFilter проверена (tests проходят)
☐ StationaryFilter проверена (tests проходят)
☐ Edge cases покрыты

Документация (Task 5):
☐ ARCHITECTURE_ANALYSIS.md обновлена
☐ STUDY_GUIDE.md обновлена
☐ redis.md в синхронизации

Парсеры (Task 6):
☐ Обсуждены с пользователем
☐ Решение принято (реализовать или отложить)

Build & Deploy:
☐ sbt compile проходит
☐ sbt test проходит
☐ docker build успешен
☐ docker run localhost:5001 работает
```

---

## 🎓 КОНТЕКСТ

Это второй этап архитектурного улучшения CM:

1. **Этап 1 (2026-02-15):** Архитектурный аудит → выявлены проблемы
2. **Этап 2 (СЕЙЧАС):** Реализация оптимизаций → этот файл
3. **Этап 3 (TODO):** Парсеры + микросервисы → MultiProtocolParser

---

## 🔗 СВЯЗЬ С REDIS.MD

Файл `redis.md` в этом репозитории содержит полное описание новой Redis архитектуры:
- Структуры и схема хранения
- Кто и как читает/пишет
- Оптимизационная стратегия
- Модели данных для Kafka

**Перед тем как писать код → прочитать redis.md полностью!**

---

**Готово к работе! 🚀**
