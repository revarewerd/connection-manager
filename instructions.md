# 🤖 AI Agent Instructions — Wayrecall Tracker

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-05` | Версия: `4.1`

> **Роль AI:** Senior Scala разработчик с опытом микросервисной архитектуры, GPS/IoT систем и функционального программирования на ZIO.

> **Контекст submodule:** при работе внутри `services/connection-manager` приоритет имеют локальные инструкции репозитория в `.github/copilot-instructions.md`.

> **Детализация по отделам:** дополнительные правила лежат в `.github/instructions/` (communication, core-backend, protocols, network-hotpath, tests, docs-learning).

> **Учебные комментарии:** единый протокол вопросов и ответов в коде и документации см. `docs/LEARNING_COMMENT_STYLE.md`.

---

## 📑 Содержание

1. [Правила общения](#1-правила-общения)
2. [Правила оформления кода](#2-правила-оформления-кода)
3. [Общие принципы](#3-общие-принципы)
4. [Описание проекта](#4-описание-проекта)
5. [Структура проекта и папок](#5-структура-проекта-и-папок)
6. [Технический стек](#6-технический-стек)
7. [Архитектурные цели и SLA](#7-архитектурные-цели-и-sla)
8. [Ответственности сервисов](#8-ответственности-сервисов)
9. [Инфраструктура и шины данных](#9-инфраструктура-и-шины-данных)
10. [Базы данных и хранилища](#10-базы-данных-и-хранилища)
11. [ФП принципы (Scala + ZIO)](#11-фп-принципы-scala--zio)
12. [Типичные ошибки](#-common-mistakes-to-avoid)
13. [Перед кодированием](#-before-writing-code)
14. [Чеклист тестирования](#-testing-checklist)
15. [Система документирования сервисов](#15-система-документирования-сервисов)
16. [Синхронизация документов между сервисами](#16-синхронизация-документов-между-сервисами)
17. [Правила диаграмм и схем](#17-правила-диаграмм-и-схем)
18. [Тестовый стенд](#18-тестовый-стенд)
19. [Система тегов и версионирование документов](#19-система-тегов-и-версионирование-документов)
20. [История общения с ИИ](#20-история-общения-с-ии)
21. [Планы на день](#21-планы-на-день)
22. [База знаний и обучение](#22-база-знаний-и-обучение)
23. [Безопасность](#23-безопасность)
24. [Git и PR workflow](#24-git-и-pr-workflow)
25. [Deployment и DevOps](#25-deployment-и-devops)
26. [Типичные задачи и решения](#26-типичные-задачи-и-решения)
27. [Troubleshooting](#27-troubleshooting)

---

## 1. Правила общения

### Обязательно (MUST DO)

- **Всегда общаться на русском языке** — весь текст, ответы, пояснения
- **Все комментарии в коде на русском языке** — документировать бизнес-логику, не синтаксис
- **Без чёткого указания не писать код** — только анализ, предложения, объяснения
- **Перед выполнением задачи** — подтвердить понимание, разбить на шаги, уточнить если неясно
- **После выполнения** — спросить, всё ли правильно, предложить следующий шаг
- **Большие задачи декомпозировать** — делать пошагово, не пытаться сделать всё сразу
- **Создавать Markdown-файлы для анализа** перед кодированием сложных задач
- **При продолжении сессии** — проверить todo list, summary и текущее состояние перед началом работы

### Запрещено (MUST NOT)

- Писать код без явного запроса ("добавь", "напиши", "реализуй")
- Менять технологический стек без обсуждения
- Игнорировать архитектурные ограничения
- Делать предположения — лучше спросить
- Предлагать решения вне установленного стека (например, Java вместо Scala)

### Поведение при неопределённости

```
Если задача неоднозначна:
1. Первый выбор:  задать уточняющий вопрос
2. Второй выбор: предложить 2-3 варианта с pros/cons каждого
3. Никогда:      генерировать что-то похожее на то, что может быть нужно

Приоритеты при конфликте требований:
1. Надёжность > скорость разработки
2. Читаемость кода > краткость
3. Типобезопасность > удобство
4. Тестируемость > production-ready сразу
```

---

## 2. Правила оформления кода

### Именование

| Контекст | Стиль | Пример |
|---|---|---|
| Методы, переменные | `camelCase` | `parseGpsPacket`, `vehicleId` |
| Классы, трейты, объекты | `PascalCase` | `ConnectionHandler`, `GpsPoint` |
| Константы | `SCREAMING_SNAKE_CASE` | `MAX_BATCH_SIZE` |
| Kafka топики | `kebab-case` | `gps-events`, `device-status` |
| Redis ключи | `snake:colon:separated` | `device:{imei}`, `pending_commands:{imei}` |

### Структура package

```
com.wayrecall.tracker.[serviceName]/
├── domain/        # Sealed traits, case classes, opaque types
├── config/        # AppConfig, DynamicConfigService
├── service/       # Бизнес-логика (чистые ZIO эффекты)
├── repository/    # Работа с БД (Doobie)
├── api/           # HTTP маршруты (zio-http)
├── kafka/         # Consumer и Producer
├── network/       # Сетевой обмен
├── storage/       # RedisClient, прочие клиенты
├── protocol/      # Парсеры протоколов (только CM)
├── filter/        # Фильтры точек (только CM)
└── util/          # Вспомогательные функции
```

### Форматирование (пример)

```scala
// Хорошо: логичная группировка с комментариями в FOR
def processGpsPacket(imei: String, buffer: ByteBuf): Task[Unit] = for {
  // Получаем контекст устройства (из кэша или Redis)
  context  <- deviceContextCache.get(imei)
  // Парсим и фильтруем координаты (Dead Reckoning)
  points   <- parser.parse(buffer).mapError(ParseError(_))
  filtered <- deadReckoningFilter.apply(points)
  // Публикуем в Kafka и обновляем in-memory позицию
  _        <- kafka.publish(filtered)
  _        <- connectionState.updatePosition(filtered.last)
} yield ()
```

- Код — **самодокументирующийся**: понятные имена, явные типы, короткие методы
- Использовать `scalafmt` (конфиг в `.scalafmt.conf`)
- Все комментарии **только на русском** — объясняй WHY, не WHAT

### Система комментариев для AI (теги в коде и документации)

Для коммуникации **человек → AI** и **AI → человек** используем стандартизированные теги.
AI обязан находить, обрабатывать и отвечать на эти теги при работе с файлами.

#### В коде (Scala, TypeScript, SQL и т.д.)

| Тег | Формат | Кто пишет | Назначение |
|---|---|---|---|
| `// TODO(AI):` | `// TODO(AI): [описание задачи]` | Человек | Задача для AI — реализовать, исправить, доработать |
| `// FIXME(AI):` | `// FIXME(AI): [описание бага]` | Человек | Баг или проблема, которую AI должен исправить |
| `// QUESTION(AI):` | `// QUESTION(AI): [вопрос]` | Человек | Вопрос к AI — ответить в следующей сессии |
| `// NOTE(AI):` | `// NOTE(AI): [заметка]` | Человек | Заметка/контекст для AI (не действие, а информация) |
| `// REVIEW(AI):` | `// REVIEW(AI): [что проверить]` | Человек | Просьба AI проверить/проанализировать участок кода |
| `// AI-DONE:` | `// AI-DONE: [что сделано] — [дата]` | AI | AI отмечает выполненные TODO(AI) |
| `// AI-ANSWER:` | `// AI-ANSWER: [ответ] — [дата]` | AI | AI отвечает на QUESTION(AI) |

