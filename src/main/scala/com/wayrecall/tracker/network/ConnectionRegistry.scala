package com.wayrecall.tracker.network

import zio.*
import io.netty.channel.ChannelHandlerContext
import com.wayrecall.tracker.protocol.ProtocolParser

/**
 * Реестр активных TCP соединений
 * 
 * ✅ Чисто функциональный: использует ZIO Ref вместо ConcurrentHashMap
 * 
 * Позволяет:
 * - Находить соединение по IMEI для отправки команд
 * - Отслеживать количество активных подключений
 * - Управлять жизненным циклом соединений
 * - Отслеживать время последней активности (для idle timeout)
 */
trait ConnectionRegistry:
  /**
   * Регистрирует новое соединение
   */
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit]
  
  /**
   * Удаляет соединение
   */
  def unregister(imei: String): UIO[Unit]
  
  /**
   * Находит контекст соединения по IMEI
   */
  def findByImei(imei: String): UIO[Option[ConnectionEntry]]
  
  /**
   * Возвращает все активные соединения
   */
  def getAllConnections: UIO[List[ConnectionEntry]]
  
  /**
   * Возвращает количество активных соединений
   */
  def connectionCount: UIO[Int]
  
  /**
   * Проверяет, подключен ли трекер
   */
  def isConnected(imei: String): UIO[Boolean]
  
  /**
   * Обновляет время последней активности для IMEI
   */
  def updateLastActivity(imei: String): UIO[Unit]
  
  /**
   * Получает соединения, неактивные дольше указанного времени
   */
  def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]]

/**
 * Информация о соединении - immutable
 */
final case class ConnectionEntry(
    imei: String,
    ctx: ChannelHandlerContext,
    parser: ProtocolParser,
    connectedAt: Long,
    lastActivityAt: Long
)

object ConnectionRegistry:
  
  // Accessor methods
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.register(imei, ctx, parser))
  
  def unregister(imei: String): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.unregister(imei))
  
  def findByImei(imei: String): URIO[ConnectionRegistry, Option[ConnectionEntry]] =
    ZIO.serviceWithZIO(_.findByImei(imei))
  
  def getAllConnections: URIO[ConnectionRegistry, List[ConnectionEntry]] =
    ZIO.serviceWithZIO(_.getAllConnections)
  
  def connectionCount: URIO[ConnectionRegistry, Int] =
    ZIO.serviceWithZIO(_.connectionCount)
  
  def isConnected(imei: String): URIO[ConnectionRegistry, Boolean] =
    ZIO.serviceWithZIO(_.isConnected(imei))
  
  def updateLastActivity(imei: String): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.updateLastActivity(imei))
  
  def getIdleConnections(maxIdleMs: Long): URIO[ConnectionRegistry, List[ConnectionEntry]] =
    ZIO.serviceWithZIO(_.getIdleConnections(maxIdleMs))
  
  /**
   * Live реализация с ZIO Ref - чисто функциональная!
   * 
   * Использует Ref[Map[String, ConnectionEntry]] для хранения соединений.
   * Все операции атомарные и thread-safe.
   * 
   * ✅ Поддержка reconnect: если трекер переподключается с тем же IMEI,
   *    старое соединение закрывается, новое регистрируется.
   *    Это важно, т.к. трекеры часто теряют связь и переподключаются.
   */
  final case class Live(
      connectionsRef: Ref[Map[String, ConnectionEntry]]
  ) extends ConnectionRegistry:
    
    override def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Создаём новую запись о соединении
        entry = ConnectionEntry(
          imei = imei,
          ctx = ctx,
          parser = parser,
          connectedAt = now,
          lastActivityAt = now
        )
        
        // Атомарно заменяем и получаем старое значение (если было)
        oldEntry <- connectionsRef.modify { map =>
          val old = map.get(imei)
          (old, map + (imei -> entry))
        }
        
        // Обработка reconnect: закрываем старое соединение если было
        _ <- ZIO.foreach(oldEntry) { old =>
          for
            _ <- ZIO.logWarning(s"[REGISTRY] ⚠ Обнаружен reconnect для IMEI=$imei, закрываем старое соединение")
            _ <- ZIO.logDebug(s"[REGISTRY] Старое соединение было установлено в ${old.connectedAt}, последняя активность ${old.lastActivityAt}")
            _ <- ZIO.attempt(old.ctx.close()).ignore
          yield ()
        }
        
        count <- connectionsRef.get.map(_.size)
        _ <- ZIO.logInfo(s"[REGISTRY] ✓ Соединение зарегистрировано: IMEI=$imei, всего активных: $count")
      yield ()
    
    override def unregister(imei: String): UIO[Unit] =
      for
        removed <- connectionsRef.modify { map =>
          (map.contains(imei), map - imei)
        }
        count <- connectionsRef.get.map(_.size)
        _ <- if removed then
          ZIO.logInfo(s"[REGISTRY] ✗ Соединение удалено: IMEI=$imei, всего активных: $count")
        else
          ZIO.logDebug(s"[REGISTRY] Попытка удалить несуществующее соединение: IMEI=$imei")
      yield ()
    
    override def findByImei(imei: String): UIO[Option[ConnectionEntry]] =
      connectionsRef.get.map(_.get(imei))
    
    override def getAllConnections: UIO[List[ConnectionEntry]] =
      connectionsRef.get.map(_.values.toList)
    
    override def connectionCount: UIO[Int] =
      connectionsRef.get.map(_.size)
    
    override def isConnected(imei: String): UIO[Boolean] =
      connectionsRef.get.map(_.contains(imei))
    
    override def updateLastActivity(imei: String): UIO[Unit] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        updated <- connectionsRef.modify { connections =>
          connections.get(imei) match
            case Some(entry) => 
              (true, connections + (imei -> entry.copy(lastActivityAt = now)))
            case None => 
              (false, connections)
        }
        // Логируем только если соединение не найдено (это странно)
        _ <- ZIO.when(!updated) {
          ZIO.logWarning(s"[REGISTRY] Попытка обновить активность несуществующего соединения: IMEI=$imei")
        }
      yield ()
    
    override def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        connections <- connectionsRef.get
        idleConnections = connections.values.filter { entry =>
          now - entry.lastActivityAt > maxIdleMs
        }.toList
        _ <- ZIO.when(idleConnections.nonEmpty) {
          ZIO.logDebug(s"[REGISTRY] Найдено ${idleConnections.size} неактивных соединений (idle > ${maxIdleMs}мс)")
        }
      yield idleConnections
  
  /**
   * ZIO Layer - чисто функциональный, использует Ref
   */
  val live: ULayer[ConnectionRegistry] =
    ZLayer {
      Ref.make(Map.empty[String, ConnectionEntry]).map(Live(_))
    }
