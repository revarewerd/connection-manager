# Сравнение масштабируемости: Legacy STELS vs CM v6.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-11` | Версия: `3.0`

## Историческая справка

Этот документ сравнивает три этапа эволюции Connection Manager:
1. **Legacy STELS** — Java/Netty 3, thread pool модель
2. **CM v4.0** (до рефакторинга) — Scala/ZIO, но с `runtime.unsafe.run()` ← **проблема решена**
3. **CM v5.0** — Scala/ZIO, полностью асинхронный, оптимизирован для 100K+
4. **CM v6.0** (текущий) — CmMetrics, ФП-аудит фиксы, 560 тестов

---

## Архитектурное сравнение

### Legacy STELS

```
┌─────────────────────────────────────────────────────┐
│                Netty (Netty 3/4)                    │
│               TCP Pipeline Handlers                 │
│                     ↓                               │
│          ChannelRead0() callback                    │
│                     ↓                               │
│        ┌────────────────────────┐                  │
│        │  PackProcessor.process()  │←─ Java вызов  │
│        │   (synchronous method)    │                │
│        └────────────────────────┘                  │
│                     ↓                               │
│        ┌────────────────────────┐                  │
│        │  ExecutionContext.global │ ← thread pool!  │
│        │  (200 потоков)          │                  │
│        └────────────────────────┘                  │
│                     ↓                               │
│    Redis / Kafka / БД операции                     │
│    (в отдельном потоке, не Netty!)                 │
└─────────────────────────────────────────────────────┘
```

- ✅ Netty I/O поток не блокируется
- ❌ thread pool = GC давление, context switching, 200+ потоков

### CM v5.0 (текущий — после рефакторинга)

```
┌─────────────────────────────────────────────────────┐
│           Netty 4.1 (Epoll/KQueue)                  │
│         ChannelInboundHandler override              │
│                     ↓                               │
│         channelRead() → forkEffect()                │
│                     ↓                               │
│   ┌───────────────────────────────────┐            │
│   │   runtime.unsafe.fork(effect)     │ ← return   │
│   │   + Semaphore(1) per connection   │   СРАЗУ! ✅│
│   └───────────────────────────────────┘            │
│                     ↓                               │
│    ZIO fiber (background, не Netty I/O):           │
│    ├─ parse → filter → serialize (1× JSON cache)   │
│    ├─ Kafka publish ×3 (zipPar: rules + retrans)   │
│    └─ Redis HSET (async)                           │
└─────────────────────────────────────────────────────┘
```

- ✅ `runtime.unsafe.fork()` — Netty I/O поток **не блокируется**
- ✅ `Semaphore(1)` — гарантирует порядок обработки per connection
- ✅ Epoll/KQueue — O(1) per event, native transport
- ✅ ConcurrentHashMap — 0 аллокаций на hot path

---

## Таблица сравнения

| Аспект | Legacy STELS | CM v4.0 (до рефакторинга) | CM v5.0 (текущий) |
|---|---|---|---|
| **Язык** | Java 8 | Scala 3.4 + ZIO 2 | Scala 3.4 + ZIO 2 |
| **Netty I/O блокировка** | ❌ Нет (thread pool) | ✅ **ДА** (`unsafe.run()`) | ❌ Нет (`unsafe.fork()`) |
| **Event loop** | NIO (O(n)) | NIO (O(n)) | Epoll/KQueue (O(1)) |
| **Реестр соединений** | `ConcurrentHashMap` | `ZIO Ref[Map]` (копии) | `ConcurrentHashMap` + `AtomicLong` |
| **Kafka publish** | Последовательный | Последовательный | `zipPar` (параллельный) |
| **JSON сериализация** | — | 3× per point | 1× per point (кэш) |
| **Clock вызовы** | `System.currentTimeMillis()` | `Clock.currentTime(MILLIS)` (аллокация) | `java.lang.System.currentTimeMillis()` |
| **Disconnect cleanup** | Последовательный | Последовательный | Параллельный (×32) |
| **Масштаб (tcp conn)** | ~2K–5K | ~10K–20K (с проблемами) | **100K+** |

---

## Критическая проблема v4.0 и как она решена

### v4.0 — runtime.unsafe.run() (ИСПРАВЛЕНО)

```
Было:  channelRead() → runtime.unsafe.run(effect) → БЛОКИРУЕТ I/O → 5-20ms
Стало: channelRead() → runtime.unsafe.fork(effect) → return СРАЗУ → 0ms
```

**Проблема:** `runtime.unsafe.run()` синхронно ждал результата ZIO эффекта прямо в Netty I/O потоке.
При 8 I/O потоках и 20K соединениях → thread starvation, TCP buffer overflow, reconnect storm.

**Решение (v5.0):**
- `runtime.unsafe.fork()` — запускает обработку в ZIO fiber, Netty callback возвращается СРАЗУ
- `Semaphore(1)` per connection — гарантирует порядок пакетов (один IMEI = строгий порядок)
- Netty I/O поток свободен для приёма следующего пакета

---

## Производительность при 100K соединениях

| Сценарий | Legacy STELS | CM v5.0 |
|---|---|---|
| channelRead() latency | <1ms (делегирует) | <1ms (fork) |
| Обработка (Redis+Kafka) | 5-20ms (worker thread) | 5-20ms (ZIO fiber) |
| Netty I/O thread | Свободен | Свободен |
| Event loop model | O(n) NIO | O(1) Epoll/KQueue |
| Аллокации per packet | Средние (Java GC) | Минимальные (lock-free ConcurrentHashMap) |
| Kafka latency per point | ~15ms (3× serial) | ~10ms (1× base + zipPar rules/retrans) |
| **Масштаб** | **~5K** | **100K+** |

---

## Оставшиеся оптимизации

Запланированы на следующий спринт:
- **M-3:** Redis connection pool (Lettuce → пуловые подключения)
- **M-5:** Prometheus метрики (buffer usage, Kafka latency p99)
- **L-3:** Конфигурируемый `poolSize` для EventLoop
- **L-6:** Auto-read backpressure (Netty write water mark)

См. [PERFORMANCE_AUDIT_100K.md](PERFORMANCE_AUDIT_100K.md) — полный аудит 19 находок.

---

## Вывод

CM v5.0 **превосходит Legacy STELS** по всем ключевым метрикам:
- ✅ Netty I/O поток **никогда не блокируется** (как в Legacy, но лучше — ZIO fibers вместо thread pool)
- ✅ **Epoll/KQueue** — нативный transport, O(1) вместо O(n) NIO
- ✅ **Lock-free** регистрация — ConcurrentHashMap + AtomicLong
- ✅ **Параллельный** Kafka publish — zipPar для rules + retranslation
- ✅ **JSON кэш** — сериализация 1× вместо 3×
- ✅ Целевая масштабируемость: **100K+ TCP соединений** на одном инстансе

---

*Версия: 2.0 | Обновлён: 10 марта 2026*
