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
   */
  final case class Live(
      connectionsRef: Ref[Map[String, ConnectionEntry]]
  ) extends ConnectionRegistry:
    
    override def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        entry = ConnectionEntry(
          imei = imei,
          ctx = ctx,
          parser = parser,
          connectedAt = now,
          lastActivityAt = now
        )
        _ <- connectionsRef.update(_ + (imei -> entry))
        count <- connectionsRef.get.map(_.size)
        _ <- ZIO.logInfo(s"Registered connection for IMEI: $imei, total: $count")
      yield ()
    
    override def unregister(imei: String): UIO[Unit] =
      for
        _ <- connectionsRef.update(_ - imei)
        count <- connectionsRef.get.map(_.size)
        _ <- ZIO.logInfo(s"Unregistered connection for IMEI: $imei, total: $count")
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
        _ <- connectionsRef.update { connections =>
          connections.get(imei) match
            case Some(entry) => connections + (imei -> entry.copy(lastActivityAt = now))
            case None => connections
        }
      yield ()
    
    override def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        connections <- connectionsRef.get
        idleConnections = connections.values.filter { entry =>
          now - entry.lastActivityAt > maxIdleMs
        }.toList
      yield idleConnections
  
  /**
   * ZIO Layer - чисто функциональный, использует Ref
   */
  val live: ULayer[ConnectionRegistry] =
    ZLayer {
      Ref.make(Map.empty[String, ConnectionEntry]).map(Live(_))
    }
