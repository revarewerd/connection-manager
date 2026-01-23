package com.wayrecall.tracker.network

import zio.*
import zio.stream.*
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import com.wayrecall.tracker.domain.*
import com.wayrecall.tracker.protocol.ProtocolParser
import com.wayrecall.tracker.storage.{RedisClient, KafkaProducer}
import com.wayrecall.tracker.filter.{DeadReckoningFilter, StationaryFilter}
import java.net.InetSocketAddress

/**
 * Состояние соединения - immutable
 */
final case class ConnectionState(
    imei: Option[String] = None,
    vehicleId: Option[Long] = None,
    connectedAt: Long = 0L,
    positionCache: Map[Long, GpsPoint] = Map.empty
):
  def withImei(newImei: String, vid: Long, timestamp: Long): ConnectionState =
    copy(imei = Some(newImei), vehicleId = Some(vid), connectedAt = timestamp)
  
  def updatePosition(vid: Long, point: GpsPoint): ConnectionState =
    copy(positionCache = positionCache.updated(vid, point))
  
  def getPosition(vid: Long): Option[GpsPoint] =
    positionCache.get(vid)

/**
 * Сервис обработки GPS данных - чисто функциональный
 */
trait GpsProcessingService:
  def processImeiPacket(
    buffer: ByteBuf,
    remoteAddress: InetSocketAddress
  ): IO[ProtocolError, (String, Long, Option[GpsPoint])]
  
  def processDataPacket(
    buffer: ByteBuf,
    imei: String,
    vehicleId: Long,
    prevPosition: Option[GpsPoint]
  ): IO[Throwable, (List[GpsPoint], Int)]
  
  def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress): UIO[Unit]
  
  def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit]

object GpsProcessingService:
  
  final case class Live(
      parser: ProtocolParser,
      redisClient: RedisClient,
      kafkaProducer: KafkaProducer,
      deadReckoningFilter: DeadReckoningFilter,
      stationaryFilter: StationaryFilter
  ) extends GpsProcessingService:
    
    override def processImeiPacket(
      buffer: ByteBuf,
      remoteAddress: InetSocketAddress
    ): IO[ProtocolError, (String, Long, Option[GpsPoint])] =
      for
        imei <- parser.parseImei(buffer)
        maybeVehicleId <- redisClient.getVehicleId(imei)
                            .mapError(e => ProtocolError.ParseError(e.message))
        vehicleId <- ZIO.fromOption(maybeVehicleId)
                       .orElseFail(ProtocolError.UnknownDevice(imei))
        prevPosition <- redisClient.getPosition(vehicleId)
                          .mapError(e => ProtocolError.ParseError(e.message))
      yield (imei, vehicleId, prevPosition)
    
    override def processDataPacket(
      buffer: ByteBuf,
      imei: String,
      vehicleId: Long,
      prevPosition: Option[GpsPoint]
    ): IO[Throwable, (List[GpsPoint], Int)] =
      for
        rawPoints <- parser.parseData(buffer, imei)
                       .mapError(e => new Exception(e.message))
        result <- ZIO.foldLeft(rawPoints)((List.empty[GpsPoint], prevPosition)) { 
          case ((processed, prev), raw) =>
            processPoint(raw, vehicleId, prev).map { point =>
              (processed :+ point, Some(point))
            }.catchAll { error =>
              ZIO.logWarning(s"Точка отфильтрована: ${error.getMessage}") *>
              ZIO.succeed((processed, prev))
            }
        }
        (validPoints, _) = result
      yield (validPoints, rawPoints.size)
    
    private def processPoint(
      raw: GpsRawPoint,
      vehicleId: Long,
      prev: Option[GpsPoint]
    ): IO[Throwable, GpsPoint] =
      for
        // Валидация через Dead Reckoning Filter
        _ <- deadReckoningFilter.validateWithPrev(raw, prev)
               .mapError(e => new Exception(e.message))
        
        point = raw.toValidated(vehicleId)
        
        // Определяем, нужно ли публиковать в Kafka
        shouldPublish <- stationaryFilter.shouldPublish(point, prev)
        
        // Сохраняем в Redis (всегда, для фронтенда)
        _ <- redisClient.setPosition(point)
               .mapError(e => new Exception(e.message))
        
        // Публикуем в Kafka только если движемся
        _ <- ZIO.when(shouldPublish)(
               kafkaProducer.publishGpsEvent(point)
                 .mapError(e => new Exception(e.message))
             )
      yield point
    
    override def onConnect(imei: String, vehicleId: Long, remoteAddress: InetSocketAddress): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        connInfo = ConnectionInfo(
          imei = imei,
          connectedAt = now,
          remoteAddress = remoteAddress.getAddress.getHostAddress,
          port = remoteAddress.getPort
        )
        _ <- redisClient.registerConnection(connInfo)
        _ <- kafkaProducer.publishDeviceStatus(DeviceStatus(
          imei = imei,
          vehicleId = vehicleId,
          isOnline = true,
          lastSeen = now
        ))
        _ <- ZIO.logInfo(s"Устройство подключено: IMEI=$imei, vehicleId=$vehicleId")
      yield ()
      
      effect.ignore
    
    override def onDisconnect(imei: String, vehicleId: Long, reason: DisconnectReason, connectedAt: Long): UIO[Unit] =
      val effect = for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        sessionDurationMs = now - connectedAt
        _ <- redisClient.unregisterConnection(imei)
        _ <- kafkaProducer.publishDeviceStatus(DeviceStatus(
          imei = imei,
          vehicleId = vehicleId,
          isOnline = false,
          lastSeen = now,
          disconnectReason = Some(reason),
          sessionDurationMs = Some(sessionDurationMs)
        ))
        _ <- ZIO.logInfo(s"Устройство отключено: IMEI=$imei, reason=$reason, session=${sessionDurationMs / 1000}s")
      yield ()
      
      effect.ignore
  
  val live: ZLayer[
    ProtocolParser & RedisClient & KafkaProducer & DeadReckoningFilter & StationaryFilter,
    Nothing,
    GpsProcessingService
  ] = ZLayer {
    for
      parser <- ZIO.service[ProtocolParser]
      redis <- ZIO.service[RedisClient]
      kafka <- ZIO.service[KafkaProducer]
      deadReckoning <- ZIO.service[DeadReckoningFilter]
      stationary <- ZIO.service[StationaryFilter]
    yield Live(parser, redis, kafka, deadReckoning, stationary)
  }

