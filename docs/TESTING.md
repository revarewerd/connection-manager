> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-02` | Версия: `1.0`

# Connection Manager — TESTING.md

## Обзор

Все тесты написаны на **ZIO Test** (`ZIOSpecDefault`).
Запуск: `sbt test` из директории `services/connection-manager/`.

**Общая статистика:** 286+ тестов, 0 failures

---

## Тестовые модули

### 1. Парсеры протоколов (16 файлов, ~140 тестов)

Расположение: `src/test/scala/com/wayrecall/tracker/protocol/`

| Файл | Протокол | Что тестируется |
|---|---|---|
| `TeltonikaParserSpec` | Teltonika Codec 8/8E | Парсинг бинарного пакета, извлечение координат, IO elements, CRC |
| `WialonParserSpec` | Wialon IPS | Текстовый протокол, формат `#D#`, `#L#`, валидация IMEI |
| `WialonBinaryParserSpec` | Wialon Binary | Бинарный вариант Wialon |
| `RuptelaParserSpec` | Ruptela | Бинарный протокол, CRC-16 |
| `NavTelecomParserSpec` | NavTelecom FLEX | NTCB заголовок, flexible data, CRC |
| `GalileoskyParserSpec` | Galileosky | Теги данных, сжатие |
| `DtmParserSpec` | DTM / Queclink | AT-подобный протокол, IOSwitch |
| `ConcoxParserSpec` | Concox / JM-VL | Бинарный, login/GPS/heartbeat пакеты |
| `GoSafeParserSpec` | GoSafe | Текстовый, CSV-подобный формат |
| `GtltParserSpec` | GT06 / GTLT | Бинарный, login + location пакеты |
| `SkySimParserSpec` | SkySim | Текстовый протокол |
| `TK102TK103ParserSpec` | TK102/TK103 | Текстовый, IMEI из login, координаты |
| `ArnaviParserSpec` | Arnavi | Бинарный, CRC-8 |
| `AdmParserSpec` | ADM | Бинарный протокол |
| `AutophoneMayakParserSpec` | Autophone Mayak | Текстовый, GSM-based |
| `MicroMayakParserSpec` | MicroMayak | Компактный бинарный формат |

**Каждый парсер тестирует:**
- Парсинг корректного пакета → `List[GpsRawPoint]`
- Извлечение IMEI
- Извлечение координат (lat, lon)
- Обработка невалидных данных (пустой буфер, повреждённые данные)
- Граничные случаи (0 спутников, нулевая скорость, отрицательные координаты)

### 2. Энкодеры команд (6 файлов, 126 тестов)

Расположение: `src/test/scala/com/wayrecall/tracker/command/`

| Файл | Тестов | Что тестируется |
|---|---|---|
| `TeltonikaEncoderSpec` | 25 | Codec 12 формат, setdigout (single-bit: "setdigout 1"/"setdigout 0"), параметры |
| `NavTelecomEncoderSpec` | 20 | NTCB FLEX структура, PasswordCommand, CRC-16 CCITT |
| `DtmEncoderSpec` | 18 | IOSwitch binary, AT-команды, XOR checksum |
| `RuptelaEncoderSpec` | 17 | cmdByte 0x65/0x66/0x67, бинарная упаковка |
| `WialonEncoderSpec` | 14 | #M# текстовый формат, кодирование параметров |
| `CommandEncoderFactorySpec` | 23 | Выбор энкодера по Protocol, ReceiveOnlyEncoder, маппинг |

**Каждый энкодер тестирует:**
- Кодирование всех типов команд (`RestartDevice`, `SetInterval`, `BlockEngine`, `UnblockEngine`, `RequestPosition`, `CustomCommand`)
- Формат результата (бинарный/текстовый в зависимости от протокола)
- Обработку неподдерживаемых команд → `UnsupportedCommand`
- `ReceiveOnlyEncoder` для протоколов без обратной связи

### 3. HTTP API (1 файл, 20 тестов)

Расположение: `src/test/scala/com/wayrecall/tracker/api/`

| Файл | Тестов | Что тестируется |
|---|---|---|
| `HttpApiSpec` | 20 | Все REST endpoints с mock слоями |

**Endpoints:**
| Endpoint | Тестов | Что проверяем |
|---|---|---|
| `GET /health` | 2 | Статус 200, `alive: true` |
| `GET /metrics` | 2 | Статус 200, JSON метрики |
| `GET /connections` | 3 | Список соединений, пустой список, `totalConnections` |
| `GET /connections/:imei` | 3 | Найдено (200), не найдено (404), ошибка формата |
| `POST /debug/protocol` | 4 | Парсинг hex-пакета, невалидный hex, ответ с полями |
| `POST /connections/:imei/disconnect` | 3 | Отключение, IMEI не найден (404) |
| `GET /debug/protocol-stats` | 3 | Статистика по протоколам |

### 4. Фильтры (2 файла)

Расположение: `src/test/scala/com/wayrecall/tracker/filter/`

| Файл | Что тестируется |
|---|---|
| `DeadReckoningFilterSpec` | Фильтрация нереальных перемещений, скорость/расстояние, TestClock |
| `StationaryFilterSpec` | Фильтрация стационарных точек (дребезг GPS) |

### 5. Сетевой уровень (2 файла)

Расположение: `src/test/scala/com/wayrecall/tracker/network/`

| Файл | Что тестируется |
|---|---|
| `ConnectionRegistrySpec` | Регистрация/удаление соединений, конкурентный доступ |
| `RateLimiterSpec` | Token Bucket алгоритм, пропускная способность |

### 6. Домен (1 файл)

Расположение: `src/test/scala/com/wayrecall/tracker/domain/`

| Файл | Что тестируется |
|---|---|
| `GeoMathSpec` | Haversine расстояние, GeoPoint валидация |

---

## Как запускать

```bash
# Все тесты
cd services/connection-manager && sbt test

# Конкретный модуль
sbt "testOnly com.wayrecall.tracker.protocol.*"
sbt "testOnly com.wayrecall.tracker.command.*"
sbt "testOnly com.wayrecall.tracker.api.HttpApiSpec"

# Конкретный тест-файл
sbt "testOnly com.wayrecall.tracker.protocol.TeltonikaParserSpec"
```

---

## Известные особенности

1. **Scala 3 + ZIO Test `assertTrue` macro** — не сравнивать `Double` с `Int` литералом (используй `.0`)
2. **`java.lang.Math$`** — в ZIO Test использовать `scala.math.abs()` вместо `Math.abs()`
3. **TestClock** — стартует с epoch (0ms), use `TestClock.adjust` для тестов с временем
4. **`@@ TestAspect.withLiveClock`** — конфликтует с `TestClock.adjust`, не миксовать
5. **Teltonika setdigout** — формат single-bit: `"setdigout 1"` / `"setdigout 0"` (НЕ two-bit)
6. **CommandStatus JSON** — `derives JsonCodec` на enum даёт `{"Sent":{}}` формат
