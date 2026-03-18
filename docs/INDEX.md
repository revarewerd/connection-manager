# Connection Manager — Документация v6.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-11` | Версия: `6.0`

## Содержание

| # | Документ | Описание |
|---|---|---|
| 1 | [README.md](README.md) | Обзор, быстрый старт, 18 протоколов, порты, оптимизации v5.0→v6.0 |
| 2 | [ARCHITECTURE.md](ARCHITECTURE.md) | Внутренняя архитектура, 10+ Mermaid-диаграмм, CmMetrics, command system |
| 3 | [API.md](API.md) | HTTP API (20+ endpoints), примеры запросов |
| 4 | [PROTOCOLS.md](PROTOCOLS.md) | Все 18 GPS-протоколов: форматы, парсинг, magic bytes, матрица команд |
| 5 | [DATA_MODEL.md](DATA_MODEL.md) | Redis ключи, in-memory кэш, 10 Kafka-сообщений (JSON), доменные модели |
| 6 | [KAFKA.md](KAFKA.md) | 10 produce + 2 consume топиков, маршрутизация, gps-parse-errors |
| 7 | [DECISIONS.md](DECISIONS.md) | 16 ADR: все решения вкл. CmMetrics (ADR-014), Redis single conn (ADR-015), FP аудит (ADR-016) |
| 8 | [RUNBOOK.md](RUNBOOK.md) | Запуск, диагностика, производительность, типичные проблемы |
| 9 | [TESTING.md](TESTING.md) | **560 тестов**, протоколы, команды, API, фильтры, CmMetrics |
| 10 | [LEARNING.md](LEARNING.md) | Подробный гайд по изучению сервиса, все компоненты |
| 11 | [PERFORMANCE_AUDIT_100K.md](PERFORMANCE_AUDIT_100K.md) | Аудит производительности: 19 находок, **все 19 закрыты** |
| 12 | [FP_AUDIT_REPORT.md](FP_AUDIT_REPORT.md) | ФП-аудит: балл 7.0/10, нарушения, рекомендации, исправленные пункты |
| 13 | [SCALABILITY_ISSUES.md](SCALABILITY_ISSUES.md) | История масштабирования 20K → 100K |
| 14 | [LEGACY_VS_NEW_COMPARISON.md](LEGACY_VS_NEW_COMPARISON.md) | Сравнение Legacy STELS vs CM v6.0 |
| 15 | [CODE_AUDIT_REPORT.md](CODE_AUDIT_REPORT.md) | Предыдущий аудит кода |
| 16 | [LEARNING_PATH_JUNIOR.md](LEARNING_PATH_JUNIOR.md) | Интенсивный пошаговый план обучения (junior): порядок чтения кода + внешние статьи |

## Что нового в v6.0

- **CmMetrics** — 16 метрик (LongAdder + AtomicLong), Prometheus text exposition через `/api/metrics`
- **ФП-аудит** — оценка 7.0/10, ключевые фиксы: .toOption → match + logWarning, .toDoubleOption safety
- **560 тестов** — полный regression, 0 failures (CmMetricsSpec + parser safety + обновлённые старые тесты)
- **19/19 аудит** — все пункты производительности закрыты (15 исправлено, 4 проанализировано)
- **ADR-014, ADR-015, ADR-016** — 3 новых решения в DECISIONS.md
- **Clock.currentTime** в getIdleConnections — совместимость с TestClock

## Что нового в v5.0

- **100K+ TCP соединений** — оптимизация всех компонентов (15 из 19 находок аудита)
- **fork() + Semaphore** — Netty I/O thread никогда не блокируется (ADR-012)
- **Epoll/KQueue** — native transport, O(1) per event (ADR-013)
- **ConcurrentHashMap** — lock-free реестр соединений (ADR-013)
- **JSON cache** — 1× сериализация вместо 3× per GPS point (ADR-013)
- **ADR-012, ADR-013** — новые решения

## Быстрые ссылки

- **Запустить CM:** [RUNBOOK.md#быстрый-запуск](RUNBOOK.md#быстрый-запуск)
- **Проверить здоровье:** `curl http://localhost:10090/api/health`
- **TCP порты:** [README.md#tcp-порты-протоколов](README.md#tcp-порты-протоколов)
- **Добавить парсер:** [PROTOCOLS.md](PROTOCOLS.md)
- **Добавить команду:** [ARCHITECTURE.md#архитектура-команд-v40](ARCHITECTURE.md#архитектура-команд-v40)
- **Оптимизации 100K:** [PERFORMANCE_AUDIT_100K.md](PERFORMANCE_AUDIT_100K.md)
- **Изучить сервис:** [LEARNING.md](LEARNING.md)

## Внешние ссылки

- **Общая архитектура:** [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
- **Block 1:** [docs/ARCHITECTURE_BLOCK1.md](../../../docs/ARCHITECTURE_BLOCK1.md)
- **Kafka топики:** [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md)
- **Redis ключи:** [infra/redis/](../../../infra/redis/)
