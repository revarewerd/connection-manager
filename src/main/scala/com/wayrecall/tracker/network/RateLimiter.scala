package com.wayrecall.tracker.network

import zio.*
import java.time.Instant

/**
 * Rate Limiter для защиты от flood атак
 * 
 * ✅ Чисто функциональный: использует ZIO Ref
 * ✅ IP-based: ограничивает количество соединений с одного IP
 * ✅ Sliding window: окно скользит по времени
 * 
 * @param maxConnectionsPerIp максимум соединений с одного IP за период
 * @param windowMs размер окна в миллисекундах
 * @param cleanupIntervalMs интервал очистки устаревших записей
 */
trait RateLimiter:
  /**
   * Проверяет, можно ли принять соединение с данного IP
   * @return true если соединение разрешено, false если превышен лимит
   */
  def tryAcquire(ip: String): UIO[Boolean]
  
  /**
   * Получает текущее количество соединений с IP
   */
  def getConnectionCount(ip: String): UIO[Int]
  
  /**
   * Получает статистику по всем IP
   */
  def getStats: UIO[Map[String, Int]]
  
  /**
   * Запускает фоновую очистку устаревших записей
   */
  def startCleanup: UIO[Unit]

/**
 * Запись о подключениях с IP
 */
final case class ConnectionRecord(
    timestamps: List[Long] // Timestamps подключений в окне
)

object RateLimiter:
  
  // Accessor methods
  def tryAcquire(ip: String): URIO[RateLimiter, Boolean] =
    ZIO.serviceWithZIO(_.tryAcquire(ip))
  
  def getConnectionCount(ip: String): URIO[RateLimiter, Int] =
    ZIO.serviceWithZIO(_.getConnectionCount(ip))
  
  def getStats: URIO[RateLimiter, Map[String, Int]] =
    ZIO.serviceWithZIO(_.getStats)
  
  def startCleanup: URIO[RateLimiter, Unit] =
    ZIO.serviceWithZIO(_.startCleanup)
  
  /**
   * Live реализация с Sliding Window алгоритмом
   * 
   * Как работает:
   * 1. Для каждого IP храним список timestamps последних подключений
   * 2. При новом подключении фильтруем timestamps вне текущего окна
   * 3. Если количество >= лимита - отклоняем
   * 4. Иначе добавляем новый timestamp
   * 
   * @param recordsRef Ref с записями по IP адресам
   * @param maxConnectionsPerIp Максимум соединений с одного IP за окно
   * @param windowMs Размер окна в миллисекундах
   * @param cleanupIntervalMs Интервал очистки устаревших записей
   */
  final case class Live(
      recordsRef: Ref[Map[String, ConnectionRecord]],
      maxConnectionsPerIp: Int,
      windowMs: Long,
      cleanupIntervalMs: Long
  ) extends RateLimiter:
    
    override def tryAcquire(ip: String): UIO[Boolean] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        windowStart = now - windowMs
        
        result <- recordsRef.modify { records =>
          val currentRecord = records.getOrElse(ip, ConnectionRecord(List.empty))
          
          // Фильтруем timestamps только в текущем окне (sliding window)
          val validTimestamps = currentRecord.timestamps.filter(_ > windowStart)
          
          if validTimestamps.size >= maxConnectionsPerIp then
            // Лимит превышен - отклоняем
            (false, records + (ip -> ConnectionRecord(validTimestamps)))
          else
            // Добавляем новый timestamp - разрешаем
            val newTimestamps = validTimestamps :+ now
            (true, records + (ip -> ConnectionRecord(newTimestamps)))
        }
        
        // Логируем только при отклонении (это важное событие безопасности)
        _ <- ZIO.when(!result) {
          ZIO.logWarning(s"[RATE_LIMIT] ⛔ Превышен лимит соединений для IP: $ip (макс: $maxConnectionsPerIp за ${windowMs/1000}с)")
        }
        
        // Debug лог для успешных подключений
        _ <- ZIO.when(result) {
          ZIO.logDebug(s"[RATE_LIMIT] ✓ Соединение разрешено для IP: $ip")
        }
      yield result
    
    override def getConnectionCount(ip: String): UIO[Int] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        windowStart = now - windowMs
        records <- recordsRef.get
        count = records.get(ip)
          .map(_.timestamps.count(_ > windowStart))
          .getOrElse(0)
      yield count
    
    override def getStats: UIO[Map[String, Int]] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        windowStart = now - windowMs
        records <- recordsRef.get
        stats = records.map { case (ip, record) =>
          ip -> record.timestamps.count(_ > windowStart)
        }
      yield stats
    
    override def startCleanup: UIO[Unit] =
      val cleanup = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        windowStart = now - windowMs
        
        _ <- recordsRef.update { records =>
          records
            .map { case (ip, record) =>
              ip -> ConnectionRecord(record.timestamps.filter(_ > windowStart))
            }
            .filter { case (_, record) => record.timestamps.nonEmpty }
        }
      yield ()
      
      cleanup
        .repeat(Schedule.fixed(Duration.fromMillis(cleanupIntervalMs)))
        .forkDaemon
        .unit
  
  /**
   * ZIO Layer с настройками по умолчанию
   * 
   * @param maxConnectionsPerIp максимум соединений с одного IP за период (по умолчанию 50)
   * @param windowSeconds размер окна в секундах (по умолчанию 60)
   * @param cleanupIntervalSeconds интервал очистки в секундах (по умолчанию 60)
   */
  def live(
      maxConnectionsPerIp: Int = 50,
      windowSeconds: Int = 60,
      cleanupIntervalSeconds: Int = 60
  ): ULayer[RateLimiter] =
    ZLayer {
      for
        ref <- Ref.make(Map.empty[String, ConnectionRecord])
        limiter = Live(
          ref,
          maxConnectionsPerIp,
          windowSeconds * 1000L,
          cleanupIntervalSeconds * 1000L
        )
      yield limiter
    }
  
  /**
   * ZIO Layer из AppConfig (для использования в Main)
   */
  import com.wayrecall.tracker.config.{AppConfig, RateLimitConfig}
  
  val live: ZLayer[AppConfig, Nothing, RateLimiter] =
    ZLayer {
      for
        config <- ZIO.service[AppConfig]
        ref <- Ref.make(Map.empty[String, ConnectionRecord])
        limiter = Live(
          ref,
          config.rateLimit.maxConnectionsPerIp,
          60 * 1000L, // 60 секунд окно
          config.rateLimit.cleanupIntervalSeconds * 1000L
        )
      yield limiter
    }
