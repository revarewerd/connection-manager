# Connection Manager — Полный аудит исходного кода

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-10` | Версия: `1.0`

## Общая статистика

| Метрика | Значение |
|---|---|
| Исходных файлов (main) | **50** |
| Тестовых файлов (test) | **43** |
| Строк кода (main) | **10 572** |
| Строк тестов | **7 945** |
| Соотношение тест/код | **75%** |
| Пакет | `com.wayrecall.tracker` |
| Тегов `TODO(AI)` / `FIXME(AI)` | **0** |
| Обычных `TODO:` | **~6** |
| Проект компилируется | ✅ Да (`sbt compile` exit 0) |

### Стек (build.sbt — 71 строка)

Scala 3.4.0, ZIO 2.0.20, Netty 4.1.104 (модульный), Lettuce 6.3.2, kafka-clients 3.6.1, zio-kafka 2.7.2, zio-http 3.0.0-RC4, zio-config 4.0.1 + magnolia, zio-json 0.6.2, Logback.

---

## 1. network/ — Сетевой слой (6 файлов, ~1 968 строк)

### 1.1 ConnectionHandler.scala — 1 002 строки

**Центральный файл сервиса — Netty ↔ ZIO мост.**

| Элемент | Описание |
|---|---|
| `case class ConnectionState` | Immutable состояние TCP-соединения: imei, vehicleId, in-memory позиция, кэш `DeviceData` с TTL (1 час) |
| `trait GpsProcessingService` | Абстракция обработки GPS: `processImeiPacket`, `processDataPacket`, `onConnect`/`onDisconnect` |
| `case class GpsProcessingService.Live` | Реализация: Redis HGETALL → parse → Dead Reckoning → Stationary → Kafka routing |
| `class ConnectionHandler` | `ChannelInboundHandlerAdapter` — `channelRead` диспатчит на `handleImeiPacket`/`handleDataPacket` |

**Ключевые паттерны:**
- `Ref[ConnectionState]` + `Semaphore(1)` — порядок обработки пакетов
- DeviceData кэшируется при подключении, обновляется 1 раз/час → снижение HGETALL с 864M/день до ~10K/день
- GPS pipeline: Dead Reckoning → GpsRawPoint.toValidated → Stationary → **всегда** `gps-events`, **условно** `gps-events-rules` / `gps-events-retranslation` (только если движение + флаги)
- Unknown devices: **не отключаются** — GPS данные идут в `unknown-gps-events`
- `Unsafe.unsafe` для fork ZIO из Netty callbacks

**TODO:**
- `// TODO: передать remoteAddress из ConnectionHandler` (строка 374)
- `// TODO: убрать после миграции всех потребителей на device:{imei}` (строки 496, 536) — legacy `registerConnection`/`unregisterConnection`

**Потенциальные проблемы:**
- `@volatile var detectedParser` в `ConnectionState` — нарушение иммутабельности (поле мутируется при определении протокола)
- `Unsafe.unsafe` мостик — если ZIO fiber упадёт, Netty не узнает об ошибке (кроме через `exceptionCaught`)

---

### 1.2 TcpServer.scala — 208 строк

| Элемент | Описание |
|---|---|
| `trait TcpServer` | `start(port, handlerFactory)`, `stop(channel)` |
| `case class Live` | Netty bootstrap: native transport detection (Epoll/KQueue/NIO через reflection) |
| `class RateLimitHandler` | Netty `ChannelInboundHandlerAdapter` — проверяет IP rate limit перед handler pipeline |

**Ключевые паттерны:**
- `ZIO.asyncZIO` для `ChannelFuture` → ZIO
- `acquireRelease` для `EventLoopGroup` lifecycle
- 2 слоя: `live` (без rate limiting), `liveWithRateLimiter` (с RateLimitHandler)
- Socket options: `SO_BACKLOG=4096`, `RCVBUF/SNDBUF=4096`, WriteBufferWaterMark(16K/32K), `PooledByteBufAllocator`

---

### 1.3 ConnectionRegistry.scala — 188 строк

