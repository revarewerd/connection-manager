package com.wayrecall.tracker.network

import zio.*
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.domain.{DeviceStatus, DisconnectReason}

/**
 * Слушатель изменений конфигурации устройств через Redis Pub/Sub
 * 
 * ✅ Реагирует на отключение/включение устройств
 * ✅ Автоматически закрывает соединения отключенных устройств
 * ✅ Отправляет события в Kafka для аналитики
 * 
 * Redis каналы:
 * - device:config:{imei}:disabled - устройство отключено
 * - device:config:{imei}:enabled - устройство включено
 */
trait DeviceConfigListener:
  /**
   * Запускает прослушивание изменений конфигурации
   */
  def start: Task[Unit]

object DeviceConfigListener:
  
  // Accessor method
  def start: RIO[DeviceConfigListener, Unit] =
    ZIO.serviceWithZIO(_.start)
  
  /**
   * Live реализация
   */
  final case class Live(
      redis: RedisClient,
      registry: ConnectionRegistry,
      kafka: KafkaProducer
  ) extends DeviceConfigListener:
    
    // Паттерн канала для подписки
    private val channelPattern = "device:config:*"
    
    override def start: Task[Unit] =
      ZIO.logInfo(s"Запуск DeviceConfigListener (pattern: $channelPattern)") *>
      redis.psubscribe(channelPattern) { (channel, message) =>
        handleConfigChange(channel, message)
      }
    
    /**
     * Обрабатывает изменение конфигурации устройства
     */
    private def handleConfigChange(channel: String, message: String): Task[Unit] =
      // Парсим канал: device:config:{imei}:{action}
      val parts = channel.split(":")
      if parts.length >= 4 then
        val imei = parts(2)
        val action = parts(3)
        
        action match
          case "disabled" =>
            handleDeviceDisabled(imei, message)
          case "enabled" =>
            handleDeviceEnabled(imei)
          case other =>
            ZIO.logWarning(s"Неизвестное действие конфигурации: $other для IMEI $imei")
      else
        ZIO.logWarning(s"Некорректный формат канала: $channel")
    
    /**
     * Обрабатывает отключение устройства
     */
    private def handleDeviceDisabled(imei: String, reason: String): Task[Unit] =
      for
        _ <- ZIO.logInfo(s"Устройство отключено: $imei, причина: $reason")
        
        // Закрываем активное соединение если есть
        connectionOpt <- registry.findByImei(imei)
        _ <- connectionOpt match
          case Some(connection) =>
            for
              _ <- ZIO.logInfo(s"Закрываем соединение для отключенного устройства: $imei")
              _ <- ZIO.attempt(connection.ctx.close())
              _ <- registry.unregister(imei)
              // Отправляем событие в Kafka
              now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
              status = DeviceStatus(
                imei = imei,
                vehicleId = 0L, // Неизвестно в данном контексте
                isOnline = false,
                lastSeen = now,
                disconnectReason = Some(DisconnectReason.AdminDisconnect)
              )
              _ <- kafka.publishDeviceStatus(status).catchAll(e => 
                ZIO.logWarning(s"Ошибка отправки статуса в Kafka: ${e.getMessage}")
              )
            yield ()
          case None =>
            ZIO.logDebug(s"Нет активного соединения для $imei")
      yield ()
    
    /**
     * Обрабатывает включение устройства
     */
    private def handleDeviceEnabled(imei: String): Task[Unit] =
      ZIO.logInfo(s"Устройство включено: $imei")
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[RedisClient & ConnectionRegistry & KafkaProducer, Nothing, DeviceConfigListener] =
    ZLayer {
      for
        redis <- ZIO.service[RedisClient]
        registry <- ZIO.service[ConnectionRegistry]
        kafka <- ZIO.service[KafkaProducer]
      yield Live(redis, registry, kafka)
    }
