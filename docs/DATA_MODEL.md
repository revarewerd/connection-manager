# Connection Manager — Модель данных v4.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `4.0`

## Redis ключи

> Полное описание всех Redis ключей → [infra/redis/](../../../infra/redis/)

### Используемые ключи

| Ключ | Тип | TTL | Операция | Когда |
|---|---|---|---|---|
| `device:{imei}` | HASH | — | HGETALL | При IMEI-аутентификации + refresh по TTL (1 час) |
| `connection:{instanceId}:{imei}` | HASH | — | HMSET | При onConnect |
| `connection:{instanceId}:{imei}` | HASH | — | HDEL | При onDisconnect |
| `connections:active` | SET | — | SADD/SREM | Регистрация/разрегистрация |
| `commands:{imei}` | LIST | 24h | LPUSH/RPOP | Очередь pending-команд |
| `config:filters` | HASH | — | HGETALL/HMSET | Динамическая конфигурация |

### Структура `device:{imei}` HASH

```
vehicleId       → "12345"
organizationId  → "100"
name            → "Toyota Camry АА777А"
hasGeozones     → "true"
hasRetranslation → "false"
previousLat     → "55.7558"
previousLon     → "37.6173"
previousSpeed   → "45.0"
previousTime    → "1709290000000"
```

### Структура `connection:{instanceId}:{imei}` HASH

```
connectedAt     → "1709280000000"
remoteAddress   → "192.168.1.100:45678"
protocol        → "teltonika"
instanceId      → "cm-instance-1"
```

---

## In-Memory кэш (ConnectionState)

**Замена Redis на горячем пути (v3.0+):**

| Поле | Тип | Источник | Обновление |
|---|---|---|---|
| `imei` | `Option[String]` | IMEI-пакет | 1 раз (аутентификация) |
| `vehicleId` | `Option[Long]` | Redis HASH | 1 раз (аутентификация) |
| `lastPosition` | `Option[GpsPoint]` | Предыдущий GPS-пакет | Каждый пакет (in-memory) |
| `deviceData` | `Option[DeviceData]` | Redis HASH | 1 раз + refresh по TTL (1 час) |
| `detectedProtocol` | `Option[Protocol]` | MultiProtocolParser | 1 раз (автодетект) |
| `contextCachedAt` | `Long` | System.currentTimeMillis | При загрузке/refresh |
| `contextCacheTtlMs` | `Long` | Конфигурация (3_600_000) | Константа |

---

## Kafka топики — Сообщения

> Полное описание всех топиков → [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md)

### Публикует (Produce) — 10 топиков

| Топик | Ключ | Модель | Когда |
|---|---|---|---|
| `gps-events` | `vehicleId` | GpsEventMessage | **ВСЕ GPS-точки** (valid + invalid + stationary) ★ v4.0 |
| `gps-events-rules` | `vehicleId` | RuleCheckEvent | Если moving + hasGeozones/hasSpeedRules |
| `gps-events-retranslation` | `vehicleId` | RetranslationEvent | Если moving + hasRetranslation |
| `gps-parse-errors` | `imei` | GpsParseErrorEvent | При ошибке парсинга ★ NEW v4.0 |
| `device-status` | `imei` | ConnectionEvent | При connect/disconnect |
| `command-audit` | `imei` | CommandAuditEvent | При отправке/получении команды |
| `unknown-devices` | `imei` | UnknownDeviceEvent | При подключении неизвестного IMEI |
| `unknown-gps-events` | `imei` | UnknownGpsPoint | GPS от незарег. трекера |

### Потребляет (Consume) — 2 топика

| Топик | Consumer Group | Модель | Действие |
|---|---|---|---|
| `device-commands` | `connection-manager` | DeviceCommand | Отправить команду через CommandEncoder |
| `device-events` | `connection-manager` | DeviceEvent | Обновить in-memory кэш |

---

## Модели Kafka-сообщений

### GpsEventMessage ★ ОБНОВЛЕНО v4.0

> Теперь все точки попадают в gps-events. Флаги `isValid` и `isMoving` определяют
> тип точки. History Writer записывает все; Rule Checker работает только с moving.

