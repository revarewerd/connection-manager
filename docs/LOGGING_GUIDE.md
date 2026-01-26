# Система логирования Connection Manager

## Куда идут логи?

Connection Manager использует **ZIO Logging** с бэкендом **SLF4J**, который перенаправляет логи в **Logback**.

### Конфигурация в Main.scala:
```scala
override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
  Runtime.removeDefaultLoggers >>> SLF4J.slf4j
```

### Места вывода логов:

1. **Консоль (stdout)** - по умолчанию все логи выводятся в консоль
2. **Файл** - если настроен в `src/main/resources/logback.xml`
3. **Docker logs** - при запуске в Docker логи идут в stdout, который Docker собирает

### Пример logback.xml:
```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/connection-manager.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/connection-manager.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  
  <root level="INFO">
    <appender-ref ref="CONSOLE" />
    <appender-ref ref="FILE" />
  </root>
  
  <!-- Включить DEBUG для нашего кода -->
  <logger name="com.wayrecall.tracker" level="DEBUG" />
</configuration>
```

---

## Уровни логирования

| Уровень | Когда использовать | Пример |
|---------|-------------------|--------|
| **ERROR** | Критические ошибки, требующие внимания | Ошибка подключения к Redis/Kafka |
| **WARN** | Важные события, но не ошибки | Неизвестное устройство, rate limit |
| **INFO** | Ключевые бизнес-события | Подключение/отключение устройства |
| **DEBUG** | Детали для отладки | Шаги обработки пакетов |

---

## Теги логов

Все логи имеют теги для фильтрации:

| Тег | Компонент | Описание |
|-----|-----------|----------|
| `[AUTH]` | GpsProcessingService | Аутентификация по IMEI |
| `[DATA]` | GpsProcessingService | Обработка GPS данных |
| `[FILTER]` | DeadReckoning/Stationary | Фильтрация точек |
| `[REDIS]` | RedisClient | Операции с Redis |
| `[KAFKA]` | KafkaProducer | Публикация событий |
| `[CONNECT]` | GpsProcessingService | Подключение устройства |
| `[DISCONNECT]` | GpsProcessingService | Отключение устройства |
| `[UNKNOWN]` | GpsProcessingService | Неизвестные устройства |
| `[HANDLER]` | ConnectionHandler | TCP обработчик Netty |
| `[REGISTRY]` | ConnectionRegistry | Реестр соединений |
| `[RATE_LIMIT]` | RateLimiter | Защита от flood |

---

## Примеры логов

### Успешное подключение (INFO):
```
14:32:15.123 [worker-1] INFO  - [HANDLER] → Новое TCP соединение от /192.168.1.100:54321
14:32:15.145 [worker-1] INFO  - [AUTH] ✓ Устройство аутентифицировано: IMEI=123456789012345 → vehicleId=42
14:32:15.167 [worker-1] INFO  - [REGISTRY] ✓ Соединение зарегистрировано: IMEI=123456789012345, всего активных: 15
14:32:15.189 [worker-1] INFO  - [CONNECT] ✓ Устройство подключено: IMEI=123456789012345, vehicleId=42, адрес=192.168.1.100:54321
```

### Неизвестное устройство (WARN):
```
14:35:22.456 [worker-2] WARN  - [HANDLER] ✗ Неизвестное устройство: IMEI=999888777666555, адрес=10.0.0.50
14:35:22.478 [worker-2] WARN  - [UNKNOWN] ⚠ Попытка подключения неизвестного устройства: IMEI=999888777666555, протокол=teltonika, адрес=10.0.0.50:12345
```

### Отключение устройства (INFO):
```
14:40:00.789 [worker-1] INFO  - [DISCONNECT] ✗ Устройство отключено: IMEI=123456789012345, причина=таймаут неактивности, длительность сессии=457с
14:40:00.801 [worker-1] INFO  - [REGISTRY] ✗ Соединение удалено: IMEI=123456789012345, всего активных: 14
```

### Rate limit (WARN):
```
14:42:33.123 [worker-3] WARN  - [RATE_LIMIT] ⛔ Превышен лимит соединений для IP: 192.168.1.200 (макс: 100 за 60с)
```

### Ошибка Redis (ERROR):
```
14:45:00.456 [worker-1] ERROR - [AUTH] Ошибка Redis при поиске vehicleId: Connection refused
```

---

## Фильтрация логов в production

### Показать только INFO и выше:
```bash
docker logs connection-manager 2>&1 | grep -E "INFO|WARN|ERROR"
```

### Показать только ошибки:
```bash
docker logs connection-manager 2>&1 | grep ERROR
```

### Показать логи конкретного IMEI:
```bash
docker logs connection-manager 2>&1 | grep "IMEI=123456789012345"
```

### Показать все подключения/отключения:
```bash
docker logs connection-manager 2>&1 | grep -E "\[CONNECT\]|\[DISCONNECT\]"
```

### Показать неизвестные устройства:
```bash
docker logs connection-manager 2>&1 | grep "\[UNKNOWN\]"
```

---

## Интеграция с ELK Stack

Для production рекомендуется настроить отправку логов в Elasticsearch:

1. **Filebeat** - собирает логи из файлов/Docker
2. **Logstash** - парсит и обогащает данные
3. **Elasticsearch** - хранит и индексирует
4. **Kibana** - визуализация и поиск

### Пример Filebeat конфигурации:
```yaml
filebeat.inputs:
- type: container
  paths:
    - '/var/lib/docker/containers/*/*.log'

output.elasticsearch:
  hosts: ["elasticsearch:9200"]
  index: "tracker-logs-%{+yyyy.MM.dd}"
```

---

## Метрики из логов

Логи можно парсить для получения метрик:
- Количество подключений/отключений в час
- Среднее время сессии
- Топ IP по rate limit
- Количество неизвестных устройств
