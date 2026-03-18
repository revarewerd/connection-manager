package com.wayrecall.tracker.network

import zio.*
import io.netty.channel.ChannelHandlerContext
import com.wayrecall.tracker.protocol.ProtocolParser
import com.wayrecall.tracker.domain.GpsRawPoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import com.wayrecall.tracker.service.CmMetrics

/**
 * Реестр активных TCP соединений
 * 
 * ✅ ConcurrentHashMap + AtomicLong для lock-free операций на горячем пути
 * ✅ updateLastActivity = 0 аллокаций (AtomicLong.set вместо Ref.modify + Map copy)
 * 
 * Позволяет:
 * - Находить соединение по IMEI для отправки команд
 * - Отслеживать количество активных подключений
 * - Управлять жизненным циклом соединений
 * - Отслеживать время последней активности (для idle timeout)
 */
trait ConnectionRegistry:
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit]
  def unregister(imei: String): UIO[Unit]
  def unregisterIfSame(imei: String, ctx: ChannelHandlerContext): UIO[Unit]
  def findByImei(imei: String): UIO[Option[ConnectionEntry]]
  def getAllConnections: UIO[List[ConnectionEntry]]
  def connectionCount: UIO[Int]
  def isConnected(imei: String): UIO[Boolean]
  def updateLastActivity(imei: String): UIO[Unit]
  def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]]

/**
 * Mutable запись соединения с AtomicLong для lock-free обновления lastActivityAt
 */
final class MutableConnectionEntry(
    val imei: String,
    val ctx: ChannelHandlerContext,
    val parser: ProtocolParser,
    val connectedAt: Long,
    val lastActivityAt: AtomicLong,
    val lastPosition: Option[GpsRawPoint] = None
):
  /** Снимок для read-only операций */
  def toSnapshot: ConnectionEntry = ConnectionEntry(
    imei = imei,
    ctx = ctx,
    parser = parser,
    connectedAt = connectedAt,
    lastActivityAt = lastActivityAt.get(),
    lastPosition = lastPosition
  )

/**
 * Immutable снимок соединения — для API и IdleConnectionWatcher
 */
final case class ConnectionEntry(
    imei: String,
    ctx: ChannelHandlerContext,
    parser: ProtocolParser,
    connectedAt: Long,
    lastActivityAt: Long,
    lastPosition: Option[GpsRawPoint] = None
)