| Элемент | Описание |
|---|---|
| `trait ConnectionRegistry` | `register`, `unregister`, `findByImei`, connections list, idle check |
| `class MutableConnectionEntry` | Mutable entry с `AtomicLong` для `lastActivityAt` — 0-allocation hot path |
| `case class ConnectionEntry` | Immutable snapshot для read-only доступа |
| `case class Live` | `ConcurrentHashMap(16384, 0.75, 64)` для lock-free операций |

**Ключевые паттерны:**
- `ConcurrentHashMap.compute` для атомарного register/unregister
- `AtomicLong.set` для обновления lastActivity без блокировок
- `toSnapshot` — паттерн mutable→immutable конвертации
- При reconnect: старое соединение закрывается, новое заменяет

---

### 1.4 RateLimiter.scala — 193 строки

| Элемент | Описание |
|---|---|
| `trait RateLimiter` | `tryAcquire(ip)`, `getConnectionCount`, `getStats` |
| `case class ConnectionRecord` | `timestamps: List[Long]` — sliding window |
| `case class Live` | `Ref[Map[String, ConnectionRecord]]` + periodic cleanup |

**Ключевые паттерны:**
- `Ref.modify` для atomic check-and-update (sliding window)
- O(1) prepend для новых timestamps
- `Schedule.fixed` для периодической очистки устаревших записей

---

### 1.5 CommandService.scala — 217 строк

| Элемент | Описание |
|---|---|
| `trait CommandService` | `sendCommand`, `startCommandListener` |
| `case class Live` | Redis Pub/Sub (`psubscribe commands:*`) + `Promise[Throwable, CommandResult]` |

**Ключевые паттерны:**
- `Promise.make` для async ожидания ответа от трекера с timeout
- `Ref[Map[String, AwaitingCommand]]` — pending commands in-memory
- Legacy Redis Pub/Sub подход (от старого Stels)

---

### 1.6 IdleConnectionWatcher.scala — 159 строк

| Элемент | Описание |
|---|---|
| `trait IdleConnectionWatcher` | `start` (forks periodic check) |
| `case class Live` | Периодическая проверка idle + parallel disconnect (до 32 параллельных) |

**Ключевые паттерны:**
- `Schedule.fixed` для периодического сканирования
- `ZIO.foreachParDiscard.withParallelism(32)` для параллельного отключения
- При disconnect: Kafka status + Redis cleanup + Netty close

---

### 1.7 DeviceConfigListener.scala — 116 строк

| Элемент | Описание |
|---|---|
| Единственный объект | Redis Pub/Sub `psubscribe device:config:*` — disabled/enabled события |

**Ключевые паттерны:**
- `handleDeviceDisabled`: закрывает TCP + Kafka status event
- `handleDeviceEnabled`: только лог (переподключение по инициативе трекера)

---

## 2. storage/ — Хранилища (4 файла, ~750 строк)

### 2.1 RedisClient.scala — 331 строка

| Элемент | Описание |
|---|---|
| `trait RedisClient` | Абстракция: `getDeviceData`, `updateDevicePosition`, `setDeviceConnectionFields`, `clearDeviceConnectionFields`, Pub/Sub, raw ops |
| `case class Live` | Lettuce async → ZIO через `fromCompletionStage` |

**Ключевые паттерны:**
- `ZLayer.scoped` + `acquireRelease` — lifecycle клиента и Pub/Sub подключения
- Unified HASH `device:{imei}` — единственный HGETALL при подключении (3 секции: context/position/connection)
- Legacy методы (`getVehicleId`, `getPosition`, `setPosition`) — помечены для удаления
- `IO[RedisError, _]` — типизированные ошибки

---

### 2.2 KafkaProducer.scala — 190 строк

| Элемент | Описание |
|---|---|
| `trait KafkaProducer` | `publish`, `publishGpsEvent`, `publishDeviceStatus`, `publishUnknownDevice`, `publishParseError` и др. |
| `case class Live` | Java Kafka Producer, `ZIO.async` callback bridge |

**Ключевые паттерны:**
- `serializeAndPublish[A: JsonEncoder]` — generic helper с context bound
- 2 слоя: `live` и `liveWithDebug` (hex-лог каждого сообщения)
- Config: 256MB buffer, 5s max block, 10 in-flight requests
- Multi-topic routing: `gps-events`, `gps-events-rules`, `gps-events-retranslation`, `device-status`, `unknown-devices`, `unknown-gps-events`, `parse-errors`, `command-audit`

