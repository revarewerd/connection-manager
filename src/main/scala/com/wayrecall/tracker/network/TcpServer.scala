package com.wayrecall.tracker.network

import zio.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.{Channel, ChannelFuture, ChannelHandler, ChannelInitializer, ChannelOption, EventLoopGroup, ChannelHandlerContext, ChannelInboundHandlerAdapter, WriteBufferWaterMark}
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
 * ✅ Нативные транспорты: Epoll (Linux) / KQueue (macOS) / NIO (fallback)
 * ✅ Оптимизированные socket options для 100K+ соединений
 * ✅ Rate limiting для защиты от flood атак
 * ✅ Graceful shutdown
 */
trait TcpServer:
  // QUESTION(U): что за объекты такие ChannelHandler? Это же интерфейс, который должен реализовать пользователь для обработки входящих сообщений?
  // ANSWER(AI): да. ChannelHandler — обработчик событий канала (подключение, чтение, ошибки, запись). В нашем случае обычно используется ChannelInboundHandlerAdapter с бизнес-логикой.
  // QUESTION(U): и Сhannel - это объект, который представляет собой открытое соединение с клиентом? И start возвращает этот объект, чтобы пользователь мог потом его закрыть через stop?
  // ANSWER(AI): да. Channel представляет серверный bind/соединение в Netty; возвращаем его, чтобы можно было контролировать lifecycle и корректно остановить через close/stop.
  def start(port: Int, handlerFactory: () => ChannelHandler): Task[Channel]
  def stop(channel: Channel): Task[Unit]