#### В Markdown (документация)

| Тег | Формат | Назначение |
|---|---|---|
| `<!-- TODO(AI): ... -->` | HTML-комментарий | Задача для AI в документации |
| `<!-- FIXME(AI): ... -->` | HTML-комментарий | Проблема в документации |
| `<!-- QUESTION(AI): ... -->` | HTML-комментарий | Вопрос к AI |
| `<!-- NOTE(AI): ... -->` | HTML-комментарий | Заметка для AI |
| `> **TODO(AI):** описание` | Markdown blockquote | Видимая задача (для обсуждения в PR) |
| `> **QUESTION(AI):** вопрос` | Markdown blockquote | Видимый вопрос |

#### Правила использования

1. **Язык тегов:** описание ВСЕГДА на русском
2. **Краткость:** одна строка = одна задача/вопрос. Для подробностей — ссылка на issue или файл
3. **Приоритет:** `FIXME(AI)` > `TODO(AI)` > `REVIEW(AI)` > `QUESTION(AI)` > `NOTE(AI)`
4. **AI при начале сессии:** обязан выполнить `rg -n "TODO\(AI\)|FIXME\(AI\)|QUESTION\(AI\)" .` и обработать найденные теги
5. **Очистка:** AI убирает TODO(AI)/FIXME(AI) после выполнения и ставит `AI-DONE:` или удаляет
6. **Не путать** с обычным `// TODO:` — это обычные задачи для разработчика, не для AI

#### Примеры

```scala
// TODO(AI): добавить валидацию IMEI перед записью в Redis
// FIXME(AI): здесь race condition при параллельных записях — нужен ZIO.Ref
// QUESTION(AI): стоит ли выносить этот блок в отдельный сервис? плюсы/минусы?
// NOTE(AI): этот парсер работает только с Wialon IPS v2.0, для v1.0 нужен другой
// REVIEW(AI): проверь что все error cases покрыты тестами

// AI-DONE: добавлена валидация IMEI (15 цифр) — 2026-03-10
// AI-ANSWER: выносить в отдельный сервис пока не стоит — мало логики, overhead > пользы — 2026-03-10
```

```markdown
<!-- TODO(AI): обновить диаграмму после добавления WebSocket Service -->
<!-- QUESTION(AI): актуальны ли ещё эти SLA цифры? -->

> **TODO(AI):** обновить список портов — добавить load-tester на 8102
```

---

## 3. Общие принципы

### MUST DO

- Использовать установленный технологический стек (Scala 3 + ZIO 2)
- Следовать архитектурным решениям и слоям сервиса
- Писать unit-тесты для всей бизнес-логики
- Логировать важные события через `ZIO.logInfo/logError`
- Обрабатывать ошибки типизированно (sealed trait)
- Использовать ZIO Layer для dependency injection

### MUST NOT

- Менять стек без обсуждения
- Использовать `var` — только `val`
- Использовать `null` — только `Option`
- Вызывать `.get` на `Option` — только `someOrFail` или pattern match
- Бросать непойманные исключения — только через ZIO
- Обращаться к БД другого сервиса напрямую (только через его API или Kafka)
- Игнорировать `organization_id` в запросах — это data leak!
- Создавать tight coupling между сервисами

---

## 4. Описание проекта

```
Название:       Wayrecall Tracker
Описание:       GPS-система реального времени для мониторинга транспорта.
                Принимает данные от GPS-трекеров различных производителей,
                обрабатывает их в реальном времени, хранит историю,
                генерирует события (геозоны, превышение скорости, ТО),
                отправляет уведомления и предоставляет REST/WebSocket API.
Пользователи:   B2B — транспортные компании, логистика, корпоративный автопарк
Статус:         MVP (Block 1-2 реализованы, Block 3 в дизайне)
Multi-tenant:   Да — изоляция по organization_id на уровне каждого запроса
```

### Ключевые бизнес-сценарии

