# Connection Manager Service

GPS трекинг сервис для обработки данных от GPS трекеров на **Scala 3.4 + ZIO 2.0**.

## 🎯 Возможности

- Persistent TCP соединения с GPS трекерами (1000-5000 подключений)
- **4 протокола:** Teltonika Codec 8/8E, Wialon IPS, Ruptela, NavTelecom
- **Отправка команд** на трекеры через Redis Pub/Sub
- **Динамическая конфигурация** фильтров без перезапуска
- HTTP API для мониторинга и управления
- Парсинг GPS координат и метаданных
- Фильтрация невалидных точек (Dead Reckoning Filter)
- Фильтрация стационарных точек (Stationary Filter)
- Публикация событий в Kafka
- Кеширование позиций в Redis

**Latency target:** 1-5ms на обработку GPS точки

## 📦 Структура проекта

```
connection-manager/
├── build.sbt
├── project/
│   └── build.properties
├── src/
│   ├── main/
│   │   ├── scala/com/wayrecall/tracker/
│   │   │   ├── Main.scala                 # Точка входа
│   │   │   ├── api/
│   │   │   │   └── HttpApi.scala          # HTTP API
│   │   │   ├── config/
│   │   │   │   ├── AppConfig.scala        # Статическая конфигурация
│   │   │   │   └── DynamicConfigService.scala # Динамическая конфигурация
│   │   │   ├── domain/
│   │   │   │   ├── GpsPoint.scala         # GPS точка
│   │   │   │   ├── Command.scala          # Команды на трекеры
│   │   │   │   ├── Vehicle.scala          # Транспорт
│   │   │   │   └── Protocol.scala         # Enums и ошибки
│   │   │   ├── network/
│   │   │   │   ├── TcpServer.scala        # Netty TCP сервер
│   │   │   │   ├── ConnectionHandler.scala # Обработчик соединений
│   │   │   │   ├── ConnectionRegistry.scala # Реестр соединений
│   │   │   │   └── CommandService.scala   # Сервис команд
│   │   │   ├── protocol/
│   │   │   │   ├── ProtocolParser.scala   # Интерфейс парсеров
│   │   │   │   ├── TeltonikaParser.scala  # Teltonika Codec 8/8E/12
│   │   │   │   ├── WialonParser.scala     # Wialon IPS
│   │   │   │   ├── RuptelaParser.scala    # Ruptela
│   │   │   │   └── NavTelecomParser.scala # NavTelecom FLEX
│   │   │   ├── filter/
│   │   │   │   ├── DeadReckoningFilter.scala # Валидация координат
│   │   │   │   └── StationaryFilter.scala    # Фильтр стоянок
│   │   │   └── storage/
│   │   │       ├── RedisClient.scala      # Redis клиент + Pub/Sub
│   │   │       └── KafkaProducer.scala    # Kafka продюсер
│   │   └── resources/
│   │       ├── application.conf           # Конфигурация
│   │       └── logback.xml                # Логирование
│   └── test/
│       └── scala/com/wayrecall/tracker/
│           ├── protocol/
│           │   └── TeltonikaParserSpec.scala
│           └── filter/
│               └── StationaryFilterSpec.scala
└── README.md
```

## 🚀 Запуск

### Требования

- JDK 17+
- SBT 1.9+
- Redis (localhost:6379)
- Kafka (localhost:9092)

### Запуск сервиса

```bash
cd connection-manager
sbt run
```

### Запуск тестов

```bash
sbt test
```

## 🌐 HTTP API

```bash
# Health check
curl http://localhost:8080/api/health

# Получить конфигурацию фильтров
curl http://localhost:8080/api/config/filters

# Обновить конфигурацию фильтров (без перезапуска!)
curl -X PUT http://localhost:8080/api/config/filters \
  -H "Content-Type: application/json" \
  -d '{
    "deadReckoningMaxSpeedKmh": 250,
    "stationaryMinDistanceMeters": 30
  }'

# Список активных соединений
curl http://localhost:8080/api/connections

# Отправить команду на трекер
curl -X POST http://localhost:8080/api/commands/reboot/352093082745395

# Запросить текущую позицию
curl -X POST http://localhost:8080/api/commands/position/352093082745395
```

## 📊 Redis Keys Schema

| Key Pattern | Type | TTL | Описание | Пример значения |
|------------|------|-----|----------|-----------------|
| `vehicle:{imei}` | String | 1h | IMEI → vehicle_id | `"42"` |
| `position:{vehicle_id}` | String (JSON) | 1h | Последняя позиция | `{"vehicleId":42,...}` |
| `connection:{imei}` | String (JSON) | - | Информация о подключении | `{"imei":"352...",...}` |
| `config:filters` | Hash | - | Динамические настройки фильтров | См. ниже |
| `commands:{imei}` | Pub/Sub | - | Команды на трекер | JSON команды |
| `command-results:{imei}` | Pub/Sub | - | Результаты команд | JSON результаты |

## ⚙️ Конфигурация

Конфигурация находится в `src/main/resources/application.conf`:

```hocon
connection-manager {
  tcp {
    teltonika { port = 5001, enabled = true }
    wialon { port = 5002, enabled = true }
    ruptela { port = 5003, enabled = true }
    navtelecom { port = 5004, enabled = true }
    boss-threads = 1
    worker-threads = 4
    max-connections = 5000
  }
  
  http {
    port = 8080
  }
  
  redis {
    host = "localhost"
    port = 6379
    pool-size = 10
  }
  
  kafka {
    bootstrap-servers = "localhost:9092"
    topics {
      raw-gps-events = "raw-gps-events"
      device-status = "device-status"
    }
  }
  
  filters {
    dead-reckoning {
      max-speed-kmh = 300
      max-jump-meters = 1000
    }
    stationary {
      min-distance-meters = 20
      min-speed-kmh = 2
    }
  }
}
```

## 📊 Data Flow

```
TCP → Parse → DeadReckoningFilter → IMEI Validation (Redis) 
  → StationaryFilter (in-memory) → Redis SET 
  → Kafka PRODUCE (если движется) → ACK трекеру
```

### Ключевые решения

- **In-memory cache** для предыдущих позиций (низкая latency)
- Redis GET только **1 раз** при подключении (прогрев кеша)
- Redis SET **всегда** (для фронтенда)
- Kafka PRODUCE **только если движется** (~70% фильтруется)

## 🧪 Тестирование

```bash
# Отправить тестовый пакет на Teltonika порт
nc localhost 5001

# Проверить Redis
redis-cli GET "position:42"

# Проверить Kafka
kafka-console-consumer --bootstrap-server localhost:9092 --topic raw-gps-events
```

## 📝 Протокол Teltonika

### Формат IMEI пакета
```
[2B length][IMEI string 15B]
```

### Формат AVL пакета
```
[Preamble 4B][Data Length 4B][Codec ID 1B][Records 1B][AVL Data][Records 1B][CRC 4B]
```

### AVL Record
```
[Timestamp 8B][Priority 1B][Longitude 4B][Latitude 4B][Altitude 2B]
[Angle 2B][Satellites 1B][Speed 2B][IO Elements]
```

### CRC
- Алгоритм: CRC-16-IBM (polynomial 0xA001)

## 📚 Технологии

- **Scala 3.4** - язык программирования
- **ZIO 2.0** - эффекты и конкурентность
- **Netty 4.1** - TCP сервер
- **Lettuce 6.3** - Redis клиент
- **Kafka Clients 3.6** - Kafka продюсер
- **zio-json** - JSON сериализация
- **zio-test** - тестирование

## 📄 Лицензия

MIT
