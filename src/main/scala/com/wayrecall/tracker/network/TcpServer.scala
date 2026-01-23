package com.wayrecall.tracker.network

import zio.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel, ChannelFuture, ChannelHandler, ChannelInitializer, ChannelOption, EventLoopGroup}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import com.wayrecall.tracker.config.TcpConfig
import java.util.concurrent.TimeUnit

/**
 * TCP сервер на базе Netty с интеграцией ZIO
 * Чисто функциональный интерфейс
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
   * Live реализация с Netty
   */
  final case class Live(
      bossGroup: EventLoopGroup,
      workerGroup: EventLoopGroup,
      config: TcpConfig
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
   */
  val live: ZLayer[TcpConfig, Throwable, TcpServer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[TcpConfig]
        
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
      yield Live(bossGroup, workerGroup, config)
    }