object TcpServer:
  
  // Accessor методы
  // QUESTION(U): что такое аксессор методы? Это просто обертки над методами интерфейса, которые позволяют вызывать их через ZIO сервис? И почему они нужны, если можно напрямую вызвать методы интерфейса через ZIO.serviceWithZIO?
  // ANSWER(AI): да, это тонкие обёртки. Они дают единый стиль вызова сервиса и убирают дублирование ZIO.serviceWithZIO по коду.
  def start(port: Int, handlerFactory: () => ChannelHandler): ZIO[TcpServer, Throwable, Channel] =
    ZIO.serviceWithZIO(_.start(port, handlerFactory))
  
  def stop(channel: Channel): ZIO[TcpServer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.stop(channel))
  
  // Определяем нативный транспорт: Epoll (Linux) > KQueue (macOS) > NIO (fallback)
  // QUESTION(U): что такое еполь и кью? Это нативные реализации сетевого стека для Linux и macOS соответственно, которые обеспечивают лучшую производительность по сравнению с универсальной Java NIO? И как они определяются в коде? Через рефлексию, которая проверяет наличие классов и методов в Netty?
  // ANSWER(AI): всё так. Epoll/KQueue — более эффективные event I/O механизмы ОС; здесь они выбираются runtime-проверкой Netty isAvailable через reflection.
  private lazy val useEpoll: Boolean =
    try
      Class.forName("io.netty.channel.epoll.Epoll")
        .getMethod("isAvailable")
        .invoke(null)
        .asInstanceOf[Boolean]
    catch case _: Throwable => false
  
  private lazy val useKQueue: Boolean =
    !useEpoll && (try
      Class.forName("io.netty.channel.kqueue.KQueue")
        .getMethod("isAvailable")
        .invoke(null)
        .asInstanceOf[Boolean]
    catch case _: Throwable => false)
  
  private def transportName: String =
    if useEpoll then "Epoll" else if useKQueue then "KQueue" else "NIO"
  // QUESTION(U):что такое евент луп груп? Это группа потоков, которая обрабатывает события ввода-вывода для сетевых соединений? И почему их две - босс и воркер? Босс отвечает за принятие новых соединений, а воркер - за обработку уже установленных соединений?
  // ANSWER(AI): да. Boss принимает входящие подключения (accept), Worker обрабатывает I/O уже принятых каналов (read/write/handlers).
  private def createEventLoopGroup(threads: Int): EventLoopGroup =
    if useEpoll then
      val cls = Class.forName("io.netty.channel.epoll.EpollEventLoopGroup")
      cls.getConstructor(classOf[Int]).newInstance(Integer.valueOf(threads)).asInstanceOf[EventLoopGroup]
    else if useKQueue then
      val cls = Class.forName("io.netty.channel.kqueue.KQueueEventLoopGroup")
      cls.getConstructor(classOf[Int]).newInstance(Integer.valueOf(threads)).asInstanceOf[EventLoopGroup]
    else
      new NioEventLoopGroup(threads)
  
  private def serverChannelClass: Class[? <: io.netty.channel.ServerChannel] =
    if useEpoll then
      Class.forName("io.netty.channel.epoll.EpollServerSocketChannel")
        .asInstanceOf[Class[? <: io.netty.channel.ServerChannel]]
    else if useKQueue then
      Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel")
        .asInstanceOf[Class[? <: io.netty.channel.ServerChannel]]
    else
      classOf[NioServerSocketChannel]

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
          // QUESTION(U):что делает аттемпт? Это конструкция ZIO, которая позволяет выполнить блок кода, который может выбросить исключение, и автоматически обернуть его в ZIO эффект, который будет либо успешным с результатом, либо провалится с ошибкой? И почему здесь используется asyncZIO вместо обычного async? Потому что внутри мы выполняем асинхронные операции Netty, которые используют callback-и, и нам нужно интегрировать их с ZIO?
          // ANSWER(AI): верно. ZIO.attempt ловит throw-ошибки в typed-эффект. asyncZIO используется, потому что результат мы получаем позже через callback ChannelFuture.addListener.
          val bootstrap = new ServerBootstrap()// QUESTION(U):что такое бутстрап? Это класс Netty, который используется для настройки и запуска сервера? И какие опции мы ему передаем? Мы указываем группы потоков, класс канала, опции сокетов (backlog, reuseaddr, keepalive, tcp_nodelay, buffer sizes), а также инициализатор каналов, который будет создавать pipeline для каждого нового соединения?
          // ANSWER(AI): да, это точка сборки Netty-сервера: event loops, тип server channel, socket options и pipeline-init для каждого нового client channel.
          bootstrap.group(bossGroup, workerGroup)
            .channel(serverChannelClass)
            .option(ChannelOption.SO_BACKLOG, Integer.valueOf(4096))
            .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
            // Оптимизация буферов для GPS пакетов (маленькие, но частые)
            .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(config.keepAlive)) //QUESTION(U): что за чаелд оптион? Это опция, которая применяется к каждому новому соединению (child channel), в отличие от опций, которые применяются к серверному каналу? И почему keepAlive важен для стабильности соединений? Он позволяет поддерживать соединение открытым даже при отсутствии данных, что может быть полезно для устройств, которые отправляют данные с определенной периодичностью?
            // ANSWER(AI): да, childOption применяется к каждому клиентскому соединению, а option — к серверному сокету bind. keepAlive помогает быстрее выявлять «мертвые» TCP-сессии на долгоживущих коннектах.
            .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.valueOf(config.tcpNodelay))
            .childOption(ChannelOption.SO_RCVBUF, Integer.valueOf(4096))
            .childOption(ChannelOption.SO_SNDBUF, Integer.valueOf(4096))
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(16 * 1024, 32 * 1024))
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childHandler(new ChannelInitializer[SocketChannel] {
              override def initChannel(ch: SocketChannel): Unit =
                val pipeline = ch.pipeline()
                
                //QUESTION(U): как вообще у нас конкрурентность обрабатывается? У нас же может быть 100К+ соединений, и все они будут вызывать этот initChannel при подключении. И как мы гарантируем, что эти операции будут выполняться эффективно и не будут блокировать друг друга? Ответ в том, что Netty использует EventLoopGroup, который распределяет обработку событий между несколькими потоками, и каждый канал обрабатывается одним потоком из группы. Поэтому даже при большом количестве соединений, они будут обрабатываться параллельно без блокировок.
                // ANSWER(AI): дополнение: initChannel выполняется коротко (сборка pipeline), а тяжёлая работа вынесена в обработчики и ZIO-fibers; это важно для масштаба на большом количестве соединений.
                //QUESTION(U): я плохо понимаю, что такое пайплайн и хендлеры в Netty. Это же цепочка обработки событий для каждого канала, где каждый хендлер отвечает за определенный этап обработки (например, декодирование, бизнес-логика, кодирование)? И мы можем добавлять свои хендлеры в эту цепочку для реализации нужной нам логики? Например, мы добавляем RateLimitHandler для проверки лимитов при подключении, а затем ReadTimeoutHandler и WriteTimeoutHandler для управления таймаутами чтения и записи? И в конце добавляем наш бизнес-логики хендлер, который будет обрабатывать входящие сообщения?
                // ANSWER(AI): да, именно так: pipeline = последовательность обработчиков событий канала. Порядок addLast определяет, в какой последовательности пройдут входящие события.
                
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
  
  // Общий код создания EventLoopGroup с acquireRelease
  private def acquireEventLoopGroup(threads: Int, name: String): ZIO[Scope, Throwable, EventLoopGroup] =
    ZIO.acquireRelease(
      ZIO.attempt(createEventLoopGroup(threads))
        .tap(_ => ZIO.logInfo(s"$name EventLoopGroup создан ($transportName, threads: $threads)"))
    )(group => 
      ZIO.async[Any, Nothing, Unit] { callback =>
        group.shutdownGracefully().addListener(_ => callback(ZIO.unit))
      }.tap(_ => ZIO.logInfo(s"$name EventLoopGroup остановлен"))
    )

  /**
   * ZIO Layer без Rate Limiting
   */
  val live: ZLayer[TcpConfig, Throwable, TcpServer] =
    ZLayer.scoped {
      for
        config   <- ZIO.service[TcpConfig]
        runtime  <- ZIO.runtime[Any]
        boss     <- acquireEventLoopGroup(config.bossThreads, "Boss")
        worker   <- acquireEventLoopGroup(config.workerThreads, "Worker")
      yield Live(boss, worker, config, None, runtime)
    }
  
  /**
   * ZIO Layer с Rate Limiting
   */
  val liveWithRateLimiter: ZLayer[TcpConfig & RateLimiter, Throwable, TcpServer] =
    ZLayer.scoped {
      for
        config   <- ZIO.service[TcpConfig]
        limiter  <- ZIO.service[RateLimiter]
        runtime  <- ZIO.runtime[Any]
        boss     <- acquireEventLoopGroup(config.bossThreads, "Boss")
        worker   <- acquireEventLoopGroup(config.workerThreads, "Worker")
        _        <- ZIO.logInfo(s"✓ Rate Limiter enabled for TcpServer ($transportName)")
      yield Live(boss, worker, config, Some(limiter), runtime)
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
    
    // Синхронный вызов — ОСТАВЛЕН НАМЕРЕННО:
    // rateLimiter.tryAcquire = in-memory Ref (<1μs), вызывается раз per connection.
    // super.channelActive ДОЛЖЕН выполниться ДО channelRead — иначе сломается pipeline.
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
    }
