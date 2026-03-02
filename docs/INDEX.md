# Connection Manager — Документация v4.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `4.0`

## Содержание

| # | Документ | Описание |
|---|---|---|
| 1 | [README.md](README.md) | Обзор, быстрый старт, 18 протоколов, порты, зависимости |
| 2 | [ARCHITECTURE.md](ARCHITECTURE.md) | Внутренняя архитектура, 10+ Mermaid-диаграмм, потоки данных, command system |
| 3 | [API.md](API.md) | HTTP API (20+ endpoints), примеры запросов |
| 4 | [PROTOCOLS.md](PROTOCOLS.md) | Все 18 GPS-протоколов: форматы, парсинг, magic bytes, матрица команд |
| 5 | [DATA_MODEL.md](DATA_MODEL.md) | Redis ключи, in-memory кэш, 10 Kafka-сообщений (JSON), доменные модели |
| 6 | [KAFKA.md](KAFKA.md) | 10 produce + 2 consume топиков, маршрутизация, gps-parse-errors |
| 7 | [DECISIONS.md](DECISIONS.md) | 11 ADR: Redis→InMemory, parser-as-param, 18 протоколов, command encoders, all-points, parse-errors |
| 8 | [RUNBOOK.md](RUNBOOK.md) | Запуск, диагностика, типичные проблемы, мониторинг |
| 9 | [TESTING.md](TESTING.md) | Тестирование: 286+ тестов, протоколы, команды, API, фильтры |

## Что нового в v4.0

- **18 протоколов** (было 10) — добавлены Galileosky, Concox, TK102, Arnavi, ADM, GTLT, МикроМаяк
- **Command system** — 10 типов команд, 5 энкодеров (Teltonika, NavTelecom, DTM, Ruptela, Wialon)
- **gps-parse-errors** — новый Kafka-топик для мониторинга ошибок парсинга
- **ВСЕ точки → gps-events** — включая стоянки и отфильтрованные (isValid/isMoving маркеры)
- **ADR-008..011** — новые решения по command encoder, all-points, parse-errors, AwaitingCommand

## Быстрые ссылки

- **Запустить CM:** [RUNBOOK.md#быстрый-запуск](RUNBOOK.md#быстрый-запуск)
- **Проверить здоровье:** `curl http://localhost:10090/api/health`
- **TCP порты:** [README.md#tcp-порты-протоколов](README.md#tcp-порты-протоколов)
- **Добавить парсер:** [PROTOCOLS.md](PROTOCOLS.md)
- **Добавить команду:** [ARCHITECTURE.md#архитектура-команд-v40](ARCHITECTURE.md#архитектура-команд-v40)
- **Изменить фильтры:** [RUNBOOK.md#изменить-фильтры-на-лету](RUNBOOK.md#изменить-фильтры-на-лету)

## Внешние ссылки

- **Общая архитектура:** [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
- **Block 1:** [docs/ARCHITECTURE_BLOCK1.md](../../../docs/ARCHITECTURE_BLOCK1.md)
- **Kafka топики:** [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md)
- **Redis ключи:** [infra/redis/](../../../infra/redis/)
