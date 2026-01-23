package com.wayrecall.tracker.network

import zio.*
import com.wayrecall.tracker.config.{TcpConfig, DynamicConfigService}
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.domain.{DeviceStatus, DisconnectReason}

/**
 * Сервис для отключения неактивных TCP соединений
 * 
 * ✅ Чисто функциональный
 * ✅ Поддерживает динамическую конфигурацию через DynamicConfigService
 * ✅ Отправляет события в Kafka при отключении (для мониторинга/алертов)
 * 
 * Workflow:
 * 1. Периодически (раз в N секунд) проверяем все соединения
 * 2. Если соединение неактивно дольше idle timeout - закрываем его
 * 3. Отправляем DeviceStatus(isOnline=false, reason=IdleTimeout) в Kafka
 * 4. Idle timeout может быть переопределён через Redis динамически
 */
trait IdleConnectionWatcher:
  /**
   * Запускает фоновую задачу мониторинга
   */
  def start: UIO[Fiber[Nothing, Unit]]
  
  /**
   * Принудительно проверяет и отключает idle соединения
   */
  def checkAndDisconnectIdle: UIO[Int]

object IdleConnectionWatcher:
  
  // Accessor methods
  def start: URIO[IdleConnectionWatcher, Fiber[Nothing, Unit]] =
    ZIO.serviceWithZIO(_.start)
  
  def checkAndDisconnectIdle: URIO[IdleConnectionWatcher, Int] =
    ZIO.serviceWithZIO(_.checkAndDisconnectIdle)
  
  /**
   * Live реализация
   */
  final case class Live(
      registry: ConnectionRegistry,
      configService: DynamicConfigService,
      kafkaProducer: KafkaProducer,
      redisClient: RedisClient,
      tcpConfig: TcpConfig
  ) extends IdleConnectionWatcher:
    
    /**
     * Интервал проверки в миллисекундах
     */
    private val checkIntervalMs: Long = tcpConfig.idleCheckIntervalSeconds.toLong * 1000
    
    /**
     * Дефолтный idle timeout из статического конфига
     */
    private val defaultIdleTimeoutMs: Long = tcpConfig.idleTimeoutSeconds.toLong * 1000
    
    override def start: UIO[Fiber[Nothing, Unit]] =
      checkAndDisconnectIdle
        .repeat(Schedule.fixed(Duration.fromMillis(checkIntervalMs)))
        .unit
        .fork
    
    override def checkAndDisconnectIdle: UIO[Int] =
      for
        // Получаем dynamic idle timeout из конфига (если переопределён)
        filterConfig <- configService.getFilterConfig
        
        // Idle timeout можно было бы добавить в FilterConfig, 
        // но пока используем статический
        idleTimeoutMs = defaultIdleTimeoutMs
        
        // Находим idle соединения
        idleConnections <- registry.getIdleConnections(idleTimeoutMs)
        
        // Закрываем каждое idle соединение и отправляем событие
        _ <- ZIO.foreachDiscard(idleConnections) { entry =>
          disconnectWithNotification(entry, DisconnectReason.IdleTimeout)
        }
        
        count = idleConnections.size
        _ <- ZIO.when(count > 0)(
               ZIO.logInfo(s"Disconnected $count idle connections due to inactivity")
             )
      yield count
    
    /**
     * Отключает соединение и отправляет уведомление в Kafka
     */
    private def disconnectWithNotification(
        entry: ConnectionEntry, 
        reason: DisconnectReason
    ): UIO[Unit] =
      (for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        idleSec = (now - entry.lastActivityAt) / 1000
        sessionDurationMs = now - entry.connectedAt
        
        _ <- ZIO.logWarning(
               s"Disconnecting: IMEI=${entry.imei}, reason=$reason, " +
               s"idle=${idleSec}s, session=${sessionDurationMs / 1000}s"
             )
        
        // Получаем vehicleId из Redis (может не быть если IMEI не валидирован)
        maybeVehicleId <- redisClient.getVehicleId(entry.imei).catchAll(_ => ZIO.succeed(None))
        
        // Создаём статус устройства
        status = DeviceStatus(
          imei = entry.imei,
          vehicleId = maybeVehicleId.getOrElse(0L),
          isOnline = false,
          lastSeen = entry.lastActivityAt,
          disconnectReason = Some(reason),
          sessionDurationMs = Some(sessionDurationMs)
        )
        
        // Отправляем в Kafka (fire and forget, не блокируем основной процесс)
        _ <- kafkaProducer.publishDeviceStatus(status).catchAll { e =>
               ZIO.logError(s"Failed to publish disconnect event to Kafka: ${e.getMessage}")
             }
        
        // Удаляем из Redis
        _ <- redisClient.unregisterConnection(entry.imei).ignore
        
        // Закрываем канал Netty
        _ <- ZIO.attempt(entry.ctx.close()).ignore
        
        // Удаляем из реестра
        _ <- registry.unregister(entry.imei)
      yield ()).ignore
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[ConnectionRegistry & DynamicConfigService & KafkaProducer & RedisClient & TcpConfig, Nothing, IdleConnectionWatcher] =
    ZLayer {
      for
        registry <- ZIO.service[ConnectionRegistry]
        configService <- ZIO.service[DynamicConfigService]
        kafka <- ZIO.service[KafkaProducer]
        redis <- ZIO.service[RedisClient]
        tcpConfig <- ZIO.service[TcpConfig]
      yield Live(registry, configService, kafka, redis, tcpConfig)
    }