/**
 * Netty ChannelHandler - минимальный мост к ZIO
 * Вся логика делегируется GpsProcessingService
 */
class ConnectionHandler(
    service: GpsProcessingService,
    parser: ProtocolParser,
    registry: ConnectionRegistry,
    runtime: Runtime[Any]
) extends ChannelInboundHandlerAdapter:
  
  // Используем Ref для иммутабельного состояния
  private val stateRef: Ref[ConnectionState] = 
    Unsafe.unsafe(implicit u => runtime.unsafe.run(Ref.make(ConnectionState())).getOrThrow())
  
  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit =
    msg match
      case buffer: ByteBuf =>
        val effect = for
          state <- stateRef.get
          _ <- state.imei match
            case None => handleImeiPacket(ctx, buffer)
            case Some(_) => handleDataPacket(ctx, buffer, state)
        yield ()
        
        runEffect(effect.ensuring(ZIO.succeed(buffer.release())))
      case _ =>
        ctx.fireChannelRead(msg)
  
  private def handleImeiPacket(ctx: ChannelHandlerContext, buffer: ByteBuf): UIO[Unit] =
    val remoteAddr = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
    
    val effect = for
      result <- service.processImeiPacket(buffer, remoteAddr)
      (imei, vehicleId, prevPosition) = result
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      
      // Обновляем состояние с временем подключения
      _ <- stateRef.update { state =>
        val updated = state.withImei(imei, vehicleId, now)
        prevPosition.fold(updated)(pos => updated.updatePosition(vehicleId, pos))
      }
      
      // Регистрируем подключение в реестре
      _ <- registry.register(imei, ctx, parser)
      
      // Регистрируем подключение в Redis/Kafka
      _ <- service.onConnect(imei, vehicleId, remoteAddr)
      
      // Отправляем ACK
      _ <- ZIO.succeed {
        val ack = parser.imeiAck(true)
        ctx.writeAndFlush(ack)
      }
    yield ()
    
    effect.catchAll { error =>
      ZIO.logWarning(s"IMEI отклонен: ${error.message}") *>
      ZIO.succeed {
        val ack = parser.imeiAck(false)
        ctx.writeAndFlush(ack)
        ctx.close()
      }
    }
  
  private def handleDataPacket(
    ctx: ChannelHandlerContext,
    buffer: ByteBuf,
    state: ConnectionState
  ): UIO[Unit] =
    val effect = for
      imei <- ZIO.fromOption(state.imei).orElseFail(new Exception("IMEI не установлен"))
      vehicleId <- ZIO.fromOption(state.vehicleId).orElseFail(new Exception("VehicleId не установлен"))
      
      // Обновляем время последней активности
      _ <- registry.updateLastActivity(imei)
      
      prevPosition = state.getPosition(vehicleId)
      result <- service.processDataPacket(buffer, imei, vehicleId, prevPosition)
      (validPoints, totalCount) = result
      
      // Обновляем кеш последней позицией
      _ <- ZIO.foreach(validPoints.lastOption) { lastPoint =>
        stateRef.update(_.updatePosition(vehicleId, lastPoint))
      }
      
      // Отправляем ACK
      _ <- ZIO.succeed {
        val ack = parser.ack(totalCount)
        ctx.writeAndFlush(ack)
      }
      
      _ <- ZIO.logDebug(s"Обработано точек: ${validPoints.size}/$totalCount для IMEI=$imei")
    yield ()
    
    effect.catchAll { error =>
      ZIO.logError(s"Ошибка обработки данных: ${error.getMessage}")
    }
  
  override def channelActive(ctx: ChannelHandlerContext): Unit =
    runEffect(ZIO.logInfo(s"Новое соединение: ${ctx.channel().remoteAddress()}"))
    super.channelActive(ctx)
  
  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    val effect = for
      state <- stateRef.get
      _ <- (state.imei, state.vehicleId) match
        case (Some(imei), Some(vid)) =>
          // Удаляем из реестра и уведомляем о graceful disconnect
          registry.unregister(imei) *>
          service.onDisconnect(imei, vid, DisconnectReason.GracefulClose, state.connectedAt)
        case _ => ZIO.unit
      _ <- ZIO.logInfo(s"Соединение закрыто: ${ctx.channel().remoteAddress()}")
    yield ()
    
    runEffect(effect)
    super.channelInactive(ctx)
  
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    runEffect(ZIO.logError(s"Ошибка в соединении: ${cause.getMessage}"))
    ctx.close()
  
  private def runEffect[A](effect: UIO[A]): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
    }

object ConnectionHandler:
  /**
   * Создает фабрику обработчиков
   */
  def factory(
      service: GpsProcessingService,
      parser: ProtocolParser,
      registry: ConnectionRegistry,
      runtime: Runtime[Any]
  ): () => ConnectionHandler =
    () => new ConnectionHandler(service, parser, registry, runtime)
