# Connection Manager — Интенсивный План Обучения (Junior)

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-14` | Версия: `1.0`

## Цель

За короткий срок понять:
- как устроен сервис CM целиком;
- как тут работают асинхронность и параллельность;
- как применяются ZIO, ZIO fibers и ZLayer;
- как читать и отлаживать hot path от TCP пакета до Kafka.

## Как проходить этот план

- Режим: 2-3 часа в день, 7 дней (интенсив).
- На каждый блок: сначала читаешь код, затем статьи, затем делаешь мини-практику.
- Все вопросы помечай тегами QUESTION(U)/WHY(U)/CONFUSED(U)/TRACE(U) прямо в файлах.
- Стандарт тегов: [LEARNING_COMMENT_STYLE.md](LEARNING_COMMENT_STYLE.md).

---

## День 0 — Карта сервиса (30-60 минут)

### Что читать в проекте

1. [README.md](README.md)
2. [INDEX.md](INDEX.md)
3. [ARCHITECTURE.md](ARCHITECTURE.md)
4. [LEARNING.md](LEARNING.md)

### Что понять

- Какие подсистемы есть в CM.
- Какие входы/выходы у сервиса (TCP, Kafka, Redis, HTTP).
- Где точка входа и где hot path.

### Статьи

- System design basics: https://martinfowler.com/articles/microservices.html
- Event-driven basics: https://www.confluent.io/learn/event-driven-architecture/

---

## День 1 — Точка входа и жизненный цикл приложения

### Порядок чтения классов

1. [Main.scala](../src/main/scala/com/wayrecall/tracker/Main.scala)
2. [AppConfig.scala](../src/main/scala/com/wayrecall/tracker/config/AppConfig.scala)
3. [application.conf](../src/main/resources/application.conf)

### Что понять

- Разница между `program` и `appLayer`.
- Порядок старта компонент и почему он критичен.
- Где именно запускаются TCP серверы, Kafka consumers, HTTP API.

### Статьи

- ZIO overview: https://zio.dev/overview/
- ZIO core effect: https://zio.dev/reference/core/zio/
- ZLayer: https://zio.dev/reference/contextual/zlayer/

### Мини-практика

- Нарисуй вручную pipeline старта: слой -> запуск -> проверка готовности.
- Найди в логах каждый шаг старта после `sbt run`.

---

## День 2 — TCP/Netty база: как пакет попадает в систему

### Порядок чтения классов

1. [TcpServer.scala](../src/main/scala/com/wayrecall/tracker/network/TcpServer.scala)
2. [ConnectionHandler.scala](../src/main/scala/com/wayrecall/tracker/network/ConnectionHandler.scala) — только lifecycle-часть (`channelActive`, `channelRead`, `channelInactive`)
3. [ConnectionRegistry.scala](../src/main/scala/com/wayrecall/tracker/network/ConnectionRegistry.scala)

### Что понять

- Что такое EventLoopGroup, pipeline, handler.
- Почему важно не блокировать Netty I/O thread.
- Как организован lifecycle одного TCP-соединения.

### Статьи

- Netty 4 user guide: https://netty.io/wiki/user-guide-for-4.x.html
- Epoll vs NIO (обзор): https://netty.io/wiki/native-transports.html

### Мини-практика

- Проследи один connect/disconnect по логам.
- Ответь себе письменно: где именно может случиться блокировка I/O и как проект это избегает.

---

## День 3 — Асинхронность, параллельность, fibers

### Порядок чтения классов

1. [ConnectionHandler.scala](../src/main/scala/com/wayrecall/tracker/network/ConnectionHandler.scala) — блоки с `fork`, `Semaphore`, обработкой пакетов
2. [IdleConnectionWatcher.scala](../src/main/scala/com/wayrecall/tracker/network/IdleConnectionWatcher.scala)
3. [RateLimiter.scala](../src/main/scala/com/wayrecall/tracker/network/RateLimiter.scala)

### Что понять

- Разница: асинхронность vs параллельность.
- Зачем `fork` и где нужен `Semaphore(1)`.
- Почему `foreachParDiscard(...).withParallelism(32)` полезен.

### Статьи

- Fibers: https://zio.dev/reference/fiber/
- Concurrency primitives: https://zio.dev/reference/concurrency/
- Runtime and scheduling: https://zio.dev/reference/core/runtime/

### Мини-практика

- Напиши себе 3 примера из CM: где используется последовательность, где параллельность, где фоновая задача.

---

## День 4 — Протоколы и парсинг GPS

### Порядок чтения классов

1. [ProtocolParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/ProtocolParser.scala)
2. [MultiProtocolParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/MultiProtocolParser.scala)
3. Один конкретный парсер: [TeltonikaParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/TeltonikaParser.scala)
4. [PROTOCOLS.md](PROTOCOLS.md)

### Что понять

- Общий контракт парсера.
- Как работает autodetect протокола.
- Где и как обрабатываются ошибки парсинга.

### Статьи

- Binary protocol parsing basics: https://en.wikipedia.org/wiki/Binary_protocol
- CRC overview: https://en.wikipedia.org/wiki/Cyclic_redundancy_check

### Мини-практика

- Для выбранного парсера выпиши: входной формат, выходной доменный объект, error ветки.

---

## День 5 — Обработка точек и фильтры

### Порядок чтения классов

1. [DeadReckoningFilter.scala](../src/main/scala/com/wayrecall/tracker/filter/DeadReckoningFilter.scala)
2. [StationaryFilter.scala](../src/main/scala/com/wayrecall/tracker/filter/StationaryFilter.scala)
3. [DynamicConfigService.scala](../src/main/scala/com/wayrecall/tracker/config/DynamicConfigService.scala)
4. [DATA_MODEL.md](DATA_MODEL.md)

### Что понять

- Какие инварианты у точек до/после фильтров.
- Почему часть точек публикуется даже как невалидная.
- Как динамически меняются параметры фильтров.

### Статьи

- Haversine formula: https://en.wikipedia.org/wiki/Haversine_formula
- Data validation patterns: https://martinfowler.com/articles/replaceThrowWithNotification.html

### Мини-практика

- Придумай 3 тест-кейса: валидная точка, телепортация, стоянка.

---

## День 6 — Kafka/Redis и интеграции

### Порядок чтения классов

1. [KafkaProducer.scala](../src/main/scala/com/wayrecall/tracker/storage/KafkaProducer.scala)
2. [RedisClient.scala](../src/main/scala/com/wayrecall/tracker/storage/RedisClient.scala)
3. [CommandHandler.scala](../src/main/scala/com/wayrecall/tracker/service/CommandHandler.scala)
4. [DeviceEventConsumer.scala](../src/main/scala/com/wayrecall/tracker/service/DeviceEventConsumer.scala)
5. [KAFKA.md](KAFKA.md)

### Что понять

- Какие топики produce/consume и почему именно так.
- Где key для partition и почему это важно.
- Как устроен legacy путь через Redis Pub/Sub.

### Статьи

- Kafka consumer concepts: https://kafka.apache.org/documentation/#intro_consumers
- Kafka producer concepts: https://kafka.apache.org/documentation/#producerapi
- Redis docs: https://redis.io/docs/latest/develop/

### Мини-практика

- Пройди один сценарий команды: publish -> consume -> encode -> send -> audit.

---

## День 7 — HTTP API, метрики, тесты и закрепление

### Порядок чтения классов

1. [HttpApi.scala](../src/main/scala/com/wayrecall/tracker/api/HttpApi.scala)
2. [CmMetrics.scala](../src/main/scala/com/wayrecall/tracker/service/CmMetrics.scala)
3. [TESTING.md](TESTING.md)
4. Выборочно тесты:
- [HttpApiSpec.scala](../src/test/scala/com/wayrecall/tracker/api/HttpApiSpec.scala)
- [ConnectionRegistrySpec.scala](../src/test/scala/com/wayrecall/tracker/network/ConnectionRegistrySpec.scala)
- [TeltonikaParserSpec.scala](../src/test/scala/com/wayrecall/tracker/protocol/TeltonikaParserSpec.scala)

### Что понять

- Как API отражает внутреннее состояние сервиса.
- Как читаются метрики и что важно мониторить.
- Как тесты фиксируют инварианты и регрессии.

### Статьи

- Prometheus exposition format: https://prometheus.io/docs/instrumenting/exposition_formats/
- Testing ZIO: https://zio.dev/reference/test/

### Мини-практика

- Составь свой чеклист из 10 пунктов: что проверить после любого изменения в CM.

---

## Быстрый трек «как можно скорее» (3 дня)

Если нужен максимально быстрый вход:

1. День A:
- [Main.scala](../src/main/scala/com/wayrecall/tracker/Main.scala)
- [TcpServer.scala](../src/main/scala/com/wayrecall/tracker/network/TcpServer.scala)
- [ConnectionHandler.scala](../src/main/scala/com/wayrecall/tracker/network/ConnectionHandler.scala)

2. День B:
- [ProtocolParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/ProtocolParser.scala)
- [MultiProtocolParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/MultiProtocolParser.scala)
- [TeltonikaParser.scala](../src/main/scala/com/wayrecall/tracker/protocol/TeltonikaParser.scala)
- [KAFKA.md](KAFKA.md)

3. День C:
- [KafkaProducer.scala](../src/main/scala/com/wayrecall/tracker/storage/KafkaProducer.scala)
- [RedisClient.scala](../src/main/scala/com/wayrecall/tracker/storage/RedisClient.scala)
- [HttpApi.scala](../src/main/scala/com/wayrecall/tracker/api/HttpApi.scala)
- [TESTING.md](TESTING.md)

---

## Порядок вопросов, чтобы учиться быстрее

На каждый файл ставь минимум 3 пометки:

- QUESTION(U): главная ответственность файла.
- TRACE(U): главный путь данных.
- WHY(U): одно нетривиальное решение.

Я отвечаю рядом, чтобы у тебя формировался «живой конспект» прямо в коде.

---

## Контроль прогресса (чеклист)

- [ ] Понимаю запуск от `run` до поднятия TCP портов.
- [ ] Понимаю путь пакета от `channelRead` до Kafka.
- [ ] Понимаю, где в CM используется асинхронность, а где параллельность.
- [ ] Понимаю, зачем нужны fibers и где они создаются.
- [ ] Понимаю, как устроены слои ZLayer и зависимости сервисов.
- [ ] Понимаю, как работают парсеры и как обрабатываются ошибки.
- [ ] Понимаю, какие топики CM читает/пишет и зачем.
- [ ] Понимаю, как проверять изменения тестами и метриками.

Если хотя бы 2 пункта остаются непонятными, возвращайся к соответствующему дню и ставь TRACE(U)/CONFUSED(U) прямо в код.