---

### 2.3 VehicleLookupService.scala — 138 строк

| Элемент | Описание |
|---|---|
| `trait VehicleLookupService` | `findByImei`, `syncAllToRedis` |
| `case class Live` | Cache-Aside: Redis → PostgreSQL fallback → cache on miss |

**Ключевые паттерны:**
- `syncAllToRedis` для preload при старте (batch)
- Fallback chain: Redis GET → DB query → Redis SET

---

### 2.4 DeviceRepository.scala — 91 строка

| Элемент | Описание |
|---|---|
| `trait DeviceRepository` | `findByImei`, `findAll` |
| `case class Dummy` | In-memory реализация с 3 hardcoded устройствами |

**TODO:** `// TODO: Добавить Doobie реализацию` — production реализация отсутствует!

---

## 3. domain/ — Доменные модели (5 файлов, ~1 494 строки)

### 3.1 GpsPoint.scala — 472 строки

| Элемент | Описание |
|---|---|
| `object GeoMath` | Haversine distance (метры) |
| `case class GpsPoint` | Валидированная GPS точка с `vehicleId`, `distanceTo`, JsonCodec |
| `case class VehicleConfig` | Конфиг ТС: `hasGeozones`, `hasRetranslation`, `retranslationTargets` |
| `case class DeviceData` | **Unified Redis HASH struct** — context/position/connection, `fromRedisHash`, `positionToHash`, `connectionToHash`, `connectionFieldNames` |
| `case class GpsEventMessage` | Обогащённое Kafka-сообщение: точка + routing flags (`hasGeozones`, `hasRetranslation`) |
| `case class GpsRawPoint` | Сырая GPS точка из протокола → `toValidated(vehicleId)` |
| `case class ConnectionInfo` | Информация о соединении |
| `case class Vehicle` | Инфо о ТС |
| `enum DisconnectReason` | 9 причин отключения |
| `case class DeviceStatus` | Статус устройства (online/offline + причина) |
| `case class UnknownDeviceEvent` | Событие неизвестного IMEI |
| Extensions | `GpsPoint.distance` alias |

---

### 3.2 Command.scala — 404 строки

| Элемент | Описание |
|---|---|
| `sealed trait Command` | Base: `commandId`, `imei`, `timestamp` |
| `enum CommandDelivery` | `Tcp`, `Sms`, `Auto` |
| `enum CommandStatus` | 10 вариантов: Queued → Sent → Delivered → Executed / Failed / Timeout / Rejected + Cancelled |
| Concrete commands | `RebootCommand`, `SetIntervalCommand`, `RequestPositionCommand`, `SetOutputCommand`, `CustomCommand`, `SetParameterCommand`, `PasswordCommand`, `DeviceConfigCommand`, `ChangeServerCommand` |
| `case class CommandResult` | Результат выполнения с timestamp |
| `case class CommandCapability` | Реестр возможностей (set capabilities per protocol) |
| `object CommandCapability` | `ReceiveOnly`, `Teltonika`, `Wialon`, `Ruptela`, `NavTelecom`, `Dtm` — матрица поддерживаемых команд по протоколам |

---

### 3.3 Protocol.scala — 103 строки

| Элемент | Описание |
|---|---|
| `enum Protocol` | 17 протоколов + `Unknown`: Teltonika, Wialon, WialonBinary, Ruptela, NavTelecom, GoSafe, SkySim, AutophoneMayak, MicroMayak, Dtm, TK102, TK103, Arnavi, Gtlt, Galileosky, Concox, Adm |
| `sealed trait ProtocolError` | 8 вариантов: ParseError, InsufficientData, InvalidChecksum, UnsupportedProtocol, ProtocolDetectionFailed, InvalidCommand, CommandEncodingError, CommandTimeout |
| `sealed trait FilterError` | 4 варианта: DeadReckoningRejected, InvalidSpeed, InvalidCoordinates, TimestampError |
| `sealed trait RedisError` | 3 варианта: ConnectionError, CommandError, ParseError |
| `sealed trait KafkaError` | 2 варианта: PublishError, SerializationError |

