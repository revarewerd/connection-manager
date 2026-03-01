# Connection Manager — HTTP API v3.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `3.0`

**Порт:** 10090  
**Базовый URL:** `http://localhost:10090/api`

---

## Система мониторинга

### GET /api/health

Health check — проверка работоспособности.

**Response:**
```json
{
  "status": "ok",
  "timestamp": 1709290000000,
  "connections": 42,
  "uptime": 86400
}
```

### GET /api/health/readiness

K8s Readiness Probe — проверяет зависимости (Redis, Kafka).

**Response:**
```json
{
  "ready": true,
  "redis": true,
  "kafka": true
}
```

### GET /api/health/liveness

K8s Liveness Probe — быстрая проверка.

**Response:**
```json
{"alive": true}
```

### GET /api/metrics

Prometheus-совместимые метрики в text exposition format.

**Response (text/plain):**
```
# HELP cm_connections_active Количество активных TCP соединений
# TYPE cm_connections_active gauge
cm_connections_active 42

# HELP cm_uptime_seconds Время работы сервиса в секундах
# TYPE cm_uptime_seconds gauge
cm_uptime_seconds 86400
```

### GET /api/stats

Детальная статистика работы сервиса.

**Response:**
```json
{
  "totalConnections": 42,
  "totalPacketsReceived": 1250000,
  "totalPointsFiltered": 15000,
  "totalPointsPublished": 1235000,
  "uptimeSeconds": 86400
}
```

### GET /api/version

Информация о версии сервиса.

**Response:**
```json
{
  "service": "connection-manager",
  "version": "3.0.0",
  "scalaVersion": "3.4.0",
  "instanceId": "cm-instance-1",
  "startedAt": 1709200000000
}
```

---

## Управление фильтрами

### GET /api/config/filters

Текущая конфигурация фильтров (Dead Reckoning + Stationary).

**Response:**
```json
{
  "deadReckoning": {
    "maxSpeedKmh": 300,
    "maxJumpMeters": 1000,
    "maxJumpSeconds": 1
  },
  "stationary": {
    "minDistanceMeters": 20,
    "minSpeedKmh": 2
  }
}
```

### PUT /api/config/filters

Обновить конфигурацию фильтров (Dynamic — через Redis Pub/Sub sync).

**Request:**
```json
{
  "deadReckoning": {
    "maxSpeedKmh": 350,
    "maxJumpMeters": 1500,
    "maxJumpSeconds": 2
  },
  "stationary": {
    "minDistanceMeters": 30,
    "minSpeedKmh": 3
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": "Configuration updated",
  "error": null
}
```

### POST /api/config/filters/reset

Сбросить фильтры на defaults из application.conf.

**Response:**
```json
{
  "success": true,
  "data": "Filters reset to defaults",
  "error": null
}
```

---

## Управление соединениями

### GET /api/connections

Список всех активных TCP-соединений.

**Response:**
```json
[
  {
    "imei": "352094080055555",
    "connectedAt": 1709280000000,
    "remoteAddress": "/192.168.1.100:45678",
    "protocol": "teltonika",
    "lastActivity": 1709290000000
  }
]
```

### GET /api/connections/:imei

Детали конкретного соединения.

**Response:**
```json
{
  "imei": "352094080055555",
  "connectedAt": 1709280000000,
  "remoteAddress": "/192.168.1.100:45678",
  "protocol": "teltonika",
  "lastActivity": 1709290000000,
  "packetsReceived": 1500,
  "pointsAccepted": 1450
}
```

### DELETE /api/connections/:imei

Принудительно отключить трекер.

**Response:**
```json
{
  "success": true,
  "data": "Disconnected: 352094080055555",
  "error": null
}
```

### GET /api/connections/:imei/last-position

Последняя GPS позиция из in-memory кэша.

**Response:**
```json
{
  "imei": "352094080055555",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "speed": 45.0,
  "angle": 180,
  "timestamp": 1709290000000,
  "satellites": 12
}
```

---

## Парсеры

### GET /api/parsers

Список всех парсеров с информацией о портах и количестве соединений.

**Response:**
```json
[
  {"protocol": "teltonika", "port": 5001, "enabled": true, "connectionsCount": 25},
  {"protocol": "wialon", "port": 5002, "enabled": true, "connectionsCount": 10},
  {"protocol": "ruptela", "port": 5003, "enabled": true, "connectionsCount": 5},
  {"protocol": "navtelecom", "port": 5004, "enabled": true, "connectionsCount": 2},
  {"protocol": "gosafe", "port": 5005, "enabled": false, "connectionsCount": 0},
  {"protocol": "skysim", "port": 5006, "enabled": false, "connectionsCount": 0},
  {"protocol": "autophone-mayak", "port": 5007, "enabled": false, "connectionsCount": 0},
  {"protocol": "dtm", "port": 5008, "enabled": false, "connectionsCount": 0},
  {"protocol": "multi", "port": 5100, "enabled": false, "connectionsCount": 0}
]
```

---

## Команды

### POST /api/commands

Отправить произвольную команду на трекер.

**Request:**
```json
{
  "commandType": "setInterval",
  "imei": "352094080055555",
  "params": {"interval": 30}
}
```

### POST /api/commands/reboot/:imei

Quick reboot — перезагрузить трекер.

### POST /api/commands/position/:imei

Quick position — запросить текущую позицию.

---

## Отладка

### GET /api/debug/redis-ping

Проверка доступности Redis.

### GET /api/debug/kafka-ping

Проверка доступности Kafka.

### POST /api/debug/clear-cache

Принудительная инвалидация всех in-memory кэшей ConnectionState.

---

## Коды ответов

| Код | Описание |
|---|---|
| 200 | Успешный запрос |
| 400 | Невалидный JSON / параметры |
| 404 | Соединение / устройство не найдено |
| 500 | Внутренняя ошибка сервера |

## Формат ошибок

```json
{
  "success": false,
  "data": null,
  "error": "описание ошибки"
}
```