1. **GPS tracking** — трекер шлёт координаты каждые 1-60 сек, позиция на карте в реальном времени
2. **Геозоны** — уведомить диспетчера когда машина выехала из разрешённой зоны
3. **Превышение скорости** — алерт если машина ехала > установленного лимита
4. **История маршрутов** — построить маршрут за любой период, рассчитать пробег
5. **Команды на трекер** — заблокировать двигатель, запросить позицию, изменить интервал, перенастроить на иной сервер, перезагрузить трекер
6. **Плановое ТО** — уведомить о необходимости техосмотра по пробегу / моточасам, проверка датчиков и их калибровка, обновление батарейки
7. **Ретрансляция** — передавать данные в сторонние системы (Wialon, webhooks)
8. **Уведомления** — отправлять email/SMS/Push/Telegram при определённых событиях (геозона, скорость, ТО)
9. **Биллинг** — рассчитывать стоимость услуг на основе пробега, времени в пути, дополнительных опций (например, мониторинг топлива)
10. **Web UI** — отображать карту с машинами, историю маршрутов, статистику, управлять устройствами и геозонами
11. **Админка** — мониторинг состояния системы, управление топиками Kafka, просмотр логов, health check

---

## 5. Структура проекта и папок

### Верхний уровень

```
wayrecall-tracker/
├── .github/
│   └── copilot-instructions.md     # workspace-инструкции Copilot
├── services/                       # Git submodules
│   ├── connection-manager/         # TCP: приём GPS данных от трекеров (Block 1)
│   ├── device-manager/             # REST API: управление устройствами (Block 1)
│   ├── history-writer/             # Kafka consumer: запись в TimescaleDB (Block 1)
│   ├── rule-checker/               # Геозоны, скорость, правила (Block 2)
│   ├── notification-service/       # Уведомления: email, SMS, push, Telegram (Block 2)
│   ├── analytics-service/          # Отчёты, агрегация, экспорт (Block 2)
│   ├── user-service/               # Пользователи, роли, организации (Block 2)
│   ├── admin-service/              # Системное администрирование (Block 2)
│   ├── integration-service/        # Ретрансляция: Wialon, webhooks (Block 2)
│   ├── maintenance-service/        # Плановое ТО, пробег, напоминания (Block 2)
│   ├── sensors-service/            # Датчики, калибровка, события (Block 2)
│   ├── billing-service/            # Тарифы, оплата, подписки, счета (Block 2)
│   ├── ticket-service/             # Тикеты техподдержки, диалоги (Block 2)
│   ├── web-billing/                # Биллинг (PostMVP)
│   ├── web-frontend/               # React + Leaflet (Block 3)
│   └── API-Gateway/                # Аутентификация, маршрутизация (Block 3)
├── infra/
│   ├── databases/
|   |   ├── timescaledb-init.sql
|   |   ├── postgis-init.sql
|   |   ├── postgres-init.sql
│   ├── kafka/
|   |   ├── TOPICS.md
|   |   ├── consumer-examples.md
|   |   └── producer-examples.md
|   |-- redis/
|   |   ├── KEYS.md
|   |   ├── data-flows.md
|   |-- nginx/
|   |   ├── nginx.conf
|   |-- monitoring/
│   └── scripts/
├── docs/                           # Системная документация
│   ├── ARCHITECTURE.md             # Общая архитектура (3 блока)
│   ├── blocks/
│   │   ├── ARCHITECTURE_BLOCK1.md  # Block 1: Data Collection
│   │   ├── ARCHITECTURE_BLOCK2.md  # Block 2: Business Logic
│   │   └── ARCHITECTURE_BLOCK3.md  # Block 3: Presentation
│   ├── DATA_STORES.md
│   ├── AI_AGENTS_INSTRUCTIONS.md
│   └── services/
│       ├── CONNECTION_MANAGER.md
│       ├── DEVICE_MANAGER.md
│       ├── HISTORY_WRITER.md
│       └── ...
├── legacy-stels/                   # Старый Java-код (ТОЛЬКО для справки!)
│   ├── docker-compose.yml          # Инфраструктура старого проекта
│   ├── monitoring/                 # Веб-приложение (Spring MVC + ExtJS)
│   ├── packreceiver/               # TCP сервер приёма GPS-пакетов
│   ├── core/                       # Парсеры протоколов (Teltonika, Wialon, Ruptela)
│   ├── conf/                       # Конфигурация
│   └── ansible/                    # Deploy скрипты
├── docs/
│   ├── LEGACY_API.md               # Справочник всех методов старого API (78 методов Ext Direct)
│   ├── STELS_GEOZONE_ANALYSIS.md   # Анализ геозон из старого проекта
│   └── services/                   # Design docs для каждого сервиса
├── test-stand/
├── docker-compose.yml
└── build.sbt
```

### Структура внутри сервиса (Scala)

```
services/[service-name]/
├── docs/
│   ├── README.md        # Точка входа: что делает, как запустить
│   ├── ARCHITECTURE.md  # Внутренняя архитектура, диаграммы
│   ├── API.md           # REST endpoints (если есть)
│   ├── DATA_MODEL.md    # Схемы БД, Redis ключи
│   ├── KAFKA.md         # Топики: consume / produce
│   ├── DECISIONS.md     # ADR — принятые решения
│   └── RUNBOOK.md       # Запуск, дебаг, типичные ошибки
│   └── INDEX.md         # Содержание документации сервиса, описание документов
└── src/main/scala/com/wayrecall/tracker/[serviceName]/
    ├── Main.scala
    ├── domain/
    ├── config/
    ├── service/
    ├── repository/
    ├── api/
    ├── kafka/
    ├── processing/     # Обработка данных (pipelines, фильтры)
    ├── redis/          # Redis клиент (state store, кэши)
    ├── network/
    ├── storage/
    └── util/
```

> **Правило:** вопрос по сервису — начни с `services/[name]/docs/README.md`.

### Не коммитить в git

- Пароли, токены, криптографические ключи
- Файлы: `.p12`, `.pem`, `.env`, `application.local.conf`
- Только использовать `.env.example` как шаблон с дефолтными значениями

---

## 6. Технический стек