---

### 3.4 ParseError.scala — 205 строк

| Элемент | Описание |
|---|---|
| `sealed trait ParseError` | 10 конкретных ошибок: InsufficientData, InvalidField, InvalidImei, CrcMismatch, UnsupportedCodec, RecordCountMismatch, InvalidSignature, UnknownPacketType, InvalidCoordinates, GenericParseError |

Каждый вариант содержит `message`, `context (Map)`, `toJson`, `severity` (Critical/High/Medium/Low).

---

### 3.5 Vehicle.scala — 14 строк

`case class VehicleInfo(id: Long, imei: String, name: String) derives JsonCodec`

---

## 4. config/ — Конфигурация (2 файла, ~371 строка)

### 4.1 AppConfig.scala — 223 строки

| Элемент | Описание |
|---|---|
| Case classes | `TcpProtocolConfig`, `MultiProtocolPortConfig`, `TcpConfig` (17 протоколов + 5 настроек threading), `RedisConfig`, `KafkaProducerSettings`, `KafkaTopicsConfig` (10 топиков), `KafkaConsumerSettings`, `KafkaConfig`, `DeadReckoningFilterConfig`, `StationaryFilterConfig`, `FiltersConfig`, `HttpConfig`, `CommandsConfig`, `LoggingConfig`, `RateLimitConfig`, `AppConfig` |

**Ключевые паттерны:**
- zio-config-magnolia auto-derivation → `toKebabCase` для HOCON маппинга
- Explicit `given DeriveConfig[_]` instances
- `AppConfig.live` — `ZLayer.fromZIO(ZIO.config[AppConfig])`

---

### 4.2 DynamicConfigService.scala — 148 строк

| Элемент | Описание |
|---|---|
| `case class FilterConfig` | Runtime фильтры с defaults (maxSpeed=380 km/h, maxJump=50000m, stationaryDist=50m) |
| `trait DynamicConfigService` | `getFilterConfig` (~5-10ns через Ref.get), `updateFilterConfig` |
| `case class Live` | `Ref[FilterConfig]` + Redis HASH + Pub/Sub sync между инстансами |

---

## 5. protocol/ — Парсеры протоколов (20 файлов, ~4 183 строки)

### 5.1 ProtocolParser.scala — 39 строк (базовый trait)

```
protocolName: String
parseImei(buffer: ByteBuf): IO[ProtocolError, String]
parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]]
ack(recordCount: Int): ByteBuf
imeiAck(accepted: Boolean): ByteBuf
encodeCommand(command: Command): IO[ProtocolError, ByteBuf]  // default = UnsupportedProtocol
```

### 5.2 MultiProtocolParser.scala — 241 строка

Определяет протокол по magic bytes первого пакета. Рекурсивная попытка каждого из 16 парсеров с mark/reset буфера. `quickDetect` — быстрое определение без полного парсинга. `asProtocolParser` — обёртка для multi-protocol TCP порта.

**Порядок проверки:** Teltonika → NavTelecom → Ruptela → Galileosky → Concox → Adm → GoSafe → SkySim → AutophoneMayak → MicroMayak → Dtm → TK102 → TK103 → Arnavi → Gtlt → Wialon (последний — наименее уникальные magic bytes)

### 5.3 DebugProtocolParser.scala — 208 строк

Обёртка-декоратор: hex-дамп КАЖДОГО пакета (первые 1024 байт), время парсинга, каждая точка с всеми полями. Для тестового стенда.

### 5.4 WialonAdapterParser.scala — 60 строк

Auto-detection текст (0x23 `#`) vs бинарный формат Wialon. Делегирует `WialonParser` или `WialonBinaryParser`.

### 5.5 Парсеры конкретных протоколов (16 файлов)

