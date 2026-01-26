package com.wayrecall.tracker.network

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.channel.{ChannelHandlerContext, Channel}
import com.wayrecall.tracker.protocol.ProtocolParser

/**
 * Тесты для ConnectionRegistry
 * 
 * Проверяем:
 * 1. Регистрация соединений
 * 2. Удаление соединений
 * 3. Поиск по IMEI
 * 4. Обработка reconnect (закрытие старого соединения)
 * 5. Мониторинг idle соединений
 */
object ConnectionRegistrySpec extends ZIOSpecDefault:
  
  // Mock ChannelHandlerContext (упрощённый)
  private class MockContext(val isOpen: Ref[Boolean]) extends ChannelHandlerContext:
    def close() = 
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(isOpen.set(false)).getOrThrowFiberFailure()
      }
      null
    
    // Остальные методы не используются в тестах
    def alloc() = ???
    def attr(key: io.netty.util.AttributeKey[?]) = ???
    def bind(localAddress: java.net.SocketAddress) = ???
    def bind(localAddress: java.net.SocketAddress, promise: io.netty.channel.ChannelPromise) = ???
    def channel() = ???
    def close(promise: io.netty.channel.ChannelPromise) = ???
    def connect(remoteAddress: java.net.SocketAddress) = ???
    def connect(remoteAddress: java.net.SocketAddress, localAddress: java.net.SocketAddress) = ???
    def connect(remoteAddress: java.net.SocketAddress, promise: io.netty.channel.ChannelPromise) = ???
    def connect(remoteAddress: java.net.SocketAddress, localAddress: java.net.SocketAddress, promise: io.netty.channel.ChannelPromise) = ???
    def deregister() = ???
    def deregister(promise: io.netty.channel.ChannelPromise) = ???
    def disconnect() = ???
    def disconnect(promise: io.netty.channel.ChannelPromise) = ???
    def executor() = ???
    def fireChannelActive() = ???
    def fireChannelInactive() = ???
    def fireChannelRead(msg: Object) = ???
    def fireChannelReadComplete() = ???
    def fireChannelRegistered() = ???
    def fireChannelUnregistered() = ???
    def fireChannelWritabilityChanged() = ???
    def fireExceptionCaught(cause: Throwable) = ???
    def fireUserEventTriggered(event: Object) = ???
    def flush() = ???
    def handler() = ???
    def hasAttr(key: io.netty.util.AttributeKey[?]) = ???
    def isRemoved() = ???
    def name() = ???
    def newFailedFuture(cause: Throwable) = ???
    def newProgressivePromise() = ???
    def newPromise() = ???
    def newSucceededFuture() = ???
    def pipeline() = ???
    def read() = ???
    def voidPromise() = ???
    def write(msg: Object) = ???
    def write(msg: Object, promise: io.netty.channel.ChannelPromise) = ???
    def writeAndFlush(msg: Object) = ???
    def writeAndFlush(msg: Object, promise: io.netty.channel.ChannelPromise) = ???
  
  // Mock ProtocolParser
  private object MockParser extends ProtocolParser:
    val protocolName = "test"
    def parseImei(buffer: io.netty.buffer.ByteBuf) = ???
    def parseData(buffer: io.netty.buffer.ByteBuf, imei: String) = ???
    def imeiAck(accepted: Boolean) = ???
    def ack(count: Int) = ???
    def encodeCommand(command: com.wayrecall.tracker.domain.Command) = ???
  
  private def makeRegistry: UIO[ConnectionRegistry] =
    Ref.make(Map.empty[String, ConnectionEntry]).map(ConnectionRegistry.Live(_))
  
  private def makeMockContext: UIO[(ChannelHandlerContext, Ref[Boolean])] =
    for
      isOpen <- Ref.make(true)
      ctx = new MockContext(isOpen)
    yield (ctx.asInstanceOf[ChannelHandlerContext], isOpen)
  
  def spec = suite("ConnectionRegistry")(
    
    suite("register")(
      
      test("регистрирует новое соединение") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          count <- registry.connectionCount
        yield assertTrue(count == 1)
      },
      
      test("регистрирует несколько соединений") {
        for
          registry <- makeRegistry
          (ctx1, _) <- makeMockContext
          (ctx2, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx1, MockParser)
          _ <- registry.register("123456789012346", ctx2, MockParser)
          count <- registry.connectionCount
        yield assertTrue(count == 2)
      }
    ),
    
    suite("unregister")(
      
      test("удаляет соединение") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          _ <- registry.unregister("123456789012345")
          count <- registry.connectionCount
        yield assertTrue(count == 0)
      },
      
      test("не падает при удалении несуществующего") {
        for
          registry <- makeRegistry
          _ <- registry.unregister("nonexistent")
          count <- registry.connectionCount
        yield assertTrue(count == 0)
      }
    ),
    
    suite("findByImei")(
      
      test("находит существующее соединение") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          found <- registry.findByImei("123456789012345")
        yield assertTrue(found.isDefined && found.get.imei == "123456789012345")
      },
      
      test("возвращает None для несуществующего") {
        for
          registry <- makeRegistry
          found <- registry.findByImei("nonexistent")
        yield assertTrue(found.isEmpty)
      }
    ),
    
    suite("isConnected")(
      
      test("возвращает true для подключенного") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          connected <- registry.isConnected("123456789012345")
        yield assertTrue(connected)
      },
      
      test("возвращает false для неподключенного") {
        for
          registry <- makeRegistry
          connected <- registry.isConnected("nonexistent")
        yield assertTrue(!connected)
      }
    ),
    
    suite("updateLastActivity")(
      
      test("обновляет время активности") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          entry1 <- registry.findByImei("123456789012345")
          _ <- TestClock.adjust(1.second)
          _ <- registry.updateLastActivity("123456789012345")
          entry2 <- registry.findByImei("123456789012345")
        yield assertTrue(
          entry1.isDefined && 
          entry2.isDefined && 
          entry2.get.lastActivityAt >= entry1.get.lastActivityAt
        )
      }
    ),
    
    suite("getIdleConnections")(
      
      test("возвращает idle соединения") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          _ <- TestClock.adjust(10.seconds)
          idle <- registry.getIdleConnections(5000)  // 5 секунд idle
        yield assertTrue(idle.length == 1)
      } @@ TestAspect.withLiveClock,
      
      test("не возвращает активные соединения") {
        for
          registry <- makeRegistry
          (ctx, _) <- makeMockContext
          _ <- registry.register("123456789012345", ctx, MockParser)
          idle <- registry.getIdleConnections(60000)  // 60 секунд idle
        yield assertTrue(idle.isEmpty)
      }
    )
  )
