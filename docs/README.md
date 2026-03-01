# Connection Manager (CM) v4.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `4.0`

## Описание

Connection Manager — центральный TCP-сервис системы Wayrecall Tracker.  
Принимает сырые GPS-пакеты от трекеров различных производителей по TCP,
парсит их протокол-специфичными парсерами, фильтрует аномалии и публикует
GPS-события в Kafka. Поддерживает отправку команд на трекеры через 5 энкодеров.

**Ключевые характеристики v4.0:**
- **18 GPS-протоколов** — Teltonika, Wialon (3 варианта), Ruptela, NavTelecom, GoSafe, SkySim, AutophoneMayak, DTM, Galileosky, Concox GT06, TK102/103, Arnavi, ADM, Queclink GTLT, МикроМаяк
- **10 типов команд** — Reboot, SetInterval, RequestPosition, SetOutput, SetParameter, Password, DeviceConfig, ChangeServer, Custom
- **5 Command Encoders** — Teltonika (Codec 12), NavTelecom (NTCB FLEX), DTM (binary), Ruptela (binary 0x65-0x67), Wialon (text #M#)
- **MultiProtocolParser** — автодетект протокола по magic bytes первого пакета
- **In-memory кэш** вместо Redis на горячем пути (~864M → ~10K Redis ops/day)
- **10 Kafka-топиков** — включая gps-parse-errors (NEW) для мониторинга
- **ВСЕ точки → gps-events** — валидные, отфильтрованные, стоянки (с маркерами isValid/isMoving)
- **HTTP API** (порт 10090) с 20+ эндпоинтами: health, K8s probes, Prometheus metrics, управление

## Быстрый старт

### Запуск инфраструктуры

```bash
cd wayrecall-tracker
docker-compose up -d redis kafka timescaledb
```

### Запуск CM

```bash
cd services/connection-manager
sbt run
```

### Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `INSTANCE_ID` | `cm-instance-1` | Уникальный ID для Kafka partition |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | `null` | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `HTTP_PORT` | `10090` | HTTP API порт |
| `LOG_LEVEL` | `INFO` | Уровень логирования |
| `TCP_IDLE_TIMEOUT` | `300` | Idle timeout (секунды) |

### Проверка здоровья

```bash
curl http://localhost:10090/api/health
curl http://localhost:10090/api/version
curl http://localhost:10090/api/parsers
curl http://localhost:10090/api/connections
```

## TCP-порты протоколов

| # | Протокол | Порт | Enabled | Команды | Описание |
|---|---|---|---|---|---|
| 1 | Teltonika | 5001 | ✅ | ✅ 7 | Codec 8/8E, binary, CRC-16-IBM |
| 2 | Wialon | 5002 | ✅ | ✅ 1 | IPS text (#L#, #D#, #SD#) |
| 3 | Ruptela | 5003 | ✅ | ✅ 6 | Binary, coords ×10^7, CRC-16 |
| 4 | NavTelecom | 5004 | ✅ | ✅ 3 | FLEX binary, LE, "*>" signature |
| 5 | GoSafe | 5005 | ❌ | — | ASCII GPRMC-подобный |
| 6 | SkySim | 5006 | ❌ | — | Binary, header 0xF0, Motorola |
| 7 | AutophoneMayak | 5007 | ❌ | — | Binary, header 0x4D, LE |
| 8 | DTM | 5008 | ❌ | ✅ 1 | Binary, header 0x7B, IOSwitch |
| 9 | Galileosky | 5009 | ❌ | — | Binary LE, tag-based, CRC-16 |
| 10 | Concox GT06 | 5010 | ❌ | — | Binary BE, BCD IMEI, CRC-ITU |
| 11 | TK102/TK103 | 5011 | ❌ | — | Text ASCII, GPRMC sentence |
| 12 | Arnavi | 5012 | ❌ | — | Binary LE, float32 coords |
| 13 | ADM | 5013 | ❌ | — | Binary, ASCII IMEI, XOR CRC |
| 14 | Queclink GTLT | 5014 | ❌ | — | Text ASCII, +RESP:GT format |
| 15 | МикроМаяк | 5015 | ❌ | — | Binary LE, int32 coords |
| 16 | **Multi** | 5100 | ❌ | (делегирует) | AutoDetect из 16 парсеров |

## Документация

| Файл | Описание |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Внутренняя архитектура, Mermaid-диаграммы (10+) |
| [API.md](API.md) | HTTP API (20+ endpoints) |
| [PROTOCOLS.md](PROTOCOLS.md) | Все 18 GPS-протоколов: форматы, парсинг, команды |
| [DATA_MODEL.md](DATA_MODEL.md) | Redis ключи, Kafka сообщения, доменные модели |
| [KAFKA.md](KAFKA.md) | 10 produce + 2 consume топиков, маршрутизация |
| [DECISIONS.md](DECISIONS.md) | 11 ADR — принятые решения v4.0 |
| [RUNBOOK.md](RUNBOOK.md) | Запуск, дебаг, типичные ошибки |
| [INDEX.md](INDEX.md) | Содержание документации |

## Зависимости

- **Redis 7.0** — контекст устройств, реестр соединений, Pub/Sub команды
- **Kafka 3.4+** — публикация GPS-событий, потребление команд
- **TimescaleDB** — (через History Writer) запись GPS-истории
- **PostgreSQL 15** — master data (через Device Manager API)

## Сборка

```bash
sbt compile          # Компиляция
sbt test             # Тесты
sbt assembly          # Fat JAR
sbt docker:publishLocal  # Docker image
```