| Парсер | Строки | Формат | Особенности |
|---|---|---|---|
| **TeltonikaParser** | 280 | Binary (Codec 8/8E) | CRC16 проверка, IO elements skip, IMEI = 2B length + ASCII |
| **WialonParser** | 167 | Text (`#L#`, `#D#`, `#SD#`, `#P#`) | DDMM.MMMM → decimal degrees, UTF-8 |
| **WialonBinaryParser** | 211 | Binary (LE) | Little-endian framing, legacy Stels формат |
| **RuptelaParser** | 212 | Binary | 2B length + 8B IMEI as long, CRC16 |
| **NavTelecomParser** | 246 | Binary (`*>` signature) | FLEX format, сложная структура |
| **GalileoskyParser** | 266 | Binary (tag-based) | 0x01 header, TLV-подобная структура, самый сложный парсер |
| **ConcoxParser** | 224 | Binary (0x7878 framing) | GL06/GT06, 2B header |
| **MicroMayakParser** | 332 | Binary | Bit-packed coordinates, `extractBitsLong` |
| **GoSafeParser** | 241 | ASCII (`$` prefix) | GPRMC-подобный текстовый |
| **AdmParser** | 225 | Binary (LE) | ADM300/ADM500, little-endian |
| **ArnaviParser** | 215 | Text (`$AV` prefix) | ASCII с semicolons |
| **DtmParser** | 213 | Binary (0x7B header) | DTM fixed-size header |
| **SkySimParser** | 167 | Binary (0xF0 header) | SkySim binary |
| **AutophoneMayakParser** | 196 | Binary (0x4D `M`) | Autophone/Mayak binary |
| **GtltParser** | 182 | Text (`*HQ` prefix) | GTLT3MT1 ASCII GPS |
| **TK102Parser** | 207 | Text (`(` framing) | TK102/TK103, 2 объекта (`tk102`, `tk103`) |

**Потенциальные проблемы в парсерах:**
- `ArnaviParser` (строки 153-154): `speedStr.toDouble.toInt` без `Option` — может бросить `NumberFormatException`
- `GtltParser` (строки 122-123, 152-153): аналогично `.toDouble` без защиты
- `MicroMayakParser` (строка 217): `.toDouble` на bit-extracted значении — safe, но неочевидно

---

## 6. command/ — Энкодеры команд (6 файлов, ~653 строки)

### 6.1 CommandEncoder.scala — 85 строк (базовый trait + фабрика)

```
trait CommandEncoder: encode, supports, capabilities
object CommandEncoder: forProtocol(name) → конкретный энкодер
case class ReceiveOnlyEncoder — заглушка для протоколов без TCP команд
```

Фабрика поддерживает: Teltonika, NavTelecom, DTM, Ruptela, Wialon. Остальные → `ReceiveOnlyEncoder`.

### 6.2 Конкретные энкодеры

| Энкодер | Строки | Поддерживаемые команды |
|---|---|---|
| **TeltonikaEncoder** | 130 | Reboot, SetInterval, RequestPosition, SetOutput, Custom, SetParameter, Password, DeviceConfig, ChangeServer |
| **RuptelaEncoder** | 153 | Reboot, SetInterval, RequestPosition, Custom |
| **NavTelecomEncoder** | 133 | Reboot, SetInterval, RequestPosition, SetOutput, Custom, ChangeServer |
| **DtmEncoder** | 90 | Reboot, SetInterval, RequestPosition, Custom |
| **WialonEncoder** | 42 | Custom (Wialon команды через `#M#` message) |

---

## 7. filter/ — Фильтры GPS точек (2 файла, ~180 строк)

### 7.1 DeadReckoningFilter.scala — 111 строк

| Элемент | Описание |
|---|---|
| `trait DeadReckoningFilter` | `validate(point)`, `validateWithPrev(point, prev)` |

Проверки: скорость (>380 km/h), координаты (lat ±90, lon ±180), timestamp (не будущее, не >30 дней назад), телепортация (>50 km между точками). Использует `DynamicConfigService` (~10ns read).

### 7.2 StationaryFilter.scala — 69 строк

| Элемент | Описание |
|---|---|
| `trait StationaryFilter` | `shouldPublish(point, prev)` → Boolean |

Определяет движение: расстояние > 50м ИЛИ скорость > 3 km/h. Использует `DynamicConfigService`.

---

## 8. service/ — Бизнес-сервисы (2 файла, ~481 строка)

### 8.1 CommandHandler.scala — 307 строк

