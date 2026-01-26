package com.wayrecall.tracker.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Тесты для GeoMath - геометрические вычисления
 * 
 * Тестируем чистые функции без side effects.
 * Эти тесты должны быть детерминированными и быстрыми.
 */
object GeoMathSpec extends ZIOSpecDefault:
  
  def spec = suite("GeoMath")(
    
    suite("haversineDistance")(
      
      // Тест 1: Расстояние между одинаковыми точками = 0
      test("возвращает 0 для одинаковых координат") {
        val distance = GeoMath.haversineDistance(55.7558, 37.6173, 55.7558, 37.6173)
        assertTrue(distance == 0.0)
      },
      
      // Тест 2: Известное расстояние Москва - Питер (~635 км)
      test("корректно вычисляет расстояние Москва - Санкт-Петербург") {
        val moscowLat = 55.7558
        val moscowLon = 37.6173
        val spbLat = 59.9343
        val spbLon = 30.3351
        
        val distance = GeoMath.haversineDistance(moscowLat, moscowLon, spbLat, spbLon)
        val distanceKm = distance / 1000.0
        
        // Ожидаем ~633-637 км (с погрешностью)
        assertTrue(distanceKm > 630 && distanceKm < 640)
      },
      
      // Тест 3: Малое расстояние (100 метров)
      test("корректно вычисляет малые расстояния (~100м)") {
        val lat1 = 55.7558
        val lon1 = 37.6173
        val lat2 = 55.7567  // ~100м на север
        val lon2 = 37.6173
        
        val distance = GeoMath.haversineDistance(lat1, lon1, lat2, lon2)
        
        // Ожидаем ~100м с погрешностью 10%
        assertTrue(distance > 90 && distance < 110)
      },
      
      // Тест 4: Симметричность (A→B = B→A)
      test("симметричен: расстояние A→B = B→A") {
        val lat1 = 55.0
        val lon1 = 37.0
        val lat2 = 56.0
        val lon2 = 38.0
        
        val distanceAB = GeoMath.haversineDistance(lat1, lon1, lat2, lon2)
        val distanceBA = GeoMath.haversineDistance(lat2, lon2, lat1, lon1)
        
        assertTrue(distanceAB == distanceBA)
      },
      
      // Тест 5: Крайние значения координат
      test("обрабатывает крайние координаты") {
        // Северный полюс к экватору
        val distance = GeoMath.haversineDistance(90.0, 0.0, 0.0, 0.0)
        val distanceKm = distance / 1000.0
        
        // Четверть окружности Земли ~10 000 км
        assertTrue(distanceKm > 9900 && distanceKm < 10100)
      }
    )
  )
