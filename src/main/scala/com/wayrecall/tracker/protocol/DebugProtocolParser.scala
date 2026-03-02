package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.ByteBuf
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

// ============================================================
// DEBUG ОБЁРТКА ДЛЯ ПАРСЕРОВ ПРОТОКОЛОВ
// ============================================================

/**
 * Обёртка вокруг любого ProtocolParser, добавляющая подробные debug логи.
 * 
 * Логирует:
 * - Hex-дамп КАЖДОГО входящего пакета (первые 1024 байт)
 * - Размер пакета в байтах
 * - Результат парсинга IMEI (успех/ошибка)
 * - Каждую распарсенную GPS точку с ВСЕМИ полями:
 *   lat, lon, speed, angle, altitude, satellites, timestamp, hdop
 * - Время парсинга (наносекунды)
 * - Количество точек до/после парсинга
 * 
 * ВАЖНО: Это ВРЕМЕННАЯ мера для тестового стенда!
 * В production (CM v2.0+) эти логи будут отключены или перемещены на TRACE уровень.
 * 
 * Использование:
 *   val debugParser = DebugProtocolParser(originalParser)
 *   // Теперь все вызовы parseImei/parseData будут логировать подробности
 * 
 * @param inner Оборачиваемый парсер протокола
 */
final case class DebugProtocolParser(inner: ProtocolParser) extends ProtocolParser:
  
  override val protocolName: String = inner.protocolName
  
  /**
   * Парсит IMEI с подробным логированием
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    for
      // Логируем hex-дамп входящего пакета
      _ <- logHexDump("IMEI", buffer)
      startNanos <- Clock.nanoTime
      
      // Вызываем реальный парсер
      result <- inner.parseImei(buffer)
                  .tap { imei =>
                    for
                      elapsed <- Clock.nanoTime.map(_ - startNanos)
                      elapsedMs = elapsed / 1_000_000.0
                      _ <- ZIO.logInfo(
                        s"""[DEBUG-PARSE] ✓ IMEI УСПЕШНО РАСПАРСЕН
                           |  ├── Протокол:  ${inner.protocolName}
                           |  ├── IMEI:      $imei
                           |  ├── Длина:     ${imei.length} символов
                           |  ├── Цифры:     ${imei.forall(_.isDigit)}
                           |  └── Время:     ${f"$elapsedMs%.2f"} мс""".stripMargin
                      )
                    yield ()
                  }
                  .tapError { error =>
                    for
                      elapsed <- Clock.nanoTime.map(_ - startNanos)
                      elapsedMs = elapsed / 1_000_000.0
                      _ <- ZIO.logWarning(
                        s"""[DEBUG-PARSE] ✗ ОШИБКА ПАРСИНГА IMEI
                           |  ├── Протокол:  ${inner.protocolName}
                           |  ├── Ошибка:    ${error.message}
                           |  ├── Тип:       ${error.getClass.getSimpleName}
                           |  └── Время:     ${f"$elapsedMs%.2f"} мс""".stripMargin
                      )
                    yield ()
                  }
    yield result
  
  /**
   * Парсит данные GPS точек с подробным логированием КАЖДОЙ точки
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    for
      // Логируем hex-дамп входящего пакета данных
      _ <- logHexDump(s"DATA($imei)", buffer)
      startNanos <- Clock.nanoTime
      bufferSize = buffer.readableBytes()
      
      // Вызываем реальный парсер
      result <- inner.parseData(buffer, imei)
                  .tap { points =>
                    for
                      elapsed <- Clock.nanoTime.map(_ - startNanos)
                      elapsedMs = elapsed / 1_000_000.0
                      
                      // Логируем заголовок
                      _ <- ZIO.logInfo(
                        s"""[DEBUG-PARSE] ✓ DATA ПАКЕТ РАСПАРСЕН
                           |  ├── Протокол:     ${inner.protocolName}
                           |  ├── IMEI:         $imei
                           |  ├── Размер:       $bufferSize байт
                           |  ├── Точек:        ${points.size}
                           |  └── Время:        ${f"$elapsedMs%.2f"} мс""".stripMargin
                      )
                      
                      // Логируем КАЖДУЮ точку со всеми полями
                      _ <- ZIO.foreachDiscard(points.zipWithIndex) { case (p, idx) =>
                        val ts = java.time.Instant.ofEpochMilli(p.timestamp)
                        ZIO.logInfo(
                          s"""[DEBUG-POINT] Точка #${idx + 1}/${points.size} [${inner.protocolName}] IMEI=$imei
                             |  ├── Координаты:  lat=${p.latitude}, lon=${p.longitude}
                             |  ├── Скорость:    ${p.speed} км/ч
                             |  ├── Курс:        ${p.angle}°
                             |  ├── Высота:      ${p.altitude} м
                             |  ├── Спутники:    ${p.satellites}
                             |  ├── Время устр.: $ts (epoch: ${p.timestamp})
                             |  └── Валидность:  lat∈[-90,90]=${p.latitude >= -90 && p.latitude <= 90}, lon∈[-180,180]=${p.longitude >= -180 && p.longitude <= 180}""".stripMargin
                        )
                      }
                    yield ()
                  }
                  .tapError { error =>
                    for
                      elapsed <- Clock.nanoTime.map(_ - startNanos)
                      elapsedMs = elapsed / 1_000_000.0
                      _ <- ZIO.logWarning(
                        s"""[DEBUG-PARSE] ✗ ОШИБКА ПАРСИНГА DATA
                           |  ├── Протокол:  ${inner.protocolName}
                           |  ├── IMEI:      $imei
                           |  ├── Размер:    $bufferSize байт
                           |  ├── Ошибка:    ${error.message}
                           |  ├── Тип:       ${error.getClass.getSimpleName}
                           |  └── Время:     ${f"$elapsedMs%.2f"} мс""".stripMargin
                      )
                    yield ()
                  }
    yield result
  
  /**
   * ACK делегируем напрямую (без логирования — ACK маленькие)
   */
  override def ack(recordCount: Int): ByteBuf = inner.ack(recordCount)
  
  /**
   * IMEI ACK делегируем напрямую
   */
  override def imeiAck(accepted: Boolean): ByteBuf = inner.imeiAck(accepted)
  
  /**
   * Кодирование команд с логированием
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    for
      _ <- ZIO.logInfo(s"[DEBUG-CMD] Кодирование команды для ${inner.protocolName}: ${command.getClass.getSimpleName}")
      result <- inner.encodeCommand(command)
      _ <- ZIO.logInfo(
        s"""[DEBUG-CMD] ✓ Команда закодирована
           |  ├── Протокол:  ${inner.protocolName}
           |  ├── Команда:   ${command.getClass.getSimpleName}
           |  └── Размер:    ${result.readableBytes()} байт""".stripMargin
      )
    yield result
  
  /**
   * Логирует hex-дамп пакета (первые 1024 байт)
   * 
   * Формат: смещение | hex байтов | ASCII представление
   * Пример:
   *   0000: 00 0F 33 35 32 30 39 34|30 30 37 34 35 31 36 32  ..352094|00745162
   */
  private def logHexDump(label: String, buffer: ByteBuf): UIO[Unit] =
    ZIO.succeed {
      val readable = buffer.readableBytes()
      val dumpSize = math.min(readable, 1024)
      val bytes = new Array[Byte](dumpSize)
      buffer.getBytes(buffer.readerIndex(), bytes)
      
      val hexLines = bytes.grouped(16).zipWithIndex.map { case (line, lineIdx) =>
        val offset = f"${lineIdx * 16}%04X"
        val hex = line.map(b => f"${b & 0xFF}%02X").mkString(" ")
        val ascii = line.map(b => if b >= 32 && b <= 126 then b.toChar else '.').mkString
        s"  $offset: ${hex.padTo(47, ' ')} $ascii"
      }.mkString("\n")
      
      val truncated = if readable > 1024 then s" (показано $dumpSize из $readable байт)" else ""
      
      (label, readable, hexLines, truncated)
    }.flatMap { case (label, readable, hexLines, truncated) =>
      ZIO.logInfo(
        s"""[DEBUG-HEX] $label — $readable байт$truncated
           |$hexLines""".stripMargin
      )
    }

object DebugProtocolParser:
  
  /**
   * Оборачивает парсер в debug-обёртку
   * 
   * Используется в TcpServer при создании pipeline.
   * Пример:
   *   val parser = if config.debugMode then DebugProtocolParser(TeltonikaParser())
   *                else TeltonikaParser()
   */
  def wrap(parser: ProtocolParser): DebugProtocolParser = DebugProtocolParser(parser)
  
  /**
   * Оборачивает MultiProtocolParser.ParserEntry в debug-версию
   */
  def wrapEntries(entries: List[MultiProtocolParser.ParserEntry]): List[MultiProtocolParser.ParserEntry] =
    entries.map(e => e.copy(parser = DebugProtocolParser(e.parser)))
