#!/bin/sh
# ============================================
# Инициализация Redis тестовыми данными
# ============================================
# Создаём тестовые маппинги IMEI → VehicleId
# Эти данные позволяют тестировать подключение трекеров
# ============================================

echo "=== Инициализация Redis тестовыми данными ==="

# Ждём готовности Redis
until redis-cli ping; do
  echo "Ожидание Redis..."
  sleep 1
done

# Тестовые трекеры Teltonika (порт 5001)
redis-cli SET vehicle:123456789012345 1001
redis-cli SET vehicle:123456789012346 1002
redis-cli SET vehicle:123456789012347 1003

# Тестовые трекеры Wialon (порт 5002)
redis-cli SET vehicle:352093083377184 2001
redis-cli SET vehicle:352093083377185 2002

# Тестовые трекеры Ruptela (порт 5003)
redis-cli SET vehicle:867232020450988 3001

# Тестовые трекеры NavTelecom (порт 5004)
redis-cli SET vehicle:860906040882713 4001

# Информация о транспортных средствах (опционально)
redis-cli HSET "vehicle-info:1001" name "Тестовый трекер 1" type "Teltonika FMB920"
redis-cli HSET "vehicle-info:1002" name "Тестовый трекер 2" type "Teltonika FMC130"
redis-cli HSET "vehicle-info:2001" name "Wialon трекер" type "Generic Wialon"
redis-cli HSET "vehicle-info:3001" name "Ruptela трекер" type "Ruptela FM-Pro4"

echo "=== Redis инициализирован ==="
echo "Тестовые IMEI:"
echo "  Teltonika: 123456789012345, 123456789012346, 123456789012347"
echo "  Wialon: 352093083377184, 352093083377185"
echo "  Ruptela: 867232020450988"
echo "  NavTelecom: 860906040882713"