object ConnectionRegistry:
  
  // Accessor methods
  def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.register(imei, ctx, parser))
  def unregister(imei: String): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.unregister(imei))
  def unregisterIfSame(imei: String, ctx: ChannelHandlerContext): URIO[ConnectionRegistry, Unit] =
    ZIO.serviceWithZIO(_.unregisterIfSame(imei, ctx))
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
   * Live реализация с ConcurrentHashMap — lock-free для горячего пути
   * 
   * ✅ updateLastActivity: AtomicLong.set — 0 аллокаций, O(1), без ZIO overhead
   * ✅ register/unregister: ConcurrentHashMap.put/remove — O(1), thread-safe
   * ✅ connectionCount: ConcurrentHashMap.size — O(1)
   * ✅ Поддержка reconnect: put заменяет старое значение, старое соединение закрывается
   */
  final case class Live(
      connections: ConcurrentHashMap[String, MutableConnectionEntry]
  ) extends ConnectionRegistry:
    
    override def register(imei: String, ctx: ChannelHandlerContext, parser: ProtocolParser): UIO[Unit] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        entry = new MutableConnectionEntry(
          imei = imei,
          ctx = ctx,
          parser = parser,
          connectedAt = now,
          lastActivityAt = new AtomicLong(now)
        )
        oldEntry <- ZIO.succeed(Option(connections.put(imei, entry)))
        _ <- ZIO.foreach(oldEntry) { old =>
          for
            _ <- ZIO.logWarning(s"[REGISTRY] ⚠ Обнаружен reconnect для IMEI=$imei, закрываем старое соединение")
            _ <- ZIO.logDebug(s"[REGISTRY] Старое соединение было установлено в ${old.connectedAt}, последняя активность ${old.lastActivityAt.get()}")
            _ <- ZIO.attempt(old.ctx.close()).ignore
          yield ()
        }
        count = connections.size()
        _ <- ZIO.succeed {
          CmMetrics.totalConnections.increment()
          CmMetrics.activeConnections.set(count.toLong)
        }
        _ <- ZIO.logInfo(s"[REGISTRY] ✓ Соединение зарегистрировано: IMEI=$imei, всего активных: $count")
      yield ()
    
    override def unregister(imei: String): UIO[Unit] =
      for
        removed <- ZIO.succeed(Option(connections.remove(imei)).isDefined)
        count = connections.size()
        _ <- ZIO.succeed {
          if removed then
            CmMetrics.totalDisconnections.increment()
            CmMetrics.activeConnections.set(count.toLong)
        }
        _ <- if removed then
          ZIO.logInfo(s"[REGISTRY] ✗ Соединение удалено: IMEI=$imei, всего активных: $count")
        else
          ZIO.logDebug(s"[REGISTRY] Попытка удалить несуществующее соединение: IMEI=$imei")
      yield ()
    
    override def unregisterIfSame(imei: String, expectedCtx: ChannelHandlerContext): UIO[Unit] =
      for
        removed <- ZIO.succeed {
          // ConcurrentHashMap.compute — атомарная операция
          var wasRemoved = false
          connections.compute(imei, (_, existing) =>
            if existing != null && (existing.ctx eq expectedCtx) then
              wasRemoved = true
              null  // удаляет запись
            else
              existing  // оставляет как есть
          )
          wasRemoved
        }
        count = connections.size()
        _ <- ZIO.succeed {
          if removed then
            CmMetrics.totalDisconnections.increment()
            CmMetrics.activeConnections.set(count.toLong)
        }
        _ <- if removed then
          ZIO.logInfo(s"[REGISTRY] ✗ Соединение удалено (safe): IMEI=$imei, всего активных: $count")
        else
          ZIO.logDebug(s"[REGISTRY] Пропущен unregister для IMEI=$imei — соединение уже заменено (reconnect)")
      yield ()
    
    override def findByImei(imei: String): UIO[Option[ConnectionEntry]] =
      ZIO.succeed(Option(connections.get(imei)).map(_.toSnapshot))
    
    override def getAllConnections: UIO[List[ConnectionEntry]] =
      ZIO.succeed(connections.values().asScala.map(_.toSnapshot).toList)
    
    override def connectionCount: UIO[Int] =
      ZIO.succeed(connections.size())
    
    override def isConnected(imei: String): UIO[Boolean] =
      ZIO.succeed(connections.containsKey(imei))
    
    // Lock-free обновление lastActivityAt — 0 аллокаций, вызывается на каждый GPS пакет
    override def updateLastActivity(imei: String): UIO[Unit] =
      ZIO.succeed {
        val entry = connections.get(imei)
        if entry != null then entry.lastActivityAt.set(java.lang.System.currentTimeMillis())
      }
    
    override def getIdleConnections(maxIdleMs: Long): UIO[List[ConnectionEntry]] =
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        idle <- ZIO.succeed {
          connections.values().asScala.filter { entry =>
            now - entry.lastActivityAt.get() > maxIdleMs
          }.map(_.toSnapshot).toList
        }
        _ <- ZIO.when(idle.nonEmpty) {
          ZIO.logDebug(s"[REGISTRY] Найдено ${idle.size} неактивных соединений (idle > ${maxIdleMs}мс)")
        }
      yield idle
  
  val live: ULayer[ConnectionRegistry] =
    ZLayer.succeed(Live(new ConcurrentHashMap[String, MutableConnectionEntry](16384, 0.75f, 64)))