| Слой | Технология | Версия | Назначение |
|---|---|---|---|
| **Язык** | Scala | 3.4.0 | Все backend-сервисы |
| **FX Runtime** | ZIO | 2.0.20 | Эффекты, конкурентность, DI |
| **TCP Server** | Netty | 4.1.104 | Приём TCP от трекеров |
| **HTTP** | zio-http | 3.0.0-RC4 | REST API |
| **Messaging** | Apache Kafka | 3.4+ | Асинхронная коммуникация |
| **Cache** | Redis | 7.0 | Кэш, очередь команд, Pub/Sub |
| **TimeSeries DB** | TimescaleDB | latest | История GPS точек |
| **Spatial** | PostGIS | latest | Геозоны, пространственные запросы |
| **RDBMS** | PostgreSQL | 15 | Master data (devices, users, orgs) |
| **DB Library** | Doobie | 1.0.0-RC4 | Functional JDBC |
| **JSON** | zio-json | 0.6.2 | Сериализация |
| **Config** | zio-config + magnolia | 4.0.0-RC16 | HOCON конфигурация, deriveConfig |
| **Kafka Client** | zio-kafka | 2.2.0 | Kafka consumer/producer (ZIO обёртка) |
| **Migrations** | Flyway | — | Миграции SQL схем |
| **Logging** | ZIO Logging + Logback | — | Логирование |
| **Containers** | Docker + Docker Compose | — | Разработка и деплой |
| **Monitoring** | Prometheus + Grafana | — | Метрики |
| **Frontend** | React + TypeScript + Leaflet | — | Web UI (Block 3) |

> **Правило:** не менять стек сервиса без обсуждения. Стек — контракт.

---

## 7. Архитектурные цели и SLA

| Метрика | Цель | Примечание |
|---|---|---|
| GPS packet latency (p99) | < 100ms | Parse → Kafka |
| REST API latency (p99) | < 200ms | Device Manager |
| Concurrent GPS trackers | 20,000+ | На весь кластер |
| GPS points/sec | 20,000+ | Без потерь |
| History write latency | < 10 сек | Kafka → TimescaleDB |
| Uptime SLA | 99.9% | ~8.7 часов даунтайма в год |
| Redis ops latency | < 1ms | После оптимизации v2.1 |
| Data retention | 30 дней | GPS история — потом архив |

### Принципы надёжности

- Retry с exponential backoff для Redis/Kafka операций
- Circuit breaker для внешних интеграций (Wialon, webhooks, ОДСМосру)
- Graceful shutdown — все сервисы обязаны его поддерживать
- Health check endpoint: `GET /health` (обязателен для K8s liveness probe)
- Идемпотентность Kafka consumers — дублирование сообщений безопасно

---

## 8. Ответственности сервисов

### Block 1 — Data Collection

| Сервис | Порт | Ответственность |
|---|---|---|
| **Connection Manager** | 5001-5004 (TCP), 10090 (API) | Приём TCP от GPS-трекеров, парсинг протоколов, фильтрация точек |
| **Device Manager** | 10092 | CRUD устройств/организаций, отправка команд на трекеры |
| **History Writer** | 10091 | Запись GPS-истории в TimescaleDB, агрегация |

### Block 2 — Business Logic

| Сервис | Порт | Ответственность |
|---|---|---|
| **Maintenance Service** | 8087 | Плановое ТО, пробег, моточасы, напоминания |
| **User Service** | 8091 | Пользователи, роли, организации, права доступа |
| **Rule Checker** | 8093 | Геозоны (enter/leave), превышение скорости, правила |
| **Notification Service** | 8094 | Уведомления: email, SMS, push, Telegram, webhook |
| **Analytics Service** | 8095 | Отчёты, агрегация данных, экспорт (PDF/Excel/CSV) |
| **Integration Service** | 8096 | Ретрансляция GPS в Wialon/Navixy, webhooks, Inbound API |
| **Admin Service** | 8097 | Мониторинг системы, управление Kafka/Redis, health check |
| **Sensors Service** | 8098 | Обработка датчиков, калибровка, события (слив/заправка) |
| **Billing Service** | 8099 | Тарифы, подписки, оплата, счета, баланс |
| **Ticket Service** | 8101 | Тикеты техподдержки, диалоги, настройки уведомлений |

### Block 3 — Presentation

| Сервис | Порт | Ответственность |
|---|---|---|
| **API Gateway** | 8080 | Аутентификация, маршрутизация, rate limiting |
| **WebSocket Service** | 8090 | Real-time GPS позиции, геозоны, скорость (Kafka → WS). 60 тестов ✅ |
| **Web Frontend** | 3001 | React + Leaflet: карта, маршруты, управление |

### Правила разграничения

- Каждый сервис — **единственный владелец** своих данных
- Другие сервисы читают через API или Kafka, НЕ через прямой доступ к БД
- При добавлении нового сервиса — обновить таблицу и назначить порт
- Kafka топики и Redis ключи каждого сервиса — см. `services/[name]/docs/`

---

## 9. Инфраструктура и шины данных

### Kafka Topics

**Все сервисы коммуницируют через Kafka для асинхронной передачи событий.**

**Основные принципы:**
- Партицирование по `deviceId` или `imei` — гарантирует порядок команд для одного устройства или инстанса
- Retention политика — разная для разных типов данных (7-90 дней)
- Идемпотентные consumers — дублирование сообщений безопасно
- **Все топики, их схемы, consumer groups, формат сообщений** — см. **[infra/kafka/TOPICS.md](../infra/kafka/TOPICS.md)**
- **Для каждого сервиса в `services/[name]/docs/`** — только его потребляемые/публикуемые топики и примеры кода

### Redis: кэширование и очереди

**Redis используется для кэширования и очередей команд в системе.**

**Основные принципы:**
- Разные сервисы используют специфичные ключи (см. документацию сервиса)
- Большие данные → Redis, потом обновление в БД
- TTL политика: контекст устройства 1 час, очереди команд 24 часа
- **Полное описание всех ключей и операций** — см. **[infra/redis/](../infra/redis/)** (если есть)
- **Для каждого сервиса в `services/[name]/docs/`** — только Redis ключи которые он использует

### Порты

**Правило: TCP серверы протоколов → 5000+, API сервисов → 10090+**

