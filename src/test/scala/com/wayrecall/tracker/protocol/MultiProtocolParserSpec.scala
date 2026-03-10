package com.wayrecall.tracker.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{Protocol, ProtocolError, GpsRawPoint, Command, RequestPositionCommand}

/**
 * Тесты для MultiProtocolParser
 * 
 * Проверяем:
 * 1. quickDetect — определение по magic bytes
 * 2. DetectionResult — структура результата
 * 3. ParserEntry — реестр парсеров
 * 4. defaultParsers — список и порядок
 * 5. detect — обработка ошибок
 * 6. asProtocolParser — обёртка
 */
object MultiProtocolParserSpec extends ZIOSpecDefault:
  
  def spec = suite("MultiProtocolParser")(
    
    suite("quickDetect — определение протокола по magic bytes")(
      
      test("определяет NavTelecom по сигнатуре *> (0x2A3E)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x2A)
        buf.writeByte(0x3E)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.NavTelecom))
      },
      
      test("определяет Teltonika по 0x000F (IMEI length=15)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x00)
        buf.writeByte(0x0F)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Teltonika))
      },
      
      test("определяет Concox по 0x7878") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x78)
        buf.writeByte(0x78)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Concox))
      },
      
      test("определяет Galileosky по 0x01 header") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x01)
        buf.writeByte(0x00)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Galileosky))
      },
      
      test("определяет Wialon по '#' (0x23)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x23)
        buf.writeByte(0x4C)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Wialon))
      },
      
      test("определяет TK102 по '(' (0x28)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x28)
        buf.writeByte(0x00)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.TK102))
      },
      
      test("определяет Gtlt по '*H' (0x2A48)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x2A)
        buf.writeByte(0x48)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Gtlt))
      },
      
      test("определяет Arnavi по '$A' (0x2441)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)
        buf.writeByte(0x41)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Arnavi))
      },
      
      test("определяет GoSafe по '$G' (0x2447)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)
        buf.writeByte(0x47)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.GoSafe))
      },
      
      test("определяет GoSafe по '$*' (0x242A)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)
        buf.writeByte(0x2A)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.GoSafe))
      },
      
      test("определяет MicroMayak по '$' (0x24) без Arnavi/GoSafe") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x24)
        buf.writeByte(0x00) // Не 'A', не 'G', не '*'
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.MicroMayak))
      },
      
      test("определяет SkySim по 0xF0") {
        val buf = Unpooled.buffer()
        buf.writeByte(0xF0)
        buf.writeByte(0x00)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.SkySim))
      },
      
      test("определяет AutophoneMayak по 'M' (0x4D)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x4D)
        buf.writeByte(0x00)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.AutophoneMayak))
      },
      
      test("определяет Dtm по '{' (0x7B)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x7B)
        buf.writeByte(0x00)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.contains(Protocol.Dtm))
      },
      
      test("возвращает None для неизвестных magic bytes") {
        val buf = Unpooled.buffer()
        buf.writeByte(0xFF)
        buf.writeByte(0xFF)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.isEmpty)
      },
      
      test("возвращает None для пустого буфера") {
        val buf = Unpooled.buffer(0)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.isEmpty)
      },
      
      test("возвращает None для буфера с 1 байтом (нужно минимум 2)") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x2A)
        val result = MultiProtocolParser.quickDetect(buf)
        buf.release()
        assertTrue(result.isEmpty)
      },
      
      test("не изменяет readerIndex буфера") {
        val buf = Unpooled.buffer()
        buf.writeByte(0x2A)
        buf.writeByte(0x3E)
        val indexBefore = buf.readerIndex()
        MultiProtocolParser.quickDetect(buf)
        val indexAfter = buf.readerIndex()
        buf.release()
        assertTrue(indexBefore == indexAfter)
      }
    ),
    
    suite("defaultParsers — реестр")(
      
      test("содержит все основные протоколы") {
        val protocols = MultiProtocolParser.defaultParsers.map(_.protocol)
        assertTrue(
          protocols.contains(Protocol.Teltonika) &&
          protocols.contains(Protocol.NavTelecom) &&
          protocols.contains(Protocol.Ruptela) &&
          protocols.contains(Protocol.Galileosky) &&
          protocols.contains(Protocol.Concox) &&
          protocols.contains(Protocol.Wialon) &&
          protocols.contains(Protocol.GoSafe) &&
          protocols.contains(Protocol.SkySim)
        )
      },
      
      test("содержит 16 парсеров (все протоколы)") {
        assertTrue(MultiProtocolParser.defaultParsers.size == 16)
      },
      
      test("Teltonika первый (наиболее специфичный magic byte)") {
        val first = MultiProtocolParser.defaultParsers.head
        assertTrue(first.protocol == Protocol.Teltonika)
      },
      
      test("Wialon последний (наименее специфичный)") {
        val last = MultiProtocolParser.defaultParsers.last
        // WialonAdapterParser — обёртка, считается Wialon
        assertTrue(
          last.protocol == Protocol.Wialon ||
          last.parser.protocolName.toLowerCase.contains("wialon")
        )
      }
    ),
    
    suite("detect — обнаружение протокола")(
      
      test("ProtocolDetectionFailed при пустом списке парсеров") {
        val buf = Unpooled.buffer()
        buf.writeBytes("Hello".getBytes)
        
        val result = MultiProtocolParser.detect(buf, parsers = List.empty)
          .flip // Ожидаем ошибку
        
        result.map { error =>
          buf.release()
          assertTrue(error.isInstanceOf[ProtocolError.ProtocolDetectionFailed])
        }
      },
      
      test("ProtocolDetectionFailed содержит количество попыток") {
        val buf = Unpooled.buffer()
        buf.writeBytes(Array[Byte](0xFF.toByte, 0xFF.toByte, 0xFF.toByte))
        
        val result = MultiProtocolParser.detect(buf, parsers = List.empty)
          .flip
        
        result.map { error =>
          buf.release()
          assertTrue(error.message.contains("0 попыток"))
        }
      }
    ),
    
    suite("DetectionResult — структура")(
      
      test("содержит протокол, парсер и IMEI") {
        val mockParser = new ProtocolParser:
          val protocolName = "test"
          def parseImei(buffer: ByteBuf) = ZIO.succeed("123456789012345")
          def parseData(buffer: ByteBuf, imei: String) = ZIO.succeed(List.empty)
          def ack(count: Int) = Unpooled.EMPTY_BUFFER
          def imeiAck(accepted: Boolean) = Unpooled.EMPTY_BUFFER
          def encodeCommand(command: Command) = ZIO.succeed(Unpooled.EMPTY_BUFFER)
        
        val result = MultiProtocolParser.DetectionResult(
          protocol = Protocol.Teltonika,
          parser = mockParser,
          imei = "123456789012345"
        )
        
        assertTrue(
          result.protocol == Protocol.Teltonika &&
          result.imei == "123456789012345" &&
          result.parser.protocolName == "test"
        )
      }
    ),
    
    suite("asProtocolParser — обёртка мульти-протокола")(
      
      test("protocolName = 'multi'") {
        val parser = MultiProtocolParser.asProtocolParser()
        assertTrue(parser.protocolName == "multi")
      },
      
      test("parseData без предшествующего parseImei → ProtocolDetectionFailed") {
        val parser = MultiProtocolParser.asProtocolParser(List.empty)
        val buf = Unpooled.buffer()
        buf.writeBytes("data".getBytes)
        
        val result = parser.parseData(buf, "123")
          .flip
        
        result.map { error =>
          buf.release()
          assertTrue(error.isInstanceOf[ProtocolError.ProtocolDetectionFailed])
        }
      },
      
      test("ack без определённого протокола возвращает EMPTY_BUFFER") {
        val parser = MultiProtocolParser.asProtocolParser(List.empty)
        val buf = parser.ack(1)
        assertTrue(buf.readableBytes() == 0)
      },
      
      test("imeiAck без определённого протокола возвращает EMPTY_BUFFER") {
        val parser = MultiProtocolParser.asProtocolParser(List.empty)
        val buf = parser.imeiAck(true)
        assertTrue(buf.readableBytes() == 0)
      },
      
      test("encodeCommand без parseImei → ProtocolDetectionFailed") {
        val parser = MultiProtocolParser.asProtocolParser(List.empty)
        val cmd: Command = RequestPositionCommand("cmd1", "123", java.time.Instant.now())
        
        parser.encodeCommand(cmd)
          .flip
          .map { error =>
            assertTrue(error.isInstanceOf[ProtocolError])
          }
      }
    )
  )
