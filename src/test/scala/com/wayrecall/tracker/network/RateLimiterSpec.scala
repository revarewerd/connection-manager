package com.wayrecall.tracker.network

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Тесты для RateLimiter
 * 
 * Проверяем:
 * 1. Разрешение соединений в пределах лимита
 * 2. Блокировка при превышении лимита
 * 3. Восстановление после истечения окна
 * 4. Изоляция между разными IP
 */
object RateLimiterSpec extends ZIOSpecDefault:
  
  // Создаём тестовый RateLimiter с коротким окном
  private def makeTestLimiter(
    maxConnections: Int = 5,
    windowSeconds: Int = 1
  ): UIO[RateLimiter] =
    Ref.make(Map.empty[String, ConnectionRecord]).map { ref =>
      RateLimiter.Live(
        recordsRef = ref,
        maxConnectionsPerIp = maxConnections,
        windowMs = windowSeconds * 1000L,
        cleanupIntervalMs = 60 * 1000L
      )
    }
  
  def spec = suite("RateLimiter")(
    
    suite("tryAcquire")(
      
      test("разрешает соединения в пределах лимита") {
        for
          limiter <- makeTestLimiter(maxConnections = 3)
          r1 <- limiter.tryAcquire("192.168.1.1")
          r2 <- limiter.tryAcquire("192.168.1.1")
          r3 <- limiter.tryAcquire("192.168.1.1")
        yield assertTrue(r1 && r2 && r3)
      },
      
      test("блокирует при превышении лимита") {
        for
          limiter <- makeTestLimiter(maxConnections = 2)
          _ <- limiter.tryAcquire("192.168.1.1")
          _ <- limiter.tryAcquire("192.168.1.1")
          r3 <- limiter.tryAcquire("192.168.1.1")
        yield assertTrue(!r3)
      },
      
      test("изолирует разные IP адреса") {
        for
          limiter <- makeTestLimiter(maxConnections = 2)
          _ <- limiter.tryAcquire("192.168.1.1")
          _ <- limiter.tryAcquire("192.168.1.1")
          r1 <- limiter.tryAcquire("192.168.1.1")  // Должен быть заблокирован
          r2 <- limiter.tryAcquire("192.168.1.2")  // Другой IP - должен быть разрешён
        yield assertTrue(!r1 && r2)
      },
      
      test("восстанавливается после истечения окна") {
        for
          limiter <- makeTestLimiter(maxConnections = 1, windowSeconds = 1)
          r1 <- limiter.tryAcquire("192.168.1.1")
          r2 <- limiter.tryAcquire("192.168.1.1")  // Должен быть заблокирован
          _ <- TestClock.adjust(2.seconds)         // Ждём истечения окна
          r3 <- limiter.tryAcquire("192.168.1.1")  // Должен быть разрешён
        yield assertTrue(r1 && !r2 && r3)
      } @@ TestAspect.withLiveClock
    ),
    
    suite("getConnectionCount")(
      
      test("возвращает 0 для нового IP") {
        for
          limiter <- makeTestLimiter()
          count <- limiter.getConnectionCount("10.0.0.1")
        yield assertTrue(count == 0)
      },
      
      test("корректно считает соединения") {
        for
          limiter <- makeTestLimiter()
          _ <- limiter.tryAcquire("10.0.0.1")
          _ <- limiter.tryAcquire("10.0.0.1")
          _ <- limiter.tryAcquire("10.0.0.1")
          count <- limiter.getConnectionCount("10.0.0.1")
        yield assertTrue(count == 3)
      }
    ),
    
    suite("getStats")(
      
      test("возвращает пустую статистику изначально") {
        for
          limiter <- makeTestLimiter()
          stats <- limiter.getStats
        yield assertTrue(stats.isEmpty)
      },
      
      test("возвращает статистику по всем IP") {
        for
          limiter <- makeTestLimiter()
          _ <- limiter.tryAcquire("192.168.1.1")
          _ <- limiter.tryAcquire("192.168.1.1")
          _ <- limiter.tryAcquire("192.168.1.2")
          stats <- limiter.getStats
        yield assertTrue(
          stats.get("192.168.1.1").contains(2) &&
          stats.get("192.168.1.2").contains(1)
        )
      }
    )
  )