```
GPS Protocols (TCP Server Ports):
  5001: Teltonika Codec 8/8E
  5002: Wialon IPS
  5003: Ruptela
  5004: NavTelecom FLEX
  500X: по мере добавления новых протоколов

Block 1 — Data Collection:
  10090: Connection Manager (health, metrics, admin)
  10091: History Writer (health, metrics)
  10092: Device Manager (REST API, health)

Block 2 — Business Logic:
  8087: Maintenance Service
  8091: User Service
  8093: Rule Checker
  8094: Notification Service
  8095: Analytics Service
  8096: Integration Service
  8097: Admin Service
  8098: Sensors Service
  8099: Billing Service
  8101: Ticket Service

Block 3 — Presentation:
  8080: API Gateway (public, клиенты)
  8090: WebSocket Service (WS + health, real-time GPS)
  3001: Web Frontend (React dev server)

Инфраструктура (дефолтные):
  5432: PostgreSQL/TimescaleDB
  6379: Redis
  9092: Kafka (broker)
  9090: Prometheus
  3000: Grafana
```

**Памятка:** при добавлении нового сервиса Block 2 → берёшь следующий свободный порт после 8098.

---

## 10. Базы данных и хранилища

### Правила

- Все изменения схемы — **только через Flyway миграции** (`V1__`, `V2__` и т.д.)
- При добавлении таблицы/поля — обновить `DATA_MODEL.md` сервиса
- Тестирование с реальной БД — через `testcontainers`
- Полные схемы БД, indexes, constraints — см. `services/[service]/docs/DATA_MODEL.md`
- Каждый сервис владеет **своей схемой** (namespace) — прямой доступ к чужим таблицам запрещён
- Миграции лежат в `services/[service]/src/main/resources/db/migration/`
- Общие скрипты инфраструктуры — в `infra/databases/`

### Принципы хранения

**TimescaleDB** — гипертаблицы для временных рядов (GPS-точки, значения датчиков)
- Compression после 7 дней, retention 90 дней
- Continuous aggregates для часовых/дневных агрегатов

**PostgreSQL** — master data (devices, users, orgs, rules, templates и т.д.)
- Каждый сервис со своей схемой (`maintenance`, `sensors`, `notifications` и т.д.)

**Redis** — кэширование, очереди, state stores
- TTL политика: контекст устройства 1 час, очереди команд 24 часа
- Паттерны ключей документированы в `services/[service]/docs/DATA_MODEL.md`

---

## 11. ФП принципы (Scala + ZIO)

### Обязательные правила

1. **Immutability** — только `val`, никогда `var`
2. **Типизированные ошибки** — sealed trait для DomainError, никогда `throw`
3. **Option вместо null** — использовать `someOrFail()`, pattern matching, `getOrElse()`
4. **Multi-tenant изоляция** — `organization_id` ОБЯЗАТЕЛЕН в каждом запросе к БД
5. **ZIO Layer** — Dependency Injection через слои, бинди в main
6. **For-comprehension** — логируй цель каждого шага через комментарии
7. **Opaque types** — используй для domain констант (Imei, VehicleId, OrgId)

### Примеры и справка

- **Полные примеры кода** — смотри документацию конкретного сервиса (`services/[name]/docs/ARCHITECTURE.md`)
- **Тестирование (ZIOSpecDefault)** — >80% coverage для service layer, testcontainers для БД
- **Kafka топики и маршруты** — см. **[infra/kafka/TOPICS.md](../infra/kafka/TOPICS.md)**
- **Redis ключи и структуры** — см. **[infra/redis/](../infra/redis/)**
- **GPS протоколы** — см. **[infra/gps-protocols/](../infra/gps-protocols/)**

---

## 12. Стандарт логирования (ZIO Logging)

### Уровни логирования

| Уровень | Когда использовать | Примеры |
|---|---|---|
| `ZIO.logInfo` | Успешные бизнес-операции, создание/изменение/удаление сущностей, важные переходы состояний | Создание устройства, вход в геозону, выполненное ТО, превышение скорости |
| `ZIO.logWarning` | Отказ в доступе, некорректные данные, деактивация, сервис недоступен, аномалии | Permission denied, дубликат email, слив топлива, деактивация ключа |
| `ZIO.logDebug` | SQL запросы, cache hit/miss, pipeline шаги, промежуточные результаты | Кеш-хит правил, количество кандидатов SpatialGrid, pending anti-bounce |
| `ZIO.logError` | Непредвиденные ошибки, потеря данных, критические сбои | Ошибка записи в Kafka, S3 upload fail, потеря TCP соединения |

### Правила

1. **Все лог-сообщения на русском языке** (кроме технических ID и значений)
2. **Включать контекст**: всегда указывать `vehicleId`, `orgId`, `userId`, `imei` или другой релевантный ID
3. **Префикс модуля**: начинать сообщение с имени модуля — `"Геозоны: ..."`, `"Скорость: ..."`, `"ТО: ..."`, `"Шаблон: ..."`, `"Webhook: ..."`
4. **Не логировать секреты**: пароли, токены, API-ключи (можно первые 4 символа + `...`)
5. **Логировать результат, не процесс**: "Создан шаблон ТО: id=X" > "Начинаем создание шаблона"
6. **При ошибках — включать причину**: `"Webhook: ошибка URL — HTTP 503 (120ms)"`
7. **Rate-sensitive методы** (вызов на каждую GPS-точку): использовать `ZIO.logDebug` или `ZIO.when(condition)(ZIO.logInfo(...))`
8. **Кеш-операции**: всегда логировать hit/miss с количеством записей

### Минимальные требования по пакетам

| Пакет | Минимум логов |
|---|---|
| `service/` | `logInfo` для каждой мутирующей операции (create/update/delete) |
| `kafka/` | `logInfo` при старте consumer, `logWarning` при ошибке парсинга |
| `processing/` | `logDebug` при входе в pipeline с количеством элементов |
| `channel/` | `logInfo`/`logWarning` для каждой отправки (успех/неуспех) |
| `storage/` | `logDebug` для cache hit/miss, `logWarning` для throttle/rate-limit |
| `api/` | логирование через middleware (API Gateway); отдельные логи не обязательны |
| `domain/`, `config/` | логирование не нужно |

