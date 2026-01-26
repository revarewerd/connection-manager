# Connection Manager - Реализованные улучшения

## Обзор

После архитектурного аудита были реализованы следующие улучшения в Connection Manager для устранения выявленных пробелов.

---

## 1. ✅ Обработка повторных подключений (Reconnect Handling)

**Файл:** `ConnectionRegistry.scala`

**Проблема:** При повторном подключении трекера с тем же IMEI старое соединение оставалось открытым.

**Решение:** Метод `register()` теперь проверяет наличие существующего соединения и закрывает его:

```scala
def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] =
  for
    now <- Clock.currentTime(TimeUnit.MILLISECONDS)
    // Закрываем старое соединение если есть (reconnect case)
    oldConnection <- connectionsRef.get.map(_.get(imei))
    _ <- oldConnection match
      case Some(old) if old.ctx.channel().isActive =>
        ZIO.attempt(old.ctx.close()).catchAll(_ => ZIO.unit) *>
        ZIO.logInfo(s"Закрыто старое соединение для $imei (reconnect)")
      case _ => ZIO.unit
    // Создаём новую запись
    entry = ConnectionEntry(imei, ctx, parser, now, now)
    _ <- connectionsRef.update(_ + (imei -> entry))
  yield ()
```

---

## 2. ✅ Rate Limiting (Защита от DDoS)

**Новый файл:** `RateLimiter.scala`

**Проблема:** Отсутствовала защита от flood-атак и DDoS.

**Решение:** Реализован Token Bucket алгоритм с ограничением по IP:

```scala
trait RateLimiter:
  def tryAcquire(ip: String): UIO[Boolean]
  def getConnectionCount(ip: String): UIO[Int]
  def getStats: UIO[Map[String, Int]]
  def startCleanup: UIO[Unit]
```

**Конфигурация:** `AppConfig.scala` - добавлен `RateLimitConfig`:

```scala
final case class RateLimitConfig(
    enabled: Boolean = true,
    maxConnectionsPerIp: Int = 100,
    refillRatePerSecond: Int = 10,
    burstSize: Int = 50,
    cleanupIntervalSeconds: Int = 300
)
```

**Интеграция:** `TcpServer.scala` - добавлен `RateLimitHandler` в Netty pipeline.

---

## 3. ✅ Device Config Listener (Реакция на disable/enable)

**Новый файл:** `DeviceConfigListener.scala`

**Проблема:** Connection Manager не реагировал на изменение статуса устройства (отключение/включение).

**Решение:** Слушатель Redis Pub/Sub для паттерна `device:config:*`:

```scala
trait DeviceConfigListener:
  def start: Task[Unit]
```

При получении сообщения `device:config:{imei}:disabled`:
1. Закрывает активное TCP соединение
2. Удаляет из реестра
3. Отправляет событие в Kafka (DeviceStatus с `AdminDisconnect`)

---

## 4. ✅ Protocol Name для Unknown Device Events

**Файлы:** `ProtocolParser.scala`, `TeltonikaParser.scala`, `WialonParser.scala`, `RuptelaParser.scala`, `NavTelecomParser.scala`

**Проблема:** При получении данных от неизвестного IMEI нельзя было определить протокол.

**Решение:** Добавлено свойство `protocolName: String` во все парсеры:

```scala
trait ProtocolParser:
  def protocolName: String  // "teltonika", "wialon", "ruptela", "navtelecom"
```

---

## 5. ✅ Unknown Device Event

**Файлы:** `GpsPoint.scala`, `KafkaProducer.scala`, `AppConfig.scala`, `ConnectionHandler.scala`

**Проблема:** Подключения от неизвестных IMEI никак не логировались.

**Решение:**

1. Добавлен domain-объект `UnknownDeviceEvent`:
```scala
case class UnknownDeviceEvent(
    imei: String,
    protocol: String,
    sourceIp: String,
    port: Int,
    timestamp: Long
)
```

2. Добавлен метод `publishUnknownDevice` в `KafkaProducer`
3. Добавлен топик `unknownDevices` в `KafkaTopicsConfig`
4. Добавлен callback `onUnknownDevice` в `ConnectionHandler`

---

## 6. ✅ AdminDisconnect Reason

**Файл:** `GpsPoint.scala`

Добавлена новая причина отключения:

```scala
enum DisconnectReason:
  case AdminDisconnect  // Отключен администратором (устройство заблокировано)
```

---

## Обновлённая архитектура Main.scala

```scala
val appLayer = 
  configLayer ++ 
  tcpServerLayer ++           // С Rate Limiter
  processingServiceLayer ++ 
  registryLayer ++ 
  commandServiceLayer ++ 
  dynamicConfigLayer ++ 
  idleWatcherLayer ++ 
  deviceConfigListenerLayer ++  // NEW: слушатель конфигурации
  rateLimiterLayer              // NEW: rate limiter
```

---

## Что осталось сделать

### Средний приоритет:

1. **VehicleLookupService** (Redis → PostgreSQL fallback)
   - Файлы созданы: `DeviceRepository.scala`, `VehicleLookupService.scala`
   - Нужно: интегрировать в `ConnectionHandler` и `GpsProcessingService`

2. **PostgreSQL connector**
   - Добавить зависимость на PostgreSQL driver (doobie или zio-quill)
   - Реализовать `DeviceRepository.Live`

### Низкий приоритет:

3. **Graceful shutdown улучшения**
   - Уведомление Device Manager об отключении всех соединений

4. **Метрики и мониторинг**
   - Prometheus metrics для rate limiter
   - Количество unknown device events

---

## Конфигурация (application.conf)

Добавить в конфигурацию:

```hocon
connection-manager {
  rate-limit {
    enabled = true
    max-connections-per-ip = 100
    refill-rate-per-second = 10
    burst-size = 50
    cleanup-interval-seconds = 300
  }
  
  kafka {
    topics {
      unknown-devices = "unknown-devices"
    }
  }
}
```

---

## Компиляция

✅ Проект успешно компилируется:
```
sbt compile
[success] Total time: 49 s
```
