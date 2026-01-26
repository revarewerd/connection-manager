package com.wayrecall.tracker.network

import zio.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel, ChannelFuture, ChannelHandler, ChannelInitializer, ChannelOption, EventLoopGroup, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import com.wayrecall.tracker.config.TcpConfig
import java.util.concurrent.TimeUnit
import java.net.InetSocketAddress

/**
 * TCP сервер на базе Netty с интеграцией ZIO
 * 
 * ✅ Чисто функциональный интерфейс
 * ✅ Rate limiting для защиты от flood атак
 * ✅ Graceful shutdown
 */
trait TcpServer:
  def start(port: Int, handlerFactory: () => ChannelHandler): Task[Channel]
  def stop(channel: Channel): Task[Unit]

object TcpServer:
  
  // Accessor методы
  def start(port: Int, handlerFactory: () => ChannelHandler): ZIO[TcpServer, Throwable, Channel] =
    ZIO.serviceWithZIO(_.start(port, handlerFactory))
  
  def stop(channel: Channel): ZIO[TcpServer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.stop(channel))
  
  /**
   * Live реализация с Netty и Rate Limiting
   */
  final case class Live(
      bossGroup: EventLoopGroup,
      workerGroup: EventLoopGroup,
      config: TcpConfig,
      rateLimiter: Option[RateLimiter],
      runtime: Runtime[Any]
  ) extends TcpServer:
    
    override def start(port: Int, handlerFactory: () => ChannelHandler): Task[Channel] =
      ZIO.asyncZIO { callback =>
        ZIO.attempt {
          val bootstrap = new ServerBootstrap()
          bootstrap.group(bossGroup, workerGroup)
            .channel(classOf[NioServerSocketChannel])
            .option(ChannelOption.SO_BACKLOG, Integer.valueOf(config.maxConnections))
            .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
            .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(config.keepAlive))
            .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.valueOf(config.tcpNodelay))
            .childHandler(new ChannelInitializer[SocketChannel] {
              override def initChannel(ch: SocketChannel): Unit =
                val pipeline = ch.pipeline()
                
                // Rate Limiter (если включен)
                rateLimiter.foreach { limiter =>
                  pipeline.addLast("rateLimiter", new RateLimitHandler(limiter, runtime))
                }
                
                // Таймауты
                pipeline.addLast("readTimeout", 
                  new ReadTimeoutHandler(config.readTimeoutSeconds.toLong, TimeUnit.SECONDS))
                pipeline.addLast("writeTimeout", 
                  new WriteTimeoutHandler(config.writeTimeoutSeconds.toLong, TimeUnit.SECONDS))
                
                // Бизнес-логика
                pipeline.addLast("handler", handlerFactory())
            })
          
          val channelFuture = bootstrap.bind(port)
          channelFuture.addListener { (future: ChannelFuture) =>
            if future.isSuccess then
              callback(ZIO.succeed(future.channel()))
            else
              callback(ZIO.fail(new Exception(s"Не удалось запустить сервер на порту $port", future.cause())))
          }
        }
      }
    
    override def stop(channel: Channel): Task[Unit] =
      ZIO.async { callback =>
        channel.close().addListener { (future: ChannelFuture) =>
          if future.isSuccess then
            callback(ZIO.unit)
          else
            callback(ZIO.fail(new Exception("Не удалось закрыть канал", future.cause())))
        }
      }
  
  /**
   * ZIO Layer с управлением ресурсами EventLoopGroup
   * 
   * Принимает опциональный RateLimiter
   */
  val live: ZLayer[TcpConfig, Throwable, TcpServer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[TcpConfig]
        runtime <- ZIO.runtime[Any]
        
        // Boss group для accept соединений
        bossGroup <- ZIO.acquireRelease(
          ZIO.attempt(new NioEventLoopGroup(config.bossThreads))
            .tap(_ => ZIO.logInfo(s"Boss EventLoopGroup создан (threads: ${config.bossThreads})"))
        )(group => 
          ZIO.async[Any, Nothing, Unit] { callback =>
            group.shutdownGracefully().addListener(_ => callback(ZIO.unit))
          }.tap(_ => ZIO.logInfo("Boss EventLoopGroup остановлен"))
        )
        
        // Worker group для обработки I/O
        workerGroup <- ZIO.acquireRelease(
          ZIO.attempt(new NioEventLoopGroup(config.workerThreads))
            .tap(_ => ZIO.logInfo(s"Worker EventLoopGroup создан (threads: ${config.workerThreads})"))
        )(group => 
          ZIO.async[Any, Nothing, Unit] { callback =>
            group.shutdownGracefully().addListener(_ => callback(ZIO.unit))
          }.tap(_ => ZIO.logInfo("Worker EventLoopGroup остановлен"))
        )
      yield Live(bossGroup, workerGroup, config, None, runtime)
    }
  
  /**
   * ZIO Layer с Rate Limiting
   */
  val liveWithRateLimiter: ZLayer[TcpConfig & RateLimiter, Throwable, TcpServer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[TcpConfig]
        limiter <- ZIO.service[RateLimiter]
        runtime <- ZIO.runtime[Any]
        
        bossGroup <- ZIO.acquireRelease(
          ZIO.attempt(new NioEventLoopGroup(config.bossThreads))
            .tap(_ => ZIO.logInfo(s"Boss EventLoopGroup создан (threads: ${config.bossThreads})"))
        )(group => 
          ZIO.async[Any, Nothing, Unit] { callback =>
            group.shutdownGracefully().addListener(_ => callback(ZIO.unit))
          }.tap(_ => ZIO.logInfo("Boss EventLoopGroup остановлен"))
        )
        
        workerGroup <- ZIO.acquireRelease(
          ZIO.attempt(new NioEventLoopGroup(config.workerThreads))
            .tap(_ => ZIO.logInfo(s"Worker EventLoopGroup создан (threads: ${config.workerThreads})"))
        )(group => 
          ZIO.async[Any, Nothing, Unit] { callback =>
            group.shutdownGracefully().addListener(_ => callback(ZIO.unit))
          }.tap(_ => ZIO.logInfo("Worker EventLoopGroup остановлен"))
        )
        
        _ <- ZIO.logInfo("✓ Rate Limiter enabled for TcpServer")
      yield Live(bossGroup, workerGroup, config, Some(limiter), runtime)
    }

/**
 * Netty handler для rate limiting
 * Проверяет IP при подключении и закрывает если лимит превышен
 */
class RateLimitHandler(
    rateLimiter: RateLimiter,
    runtime: Runtime[Any]
) extends ChannelInboundHandlerAdapter:
  
  override def channelActive(ctx: ChannelHandlerContext): Unit =
    val remoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
    val ip = remoteAddress.getAddress.getHostAddress
    
    val effect = rateLimiter.tryAcquire(ip).flatMap {
      case true => 
        // Разрешено — пропускаем дальше
        ZIO.succeed(super.channelActive(ctx))
      case false =>
        // Лимит превышен — закрываем соединение
        ZIO.logWarning(s"Rate limit exceeded, closing connection from $ip") *>
        ZIO.succeed(ctx.close())
    }
    
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
    }