### Пример правильного логирования (for-comprehension)

```scala
def createTemplate(companyId: UUID, req: CreateTemplateRequest): Task[MaintenanceTemplate] =
  for {
    _ <- ZIO.logInfo(s"ТО: создание шаблона '${req.name}', компания=$companyId")
    // ... бизнес-логика ...
    _ <- templateRepo.create(template)
    _ <- ZIO.logInfo(s"ТО: шаблон создан id=$templateId, name='${req.name}'")
  } yield template
```

### Anti-patterns

- ❌ `ZIO.logInfo("OK")` — нет контекста
- ❌ `println(s"Debug: $x")` — использовать ZIO Logging
- ❌ Логировать каждую GPS-точку на уровне Info — перегрузка логов
- ❌ `catchAll(_ => ZIO.logError(...))` без re-throw — проглатывание ошибок

---

## ⚠️ Common Mistakes to Avoid

| Ошибка | Последствие | Решение |
|---|---|---|
| Использование `.get` на Option/Try | Runtime краш | Использовать `someOrFail()` или pattern matching |
| Забыл `organization_id` в фильтре | Data leak между организациями | Всегда проверять org контекст в сервисе |
| `var` вместо `val` | Нарушает referential transparency | Использовать immutable collections |
| Blocking calls в эффектах | Deadlock, потеря concurrency | Использовать non-blocking API (ZIO, async) |
| String partition keys в Kafka | Неправильный порядок | Использовать numeric `deviceId` для partitioning |
| Null returns из БД | NPE в runtime | Использовать Option/Either в схеме |
| Игнорирование Kafka ordering | Race conditions в командах | Одно устройство = один partition |
| Отсутствие tests для error cases | Падения в production | Тестировать happy path + все error scenarios |
| Hardcoded secrets в коде | Security breach | Только env variables или Docker secrets |
| Игнорирование graceful shutdown | Потеря данных при deployment | Все сервисы обязаны его реализовать |

---

## 🎯 Before Writing Code

Перед тем, как начать кодировать, ответь на эти 5 вопросов:

1. **Понимаешь ли ты границы сервисов?**
   - Твоё изменение пересекает сервисы? Если да → используй Kafka или Redis
   - Оно внутри одного сервиса? → синхронный код OK

2. **Проверил ли ты изоляцию по org?**
   - `organization_id` присутствует в запросе к БД? 
   - Конфликт между организациями = критическая ошибка

3. **Выбрал ли ты правильный Kafka топик?**
   - Какой топик для этого события?
   - Правильный ключ партиции (deviceId или imei или другой)?
   - Consumer готов его обработать?

4. **Протестировал ли ты error scenarios?**
   - Что если устройство оффлайн?
   - Что если БД упала?
   - Что если Redis недоступен?
   - Что если Kafka lag растёт?
   - Какие сервисы задействованы?

---

## 🧪 Testing Checklist

Перед тем как сабмитить код:

- [ ] **Unit тесты** покрывают service layer (>80% coverage)
- [ ] **Test happy path + error scenarios** (не только успех)
- [ ] **Integration тесты** для DB/Redis/Kafka (с `testcontainers`)
- [ ] **Используется `ZIOSpecDefault`** для async тестов
- [ ] **Mock external dependencies** с ZIO Mock
- [ ] **Нет hardcoded secrets** в тестах и коде
- [ ] **Тесты воспроизводят реальные сценарии** (например, offline device)
- [ ] **Все error types покрыты** (DeviceNotFound, ParseError, etc.)
- [ ] **Doobie queries проверены** через integration tests
- [ ] **Kafka message ordering** протестирована (same device = same partition)

---

## 15. Система документирования сервисов

### Структура документации

**Документация в `/infra/` — для всего проекта:**
- `infra/kafka/TOPICS.md` — все Kafka топики, их маршруты, consumer groups
- `infra/redis/` — все Redis ключи, структуры, операции (общие для всех сервисов)
- `infra/databases/` — схемы БД, миграции, индексы
- `infra/gps-protocols/` — все GPS протоколы (Teltonika, Wialon, Ruptela, NavTelecom)

**Документация в `services/[name]/docs/` — для конкретного сервиса:**
- Какие топики он потребляет/публикует (со ссылкой на `infra/kafka/TOPICS.md`)
- Какие Redis ключи он использует (со ссылкой на `infra/redis/`)
- Какие таблицы БД он использует (со ссылкой на `infra/databases/`)
- Внутренняя архитектура и компоненты сервиса

### Обязательные файлы в `services/[name]/docs/`

| Файл | Содержание | Обязателен |
|---|---|---|
| `README.md` | Что делает, как запустить, ссылки | Да |
| `ARCHITECTURE.md` | Внутренняя архитектура, Mermaid диаграммы | Да |
| `API.md` | HTTP endpoints, примеры | если есть API |
| `DATA_MODEL.md` | Какие таблицы/ключи использует (со ссылками на /infra/) | если есть БД/кэш |
| `KAFKA.md` | Какие топики consume/produce (со ссылкой на infra/kafka/TOPICS.md) | если есть Kafka |
| `DECISIONS.md` | ADR — почему приняты решения | Да |
| `RUNBOOK.md` | Запуск, дебаг, типичные ошибки | Да |
| `TESTING.md` | Описание всех тестов | Да |
| `LEARNING.md` | Подробный гайд как разобраться с кодом, изучить сервис | Да |
| `INDEX.md` | описание документов, где что лежит внутри сервиса | Да |

**Правило:** не дублируй информацию. Используй ссылки на `/infra/`.

---

## 16. Синхронизация документов между сервисами

### Процесс утверждения инфраструктурных сущностей

**Инфраструктурные сущности** — это Kafka топики, Redis ключи, БД схемы, которые используют **несколько сервисов**.

**Когда утверждаются новые инфрасущности:**

