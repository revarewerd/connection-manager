# Connection Manager — Runbook v5.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-11` | Версия: `5.0`

## Быстрый запуск

### Первый запуск (dev)

```bash
# 1. Поднять инфраструктуру
cd wayrecall-tracker
docker-compose up -d redis kafka timescaledb

# 2. Проверить готовность
docker-compose ps
redis-cli ping       # PONG
kafkacat -b localhost:9092 -L  # metadata

# 3. Запустить CM
cd services/connection-manager
sbt run

# 4. Проверить здоровье
curl http://localhost:10090/api/health
curl http://localhost:10090/api/parsers
```

### Docker (production)

```bash
cd services/connection-manager
sbt docker:publishLocal
docker run -d \
  -e REDIS_HOST=redis \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e INSTANCE_ID=cm-prod-1 \
  -p 5001:5001 -p 5002:5002 -p 5003:5003 -p 5004:5004 \
  -p 10090:10090 \
  wayrecall/connection-manager:latest
```

---

## Диагностика

### Проверить активные соединения

```bash
curl http://localhost:10090/api/connections | jq
```

### Проверить парсеры и их порты

```bash
curl http://localhost:10090/api/parsers | jq
```

### Проверить последнюю позицию трекера

```bash
curl http://localhost:10090/api/connections/352094080055555/last-position | jq
```

### Принудительно отключить трекер

```bash
curl -X DELETE http://localhost:10090/api/connections/352094080055555
```

### Prometheus метрики (CmMetrics v6.0)

```bash
curl http://localhost:10090/api/metrics
```

Возвращает Prometheus text exposition формат с 16 метриками:

```
# HELP cm_connections_active Текущие активные TCP соединения
# TYPE cm_connections_active gauge
cm_connections_active 42

# HELP cm_connections_total Всего TCP соединений с момента старта
# TYPE cm_connections_total counter
cm_connections_total 1523

cm_packets_received_total 45678
cm_gps_points_received_total 234567
cm_gps_points_published_total 234100
cm_parse_errors_total 42
cm_kafka_publish_success_total 234100
cm_kafka_publish_errors_total 3
cm_redis_operations_total 1500
cm_unknown_devices_total 12
cm_commands_sent_total 8
cm_uptime_seconds 3600
```

**Prometheus scrape config:**
```yaml
scrape_configs:
  - job_name: 'connection-manager'
    static_configs:
      - targets: ['localhost:10090']
    metrics_path: '/api/metrics'
    scrape_interval: 15s
```

---

## Типичные проблемы

### 1. ParseError на каждый пакет

**Симптомы:** В логах `[PARSE] Parse error` на каждый DATA пакет.

**Причины:**
- Трекер подключился на неправильный порт (например, Ruptela на порт Teltonika)
- Версия прошивки трекера не поддерживается
- Повреждение данных в TCP потоке

**Диагностика:**
```bash
# Проверить протокол соединения
curl http://localhost:10090/api/connections/352094080055555 | jq .protocol

# Включить debug логирование
export LOG_LEVEL=DEBUG
```

**Решение:**
- Перенастроить трекер на правильный порт
- Использовать MultiProtocol порт (5100) для автодетекта
- Обновить прошивку трекера

### 2. Redis latency spike

**Симптомы:** Замедление IMEI-аутентификации, `[REFRESH] Redis ошибка`.

**Причины:**
- Redis перегружен другими операциями
- Сетевые проблемы

**Диагностика:**
```bash
curl http://localhost:10090/api/debug/redis-ping
redis-cli info stats | grep instantaneous_ops_per_sec
```

**Решение:**
- В v3.0 Redis используется минимально (~10K ops/day)
- Проверить что другие сервисы не делают избыточные операции
- Увеличить pool-size в конфигурации

### 3. Kafka consumer lag растёт

**Симптомы:** Команды на трекеры не выполняются, device-events не обрабатываются.