| Элемент | Описание |
|---|---|
| `trait CommandHandler` | `start` (Kafka consumer), `handleCommand`, `processPendingCommands(imei)` |
| `case class PendingCommand` | Команда в ожидании подключения трекера |
| `case class Live` | `pendingQueueRef: Ref[Map[String, List[PendingCommand]]]` + Redis ZSET backup |

**Ключевые паттерны:**
- **Kafka Static Partition Assignment**: `instanceId` → partition (cm-instance-1→0, cm-instance-2→1, ..., cm-instance-4→3, fallback→0)
- Pending queue: in-memory `Ref[Map]` + Redis ZSET `pending_commands:{imei}` (score = timestamp)
- При reconnect: merge in-memory + Redis queues → dedup → sort by timestamp → send
- Audit events → `command-audit` Kafka topic

---

### 8.2 DeviceEventConsumer.scala — 174 строки

| Элемент | Описание |
|---|---|
| `trait DeviceEventConsumer` | `start` → Kafka Consumer Group для `device-events` |

Обрабатывает события от Device Manager (обновление конфигурации устройств). Обновляет `device:{imei}` HASH context-поля.

**TODO:** `// TODO: убрать после полной миграции на device:{imei}` — legacy `vehicle:config:{imei}`

---

## 9. api/ — HTTP API (1 файл, 470 строк)

### 9.1 HttpApi.scala — 470 строк

| Секция | Endpoints |
|---|---|
| **Monitoring** | `GET /health`, `/readiness`, `/liveness`, `/metrics` (Prometheus), `/api/stats`, `/api/version` |
| **Filters** | `GET/PUT /api/config/filters`, `POST /api/config/filters/reset` |
| **Connections** | `GET /api/connections`, `GET /api/connections/{imei}`, `DELETE /api/connections/{imei}`, `GET /api/connections/{imei}/last-position` |
| **Parsers** | `GET /api/parsers` — список с count соединений |
| **Commands** | `POST /api/commands` (generic), `POST /api/commands/reboot/{imei}`, `POST /api/commands/position/{imei}` |
| **Debug** | `GET /api/debug/redis-ping`, `/api/debug/kafka-ping`, `POST /api/debug/clear-cache` |

**TODO в debug routes:**
- `// TODO: реальный PING через RedisClient` (redis-ping)
- `// TODO: реальный metadata request через KafkaProducer` (kafka-ping)
- `// TODO: пройтись по всем ConnectionHandler и вызвать invalidateContext на stateRef` (clear-cache)

---

## 10. Main.scala — 274 строки

**Точка входа: ZIOAppDefault с полной композицией ZIO Layers.**

Последовательность запуска (8 шагов):
1. Redis ping check
2. Kafka consumers: CommandHandler + DeviceEventConsumer
3. Redis Pub/Sub listeners: CommandService + DeviceConfigListener
4. Idle Connection Watcher
5. Dynamic filter config load from Redis
6. TCP Servers: до 17 параллельных (по протоколам) + multi-protocol
7. HTTP API на сконфигурированном порту
8. `ZIO.never` — работает вечно

Debug mode: все парсеры оборачиваются в `DebugProtocolParser`.

---

## 11. Тесты (43 файла, 7 945 строк)

### Покрытие по пакетам