1. **Фаза проектирования** (документирование)
   - Создай задачу с описанием новой сущности (топик, ключ, таблица)
   - Приложи диаграмму маршрутов и примеры сообщений
   - Обсуди с командой: может ли существующая сущность переиспользоваться?

2. **Фаза утверждения** (решение)
   - Согласование в PR: код + документация в `/infra/` одновременно
   - Например: добавляем топик → сразу в PR обновляем `infra/kafka/TOPICS.md`
   - Code review проверяет обе части: код + документацию

3. **Фаза синхронизации** (распространение в сервисы)
   - После merge в main: каждый сервис обновляет свою документацию
   - Например: новый топик в TOPICS.md → обновляют KAFKA.md всех затронутых сервисов
   - Возможна в отдельном пакете задач если сервисов много

### Матрица синхронизации

| Что изменилось | Где утверждается | Что синхронизируется | Срок |
|---|---|---|---|
| Новый Kafka топик | PR: код сервиса-продюсера + `/infra/kafka/TOPICS.md` | KAFKA.md всех потребителей | в том же PR или сразу после |
| Новый Redis ключ | PR: код сервиса + `/infra/redis/` | DATA_MODEL.md сервисов-потребителей | в том же PR |
| Новая БД таблица/поле | PR: Flyway миграция + `/infra/databases/` | DATA_MODEL.md сервиса-владельца | в том же PR |
| Новый REST API | PR: код + API.md сервиса | README всех клиентов | в том же PR или отдельный PR |
| Новый сервис | PR: архитектура + раздел 8 этого файла | ARCHITECTURE.md, обновить матрицы | в том же PR |

### Чеклист при изменении сервиса

```
[ ] Обновлён docs/ сервиса (ARCHITECTURE.md, KAFKA.md, DATA_MODEL.md и т.д.)
[ ] ЕСЛИ изменилась инфра сущность → обновлена /infra/
[ ] ЕСЛИ новая инфра сущность → затронутые сервисы уведомлены о синхронизации
[ ] Обновлены потребители (если изменился API / Kafka топик)
[ ] Тег документа обновлён (раздел 19)
[ ] Тесты обновлены / добавлены
[ ] Нет hardcoded секретов
[ ] build.sbt обновлён (если добавлены зависимости)
```

---

## 17. Правила диаграмм и схем

Инструмент: **Mermaid** (встраивается в Markdown)

| Тип | Когда использовать |
|---|---|
| `flowchart LR` | Поток данных между сервисами |
| `sequenceDiagram` | Последовательность запросов |
| `stateDiagram-v2` | Жизненный цикл (соединения, команды) |
| `erDiagram` | Схема БД |
| `classDiagram` | UML-схема классов сервиса (композиция, наследование, зависимости между слоями) |

**Правила:** диаграмма точно отражает текущее состояние; при изменении потока — немедленно обновить.

---

## 18. Тестовый стенд

Документация: [test-stand/README.md](../test-stand/README.md)

Инструкции по запуску инфраструктуры, сервисов и проверке здоровья находятся в README файле тестового стенда.

---

## 19. Система тегов и версионирование документов

Каждый документ начинается с тега:

```markdown
> Тег: `АКТУАЛЬНО` | Обновлён: `YYYY-MM-DD` | Версия: `1.x`
```

| Тег | Значение |
|---|---|
| `АКТУАЛЬНО` | Можно доверять, отражает текущее состояние |
| `УСТАРЕЛО` | Не использовать без проверки |
| `ЧЕРНОВИК` | В процессе написания |
| `АРХИВ` | Больше не применяется, только для истории |
| `ПРОВЕРИТЬ` | Требует проверки (>30 дней без обновления) |

### Правила

- При любом изменении сервиса — обновить тег и дату в его `docs/`
- Если документ не обновлялся >30 дней — автоматически ставить тег `ПРОВЕРИТЬ`
- Устаревшие документы не удалять — перемещать в `docs/archive/`

---

## 20. История общения с ИИ

Папка: `ai_chat_history/` (создать при необходимости)

```
ai_chat_history/
└── YYYY-MM-DD/
    └── [тема].md    # Краткое резюме: что обсуждали, что решили
```

---

## 21. Планы на день

Папка: `daily_tasks/` (создать при необходимости)

### Формат имени файла

**Обязательно:** `ДД-ММ-ГГГГ.md` (русский формат: день-месяц-год)

Примеры:
- `05-03-2026.md` — 5 марта 2026
- `19-02-2026.md` — 19 февраля 2026
- `26-02-2026.md` — 26 февраля 2026

**Запрещено:** `YYYY-MM-DD.md` (американский/ISO формат)

### Шаблон содержимого

```markdown
# План на [число] [месяц на русском] [год], [день недели]
## 🎯 Главная цель дня
[Одно предложение]
## Задачи
- [ ] [Задача] — [Сервис]
## Итог дня
[Что сделано, что перенесено на завтра]
```

### Правила

- Дата в заголовке — **прописью**: `5 марта 2026, среда`
- Дата в имени файла — **цифрами**: `05-03-2026.md`
- День недели обязателен в заголовке
- В конце файла: `*Создан: [дата]*`

---

## 22. База знаний и обучение

Папка: `learning/` (создать при необходимости)

Документация по изучению технологий и компонентов системы.

### Legacy Stels

**Старый проект:** папка `legacy-stels/` — Java-код (ТОЛЬКО для справки при переносе логики)

Основные файлы документации:
- **[LEGACY_API.md](../docs/LEGACY_API.md)** — 78 методов Ext Direct API
- **[STELS_GEOZONE_ANALYSIS.md](../docs/STELS_GEOZONE_ANALYSIS.md)** — Анализ геозон (КРИТИЧНО)

Структура legacy-stels:
- `packreceiver/` — TCP сервер приёма GPS-пакетов
- `core/` — Парсеры протоколов (Teltonika, Wialon, Ruptela)
- `monitoring/` — Веб-приложение (Spring MVC + ExtJS)
- `conf/` — Конфигурация

