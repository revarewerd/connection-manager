package com.wayrecall.tracker.service

import java.util.concurrent.atomic.{AtomicLong, LongAdder}

/**
 * Метрики Connection Manager — lock-free счётчики для мониторинга
 * 
 * Использует LongAdder для счётчиков (optimized для high-contention increment)
 * и AtomicLong для gauges (текущее значение).
 * 
 * Формат вывода: Prometheus text exposition format (OpenMetrics-совместимый).
 * Доступен через GET /api/metrics.
 */
object CmMetrics:

  // ═══════════════════════════════════════════════════════════════
  // Gauges — текущее состояние (может увеличиваться и уменьшаться)
  // ═══════════════════════════════════════════════════════════════
  
  /** Количество активных TCP соединений прямо сейчас */
  val activeConnections: AtomicLong = new AtomicLong(0)
  
  // ═══════════════════════════════════════════════════════════════
  // Counters — монотонно растущие значения с момента старта
  // ═══════════════════════════════════════════════════════════════
  
  /** Общее количество TCP соединений с момента старта */
  val totalConnections: LongAdder = new LongAdder()
  
  /** Общее количество TCP отключений с момента старта */
  val totalDisconnections: LongAdder = new LongAdder()
  
  /** Общее количество обработанных DATA-пакетов (TCP чтений после аутентификации) */
  val packetsReceived: LongAdder = new LongAdder()
  
  /** Общее количество сырых GPS точек, полученных от парсеров */
  val gpsPointsReceived: LongAdder = new LongAdder()
  
  /** Общее количество GPS точек, прошедших фильтры и опубликованных в Kafka */
  val gpsPointsPublished: LongAdder = new LongAdder()
  
  /** Общее количество ошибок парсинга протоколов */
  val parseErrors: LongAdder = new LongAdder()
  
  /** Общее количество ошибок публикации в Kafka */
  val kafkaPublishErrors: LongAdder = new LongAdder()
  
  /** Общее количество успешных Kafka publish операций */
  val kafkaPublishSuccess: LongAdder = new LongAdder()
  
  /** Общее количество Redis операций */
  val redisOperations: LongAdder = new LongAdder()
  
  /** Общее количество подключений неизвестных (незарегистрированных) устройств */
  val unknownDevices: LongAdder = new LongAdder()
  
  /** Общее количество команд, отправленных на трекеры */
  val commandsSent: LongAdder = new LongAdder()
  
  /** Общее количество пакетов от unknown-устройств */
  val unknownDevicePackets: LongAdder = new LongAdder()

  // Время запуска (для uptime)
  private val startedAtMs: Long = java.lang.System.currentTimeMillis()

  /**
   * Формирует строку в Prometheus text exposition format
   * 
   * Совместим с Prometheus scraper: GET /api/metrics → text/plain
   */
  def prometheusOutput: String =
    val uptimeSeconds = (java.lang.System.currentTimeMillis() - startedAtMs) / 1000
    
    s"""# HELP cm_connections_active Количество активных TCP соединений
# TYPE cm_connections_active gauge
cm_connections_active ${activeConnections.get()}

# HELP cm_connections_total Общее количество TCP соединений с момента старта
# TYPE cm_connections_total counter
cm_connections_total ${totalConnections.sum()}

# HELP cm_disconnections_total Общее количество TCP отключений
# TYPE cm_disconnections_total counter
cm_disconnections_total ${totalDisconnections.sum()}

# HELP cm_packets_received_total Обработано DATA-пакетов
# TYPE cm_packets_received_total counter
cm_packets_received_total ${packetsReceived.sum()}

# HELP cm_gps_points_received_total Сырые GPS точки от парсеров
# TYPE cm_gps_points_received_total counter
cm_gps_points_received_total ${gpsPointsReceived.sum()}

# HELP cm_gps_points_published_total GPS точки опубликованные в Kafka
# TYPE cm_gps_points_published_total counter
cm_gps_points_published_total ${gpsPointsPublished.sum()}

# HELP cm_parse_errors_total Ошибки парсинга протоколов
# TYPE cm_parse_errors_total counter
cm_parse_errors_total ${parseErrors.sum()}

# HELP cm_kafka_publish_errors_total Ошибки публикации в Kafka
# TYPE cm_kafka_publish_errors_total counter
cm_kafka_publish_errors_total ${kafkaPublishErrors.sum()}

# HELP cm_kafka_publish_success_total Успешные публикации в Kafka
# TYPE cm_kafka_publish_success_total counter
cm_kafka_publish_success_total ${kafkaPublishSuccess.sum()}

# HELP cm_redis_operations_total Операции Redis
# TYPE cm_redis_operations_total counter
cm_redis_operations_total ${redisOperations.sum()}

# HELP cm_unknown_devices_total Подключения неизвестных устройств
# TYPE cm_unknown_devices_total counter
cm_unknown_devices_total ${unknownDevices.sum()}

# HELP cm_commands_sent_total Команды отправленные на трекеры
# TYPE cm_commands_sent_total counter
cm_commands_sent_total ${commandsSent.sum()}

# HELP cm_unknown_device_packets_total Пакеты от незарегистрированных устройств
# TYPE cm_unknown_device_packets_total counter
cm_unknown_device_packets_total ${unknownDevicePackets.sum()}

# HELP cm_uptime_seconds Время работы сервиса
# TYPE cm_uptime_seconds gauge
cm_uptime_seconds $uptimeSeconds

# HELP cm_started_at_timestamp Время запуска (unix ms)
# TYPE cm_started_at_timestamp gauge
cm_started_at_timestamp $startedAtMs
"""