| Пакет | Файлов | Строк тестов | Ключевые спеки |
|---|---|---|---|
| **domain/** | 6 | 1 191 | GpsPointSpec (429), CommandSpec (280), ProtocolSpec (182), ParseErrorSpec (131), GeoMathSpec (75), VehicleInfoSpec (43) |
| **protocol/** | 18 | 2 844 | MultiProtocolParserSpec (321), WialonBinaryParserSpec (192), AdmParserSpec (160), DtmParserSpec (159), MicroMayakParserSpec (155), NavTelecomParserSpec (151), RuptelaParserSpec (149), GalileoskyParserSpec (145), TeltonikaParserSpec (143), AutophoneMayakParserSpec (142), ConcoxParserSpec (134), WialonParserSpec (131), SkySimParserSpec (113), GoSafeParserSpec (112), TK102TK103ParserSpec (111), GtltParserSpec (80), ArnaviParserSpec (77) |
| **command/** | 6 | 1 321 | TeltonikaEncoderSpec (302), NavTelecomEncoderSpec (229), RuptelaEncoderSpec (226), CommandEncoderFactorySpec (221), DtmEncoderSpec (195), WialonEncoderSpec (148) |
| **network/** | 5 | 888 | ConnectionRegistrySpec (213), CommandServiceSpec (213), IdleConnectionWatcherSpec (179), DeviceConfigListenerSpec (166), RateLimiterSpec (117) |
| **service/** | 2 | 609 | CommandHandlerSpec (338), DeviceEventConsumerSpec (271) |
| **config/** | 2 | 435 | AppConfigSpec (336), DynamicConfigServiceSpec (99) |
| **api/** | 1 | 409 | HttpApiSpec (409) |
| **filter/** | 2 | 337 | DeadReckoningFilterSpec (221), StationaryFilterSpec (116) |
| **storage/** | 2 | 331 | VehicleLookupServiceSpec (233), DeviceRepositorySpec (98) |

**Наблюдения:**
- Все 17 парсеров протоколов покрыты тестами ✅
- Все 5 энкодеров команд покрыты ✅
- Все network компоненты покрыты ✅
- HttpApi покрыт (409 строк) ✅
- `ConnectionHandler` (1002 строки, самый большой файл) — **тест отсутствует** ❌

---

## 12. Сводка TODO / потенциальных проблем

### Обычные TODO (не AI)

| Файл | TODO | Приоритет |
|---|---|---|
| ConnectionHandler:374 | Передать remoteAddress из ConnectionHandler | Средний |
| ConnectionHandler:496,536 | Убрать legacy registerConnection после миграции на `device:{imei}` | Низкий (техдолг) |
| DeviceEventConsumer:127 | Убрать legacy `vehicle:config:{imei}` после миграции | Низкий (техдолг) |
| DeviceRepository:90 | **Добавить Doobie реализацию** — только Dummy! | **Высокий** |
| HttpApi (debug routes) | Реальный Redis PING, Kafka PING, cache clear | Средний |

### Потенциальные проблемы

| Проблема | Файл | Описание | Серьёзность |
|---|---|---|---|
| NumberFormatException | ArnaviParser, GtltParser | `.toDouble.toInt` без Option/Try → runtime crash при невалидных данных | **Высокая** |
| Нет теста ConnectionHandler | test/ | 1002 строки без unit-тестов — самый критичный файл | **Высокая** |
| Dummy DeviceRepository | DeviceRepository.scala | Production реализация (Doobie) отсутствует | **Высокая** |
| Debug routes — заглушки | HttpApi.scala | redis-ping/kafka-ping/clear-cache — не реализованы | Средняя |
| Static partition mapping | CommandHandler | Hardcoded `cm-instance-1`→0 — не масштабируется | Средняя |
| @volatile var в MultiProtocolParser | MultiProtocolParser | `detectedParser` и `detectedProtocolName` — мутация в Netty thread | Низкая (thread-safe через volatile) |

---

## 13. Архитектурные паттерны

| Паттерн | Где используется |
|---|---|
| **ZIO Service Pattern** (trait + accessor + Live) | Все сервисы (20+) |
| **ZLayer DI** | Main.scala — полная композиция из ~15 layers |
| **Ref для immutable state** | ConnectionState, RateLimiter, DynamicConfigService, CommandHandler pending queue |
| **ConcurrentHashMap для hot path** | ConnectionRegistry (20K+ трекеров) |
| **AtomicLong для 0-allocation** | ConnectionEntry.lastActivityAt |
| **Cache-Aside** | VehicleLookupService (Redis → PostgreSQL → Redis) |
| **Decorator** | DebugProtocolParser wraps any ProtocolParser |
| **Strategy** | MultiProtocolParser — runtime protocol detection |
| **Factory** | CommandEncoder.forProtocol, ConnectionHandler.factory |
| **Typed errors (sealed trait)** | ProtocolError, FilterError, RedisError, KafkaError, ParseError |
| **Semaphore для ordering** | ConnectionHandler — порядок пакетов |
| **Promise для async** | CommandService — ожидание ответа от трекера |
| **acquireRelease** | RedisClient, TcpServer EventLoopGroups |

---

*Создан: 10 марта 2026*