---

## 23. Безопасность

### Главные правила

- **Секреты через env variables** — никогда в коде или git
- **Multi-tenant изоляция** — `organization_id` ОБЯЗАТЕЛЕН в каждом запросе к БД и в каждом Kafka сообщении
- **Rate limiting** для TCP уровня (Token Bucket)
- **Whitelist IMEI** — неизвестные устройства отклонять с алертом
- **Проверка временной метки** пакета (защита от replay атак)

---

## 24. Git и PR workflow

### Naming branches

```
feat/[service]/[short-description]
fix/[service]/[short-description]
refactor/[service]/[short-description]
docs/[what]
```

### Commit format (Conventional Commits)

```
[type]([scope]): [description]

Типы: feat, fix, refactor, docs, test, chore, perf
Scope: cm, dm, hw, rc, ns, as, us, ads, is, ms, ss, infra, docs
```

### PR Checklist

```
[ ] Unit tests для изменённой логики (>80% coverage)
[ ] Integration tests (если затронута БД/Redis/Kafka)
[ ] Документация обновлена (API.md, KAFKA.md и т.д.)
[ ] Flyway миграции добавлены (если изменилась схема)
[ ] DECISIONS.md обновлён (если принято архитектурное решение)
[ ] Тег документов обновлён
[ ] Нет hardcoded секретов
[ ] build.sbt обновлён (если добавлены зависимости)
```

---

## 25. Deployment и DevOps

**Основные сервисы запускаются с Docker Compose.** Документация:
- Поднять инфраструктуру → [infra/README.md](../infra/README.md)
- Health checks и мониторинг → [test-stand/README.md](../test-stand/README.md)

**Переменные окружения** для Connection Manager — см. [services/connection-manager/docs/README.md](../services/connection-manager/docs/README.md)

**Graceful shutdown** — все сервисы обязаны его реализовать (ZIO handle sigterm/sigint)

---

## 26. Типичные задачи и решения

**Добавить новый GPS протокол:**
1. Создать парсер в `services/connection-manager/src/main/scala/.../protocol/`
2. Добавить в `MultiProtocolParser` список
3. Написать unit тесты с реальными бинарными пакетами
4. Обновить **[PROTOCOLS.md](../services/connection-manager/docs/PROTOCOLS.md)**

**Добавить новую команду для трекера:**
1. Добавить case class в `domain/Command.scala`
2. REST endpoint в Device Manager
3. Обработку в Connection Manager (в протоколе)
4. Unit + интеграционный test
5. Обновить **[API.md](../docs/services/DEVICE_MANAGER.md)**

**Проверить застрявшую команду:**
- Смотреть Redis очередь → **[infra/redis/](../infra/redis/)**
- Смотреть Kafka consumer lag → **[infra/kafka/TOPICS.md](../infra/kafka/TOPICS.md)**
- Смотреть как это реализовано в CM → **[services/connection-manager/docs/](../services/connection-manager/docs/)**

---

## 27. Troubleshooting

**Основной справочник:** [services/connection-manager/docs/RUNBOOK.md](../services/connection-manager/docs/RUNBOOK.md)

**Частые проблемы:**
- ParseError на каждый пакет → проверить протокол и версию
- Redis latency spike → проверить количество операций (HGETALL)
- Kafka consumer lag растет → проверить BATCH_SIZE, скорость БД

---

## Обязательная документация

**Системная архитектура:**
- **[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)** — общая архитектура 3 блоков
- **[docs/blocks/ARCHITECTURE_BLOCK1.md](../docs/blocks/ARCHITECTURE_BLOCK1.md)** — Data Collection
- **[docs/blocks/ARCHITECTURE_BLOCK2.md](../docs/blocks/ARCHITECTURE_BLOCK2.md)** — Business Logic
- **[docs/blocks/ARCHITECTURE_BLOCK3.md](../docs/blocks/ARCHITECTURE_BLOCK3.md)** — Presentation

**Инфраструктура (общая для всех сервисов):**
- **[infra/kafka/TOPICS.md](../infra/kafka/TOPICS.md)** — Все Kafka топики, маршруты, JSON схемы
- **[infra/redis/](../infra/redis/)** — Все Redis ключи, структуры, операции
- **[infra/databases/](../infra/databases/)** — Схемы TimescaleDB и PostgreSQL

**Сервисы (Block 1 — Data Collection):**
- **[services/connection-manager/docs/](../services/connection-manager/docs/)** — TCP, парсинг GPS, протоколы
- **[services/device-manager/docs/](../services/device-manager/docs/)** — REST API, команды, CRUD устройств
- **[services/history-writer/docs/](../services/history-writer/docs/)** — Запись GPS в TimescaleDB

**Сервисы (Block 2 — Business Logic):**
- **[services/maintenance-service/docs/](../services/maintenance-service/docs/)** — Плановое ТО, пробег, напоминания
- **[services/sensors-service/docs/](../services/sensors-service/docs/)** — Датчики, калибровка, события
- **[services/rule-checker/docs/](../services/rule-checker/docs/)** — Геозоны, скорость, правила
- **[services/notification-service/docs/](../services/notification-service/docs/)** — Уведомления (email, SMS, push, Telegram)
- **[services/analytics-service/docs/](../services/analytics-service/docs/)** — Отчёты, агрегация, экспорт
- **[services/user-service/docs/](../services/user-service/docs/)** — Пользователи, роли, организации
- **[services/admin-service/docs/](../services/admin-service/docs/)** — Администрирование, мониторинг
- **[services/integration-service/docs/](../services/integration-service/docs/)** — Ретрансляция, Wialon, webhooks
- **[services/billing-service/docs/](../services/billing-service/docs/)** — Тарифы, оплата, подписки, счета
- **[services/ticket-service/docs/](../services/ticket-service/docs/)** — Тикеты техподдержки, диалоги

---

*Версия: 4.1 | Обновлён: 5 марта 2026 | Тег: АКТУАЛЬНО*
*Maintained by: Development Team*