```json
{
  "vehicleId": 12345,
  "imei": "352094080055555",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "altitude": 150.0,
  "speed": 45.0,
  "angle": 180,
  "satellites": 12,
  "deviceTime": 1709290000000,
  "serverTime": 1709290000050,
  "isMoving": true,
  "isValid": true,
  "protocol": "teltonika",
  "instanceId": "cm-instance-1",
  "organizationId": 100,
  "deviceName": "Toyota Camry"
}
```

**Семантика флагов:**

| isValid | isMoving | Происхождение |
|---|---|---|
| `true` | `true` | Прошла DR + Stationary → нормальное движение |
| `true` | `false` | Прошла DR, не прошла Stationary → стоянка |
| `false` | `false` | Dead Reckoning отфильтровала → невалидная |

### GpsParseErrorEvent ★ NEW v4.0

```json
{
  "imei": "352094080055555",
  "protocol": "teltonika",
  "errorType": "InvalidChecksum",
  "errorMessage": "CRC mismatch: expected 0xA1B2, got 0xC3D4",
  "rawPacketHex": "000f333532303934303830303535353535...",
  "rawPacketSize": 128,
  "remoteAddress": "192.168.1.100:45678",
  "instanceId": "cm-instance-1",
  "timestamp": 1709290000050
}
```

**Типы ошибок (errorType):**
- `InvalidChecksum` — CRC не совпала
- `InvalidCodec` — неподдерживаемый codec ID
- `ParseError` — общая ошибка парсинга
- `InsufficientData` — недостаточно данных в буфере
- `InvalidImei` — некорректный формат IMEI
- `UnknownDevice` — устройство не найдено
- `UnsupportedProtocol` — протокол не поддерживается
- `ProtocolDetectionFailed` — MultiProtocol не смог определить

### UnknownGpsPoint

```json
{
  "imei": "999999999999999",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "altitude": 150.0,
  "speed": 45.0,
  "angle": 180,
  "satellites": 12,
  "deviceTime": 1709290000000,
  "serverTime": 1709290000050,
  "protocol": "teltonika",
  "instanceId": "cm-instance-1"
}
```

### ConnectionEvent (device-status)

```json
{
  "imei": "352094080055555",
  "vehicleId": 12345,
  "eventType": "CONNECTED",
  "protocol": "teltonika",
  "instanceId": "cm-instance-1",
  "remoteAddress": "192.168.1.100:45678",
  "timestamp": 1709280000000
}
```

---

## Доменные модели v4.0

### Command (10 типов) ★ REWRITTEN v4.0

```scala
sealed trait Command:
  def commandId: String
  def imei: String
  def delivery: CommandDelivery
  def priority: Int

enum CommandDelivery:
  case Tcp, Sms, Auto

// 10 типов команд:
case class RebootCommand(commandId, imei, delivery, priority)
case class SetIntervalCommand(commandId, imei, delivery, priority, intervalSeconds: Int)
case class RequestPositionCommand(commandId, imei, delivery, priority)
case class SetOutputCommand(commandId, imei, delivery, priority, outputIndex: Int, value: Boolean)
case class SetParameterCommand(commandId, imei, delivery, priority, parameterId: Int, parameterValue: String)
case class PasswordCommand(commandId, imei, delivery, priority, password: String)
case class DeviceConfigCommand(commandId, imei, delivery, priority, configData: String)
case class ChangeServerCommand(commandId, imei, delivery, priority, serverHost: String, serverPort: Int)
case class CustomCommand(commandId, imei, delivery, priority, commandText: String)
```

### CommandCapability

```scala
case class CommandCapability(
  protocol: String,
  supportedCommands: Set[String],
  tcpCommands: Set[String],
  smsCommands: Set[String],
  requiresAuth: Boolean
)
```

### CommandStatus (10 состояний)

```scala
enum CommandStatus:
  case Pending, Sent, Delivered, Acked, Failed,
       Timeout, Queued, Rejected, Cancelled, Expired
```

### PendingCommand (domain)

```scala
case class PendingCommand(
  command: Command,
  status: CommandStatus,
  createdAt: Instant,
  lastAttemptAt: Option[Instant],
  lastError: Option[String],
  attemptCount: Int
)
```

### AwaitingCommand (network, internal)

```scala
// Внутренняя структура CommandService для отслеживания TCP-ответов
private[network] case class AwaitingCommand(
  command: Command,
  promise: Promise[ProtocolError, CommandResult],
  sentAt: Instant
)
```