**Диагностика:**
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group connection-manager
```

**Решение:**
- Проверить количество партиций топика vs consumers
- Увеличить `max-poll-records`
- Проверить что consumer не блокируется

### 4. Трекер подключается но не аутентифицируется

**Симптомы:** `channelActive` в логах, но нет `handleImeiPacket`.

**Причины:**
- Трекер шлёт нестандартный IMEI-пакет
- Firewall блокирует часть TCP потока

**Диагностика:**
```bash
# Посмотреть raw TCP данные
tcpdump -i any port 5001 -A | head -50
```

### 5. Незарегистрированный трекер

**Симптомы:** `[HANDLER] ⚠ Незарегистрированный трекер` в логах.

**Это НЕ ошибка!** CM v3.0 принимает такие трекеры:
- GPS данные публикуются в `unknown-gps-events`
- Device Manager может автоматически зарегистрировать
- Мониторить через `unknown-devices` топик

### 6. Context TTL refresh ошибки

**Симптомы:** `[HANDLER] не удалось обновить контекст` раз в час.

**Причины:**
- Redis недоступен в момент refresh

**Решение:**
- CM продолжает работать со старыми данными
- При восстановлении Redis следующий refresh пройдёт успешно
- Критично только если изменились routing-правила устройства

---

## Мониторинг

### Ключевые метрики (Prometheus) — CmMetrics v6.0

```
cm_connections_active        — текущие TCP соединения (gauge)
cm_connections_total         — всего соединений с момента старта (counter)
cm_disconnections_total      — всего отключений (counter)
cm_packets_received_total    — пакетов принято (counter)
cm_gps_points_received_total — GPS-точек принято (counter)
cm_gps_points_published_total— GPS-точек опубликовано в Kafka (counter)
cm_parse_errors_total        — ошибок парсинга (counter)
cm_kafka_publish_errors_total— ошибок Kafka (counter)
cm_unknown_devices_total     — незарегистрированных устройств (counter)
cm_commands_sent_total       — команд отправлено (counter)
cm_uptime_seconds            — время работы (gauge)
```

### Логирование

| Уровень | Что логируется |
|---|---|
| ERROR | Ошибки Redis/Kafka, неожиданные исключения |
| WARNING | Незарегистрированные трекеры, ошибки парсинга |
| INFO | Connect/disconnect, TTL refresh, команды |
| DEBUG | Каждый GPS-пакет, фильтрация, ACK |

### Health check для мониторинга

```bash
# Bash скрипт для Nagios/Zabbix
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:10090/api/health)
if [ "$HTTP_CODE" != "200" ]; then
  echo "CRITICAL: CM health check failed"
  exit 2
fi
CONNECTIONS=$(curl -s http://localhost:10090/api/health | jq .connections)
echo "OK: $CONNECTIONS active connections"
exit 0
```

---

## Конфигурация

### Включить новый протокол

В `application.conf`:
```hocon
tcp {
  gosafe { port = 5005, enabled = true }
}
```

Или через env variable:
```bash
# TODO: добавить env overrides для enabled флагов
```

### Изменить фильтры на лету

```bash
curl -X PUT http://localhost:10090/api/config/filters \
  -H "Content-Type: application/json" \
  -d '{
    "deadReckoning": { "maxSpeedKmh": 350, "maxJumpMeters": 1500, "maxJumpSeconds": 2 },
    "stationary": { "minDistanceMeters": 30, "minSpeedKmh": 3 }
  }'
```

### Graceful Shutdown

CM обрабатывает SIGTERM/SIGINT:
1. Перестаёт принимать новые TCP соединения
2. Завершает обработку текущих пакетов
3. Публикует DISCONNECTED для всех активных соединений
4. Закрывает Redis/Kafka подключения

---

## Тюнинг производительности (100K+ соединений)

### OS уровень (Linux)

```bash
# Максимум открытых файлов (каждый TCP = 1 fd)
ulimit -n 200000

# Или в /etc/security/limits.conf:
# wayrecall soft nofile 200000
# wayrecall hard nofile 200000

# Kernel параметры
sysctl -w net.core.somaxconn=4096
sysctl -w net.ipv4.tcp_max_syn_backlog=4096
sysctl -w net.core.netdev_max_backlog=5000
sysctl -w net.ipv4.ip_local_port_range="1024 65535"
```

### Проверить Epoll/KQueue

CM v5.0 автоматически использует Epoll (Linux) или KQueue (macOS).
Проверить в логах при старте:
```
[INFO] TCP: используется Epoll native transport
```

Если в логах `NIO` вместо `Epoll` — проверить наличие `netty-transport-native-epoll` в classpath:
```bash
sbt "show dependencyClasspath" | grep epoll
```

### JVM параметры

```bash
# Рекомендуемые для 100K соединений
java -Xmx4g -Xms4g \
  -XX:+UseZGC \
  -XX:+UseStringDeduplication \
  -Dio.netty.recycler.maxCapacityPerThread=0 \
  -Dio.netty.leakDetection.level=disabled \
  -jar connection-manager.jar
```

### Мониторинг при нагрузочном тестировании

```bash
# Количество TCP соединений (Linux)
ss -s | grep estab

# Файловые дескрипторы процесса CM
ls /proc/$(pgrep -f connection-manager)/fd | wc -l

# Kafka consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group connection-manager
```

### Подробнее
- [PERFORMANCE_AUDIT_100K.md](PERFORMANCE_AUDIT_100K.md) — полный аудит
- [SCALABILITY_ISSUES.md](SCALABILITY_ISSUES.md) — история масштабирования
