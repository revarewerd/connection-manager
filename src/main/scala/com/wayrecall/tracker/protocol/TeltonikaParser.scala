package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}
import java.nio.charset.StandardCharsets

/**
 * Парсер протокола Teltonika Codec 8/8E
 * 
 * Формат пакета:
 * [Preamble 4B][Data Length 4B][Codec ID 1B][Records 1B][AVL Data][Records 1B][CRC 4B]
 * 
 * AVL Record:
 * [Timestamp 8B][Priority 1B][Longitude 4B][Latitude 4B][Altitude 2B]
 * [Angle 2B][Satellites 1B][Speed 2B][IO Elements]
 */
class TeltonikaParser extends ProtocolParser:
  
  override val protocolName: String = "teltonika"
  
  // Codec 8 и 8E идентификаторы
  private val CODEC_8: Byte = 0x08
  private val CODEC_8E: Byte = 0x8E.toByte
  
  /**
   * Парсит IMEI из первого пакета
   * Формат: [2B length][IMEI string]
   */
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    ZIO.attempt {
      if buffer.readableBytes() < 2 then
        throw new Exception("Недостаточно данных для длины IMEI")
      
      val length = buffer.readUnsignedShort()
      
      if buffer.readableBytes() < length then
        throw new Exception(s"Недостаточно данных для IMEI: нужно $length, доступно ${buffer.readableBytes()}")
      
      val imeiBytes = new Array[Byte](length)
      buffer.readBytes(imeiBytes)
      val imei = new String(imeiBytes, StandardCharsets.US_ASCII)
      
      // Валидация IMEI: должен быть числовой, 15 символов
      if !imei.forall(_.isDigit) || imei.length != 15 then
        throw new Exception(s"Невалидный IMEI: $imei")
      
      imei
    }.mapError(e => ProtocolError.ParseError(e.getMessage))
  
  /**
   * Парсит AVL данные из пакета
   */
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    ZIO.attempt {
      // Минимальный размер пакета: 4(preamble) + 4(length) + 1(codec) + 1(records) + 1(records) + 4(crc) = 15
      if buffer.readableBytes() < 15 then
        throw new Exception(s"Слишком маленький пакет: ${buffer.readableBytes()} байт")
      
      // Читаем preamble (должен быть 0x00000000)
      val preamble = buffer.readInt()
      if preamble != 0 then
        throw new Exception(s"Невалидный preamble: $preamble")
      
      // Длина данных (без preamble и data length)
      val dataLength = buffer.readUnsignedInt().toInt
      
      if buffer.readableBytes() < dataLength + 4 then // +4 для CRC
        throw new Exception(s"Недостаточно данных: нужно ${dataLength + 4}, доступно ${buffer.readableBytes()}")
      
      // Сохраняем позицию для CRC проверки
      val dataStartIndex = buffer.readerIndex()
      
      // Codec ID
      val codecId = buffer.readByte()
      if codecId != CODEC_8 && codecId != CODEC_8E then
        throw new Exception(s"Неподдерживаемый codec: $codecId")
      
      val isCodec8E = codecId == CODEC_8E
      
      // Количество записей
      val recordCount = buffer.readUnsignedByte()
      
      // Парсим AVL записи
      val records = (0 until recordCount).map { _ =>
        parseAvlRecord(buffer, imei, isCodec8E)
      }.toList
      
      // Проверяем количество записей в конце
      val recordCount2 = buffer.readUnsignedByte()
      if recordCount != recordCount2 then
        throw new Exception(s"Количество записей не совпадает: $recordCount != $recordCount2")
      
      // Читаем и проверяем CRC
      val receivedCrc = buffer.readInt()
      
      // Вычисляем CRC для данных (от codec ID до второго record count включительно)
      val dataEndIndex = buffer.readerIndex() - 4 // исключаем CRC
      buffer.readerIndex(dataStartIndex)
      val dataForCrc = new Array[Byte](dataLength)
      buffer.readBytes(dataForCrc)
      buffer.readerIndex(dataEndIndex + 4) // восстанавливаем позицию после CRC
      
      val calculatedCrc = calculateCrc16(dataForCrc)
      if calculatedCrc != receivedCrc then
        throw new Exception(s"CRC не совпадает: calculated=$calculatedCrc, received=$receivedCrc")
      
      records
    }.mapError(e => ProtocolError.ParseError(e.getMessage))
  
  /**
   * Парсит одну AVL запись
   */
  private def parseAvlRecord(buffer: ByteBuf, imei: String, isCodec8E: Boolean): GpsRawPoint =
    // Timestamp (миллисекунды с 1970)
    val timestamp = buffer.readLong()
    
    // Priority (0=Low, 1=High, 2=Panic)
    val priority = buffer.readUnsignedByte()
    
    // GPS данные
    val longitude = buffer.readInt().toDouble / 10000000.0 // degrees * 10^7
    val latitude = buffer.readInt().toDouble / 10000000.0
    val altitude = buffer.readUnsignedShort()
    val angle = buffer.readUnsignedShort()
    val satellites = buffer.readUnsignedByte()
    val speed = buffer.readUnsignedShort()
    
    // IO Elements (пропускаем для базовой реализации)
    skipIoElements(buffer, isCodec8E)
    
    GpsRawPoint(
      imei = imei,
      latitude = latitude,
      longitude = longitude,
      altitude = altitude,
      speed = speed,
      angle = angle,
      satellites = satellites,
      timestamp = timestamp
    )
  
  /**
   * Пропускает IO элементы в записи
   * 
   * IO элементы содержат телеметрию: напряжение, топливо, температуру и т.д.
   * В базовой реализации мы их игнорируем, но двигаем указатель чтения буфера.
   * 
   * Примечание: используем val _ = для явного указания, что значения отбрасываются.
   * Это ФП-идиоматичный способ показать намеренный discard.
   */
  private def skipIoElements(buffer: ByteBuf, isCodec8E: Boolean): Unit =
    if isCodec8E then
      // Codec 8E: количество IO элементов как 2 байта
      val _ = buffer.readUnsignedShort() // eventIoId - отбрасываем
      val _ = buffer.readUnsignedShort() // totalIoCount - отбрасываем
      
      // 1-байтовые IO
      val count1 = buffer.readUnsignedShort()
      (0 until count1).foreach { _ =>
        val _ = buffer.readUnsignedShort() // id - отбрасываем
        val _ = buffer.readByte()          // value - отбрасываем
      }
      
      // 2-байтовые IO
      val count2 = buffer.readUnsignedShort()
      (0 until count2).foreach { _ =>
        val _ = buffer.readUnsignedShort() // id - отбрасываем
        val _ = buffer.readShort()         // value - отбрасываем
      }
      
      // 4-байтовые IO
      val count4 = buffer.readUnsignedShort()
      (0 until count4).foreach { _ =>
        val _ = buffer.readUnsignedShort() // id - отбрасываем
        val _ = buffer.readInt()           // value - отбрасываем
      }
      
      // 8-байтовые IO
      val count8 = buffer.readUnsignedShort()
      (0 until count8).foreach { _ =>
        val _ = buffer.readUnsignedShort() // id - отбрасываем
        val _ = buffer.readLong()          // value - отбрасываем
      }
      
      // Variable length IO (только в Codec 8E)
      val countX = buffer.readUnsignedShort()
      (0 until countX).foreach { _ =>
        val _ = buffer.readUnsignedShort() // id - отбрасываем
        val len = buffer.readUnsignedShort()
        buffer.skipBytes(len) // value - пропускаем
      }
    else
      // Codec 8: количество IO элементов как 1 байт
      val _ = buffer.readUnsignedByte() // eventIoId - отбрасываем
      val _ = buffer.readUnsignedByte() // totalIoCount - отбрасываем
      
      // 1-байтовые IO
      val count1 = buffer.readUnsignedByte()
      (0 until count1).foreach { _ =>
        val _ = buffer.readUnsignedByte() // id - отбрасываем
        val _ = buffer.readByte()         // value - отбрасываем
      }
      
      // 2-байтовые IO
      val count2 = buffer.readUnsignedByte()
      (0 until count2).foreach { _ =>
        val _ = buffer.readUnsignedByte() // id - отбрасываем
        val _ = buffer.readShort()        // value - отбрасываем
      }
      
      // 4-байтовые IO
      val count4 = buffer.readUnsignedByte()
      (0 until count4).foreach { _ =>
        val _ = buffer.readUnsignedByte() // id - отбрасываем
        val _ = buffer.readInt()          // value - отбрасываем
      }
      
      // 8-байтовые IO
      val count8 = buffer.readUnsignedByte()
      (0 until count8).foreach { _ =>
        val _ = buffer.readUnsignedByte() // id - отбрасываем
        val _ = buffer.readLong()         // value - отбрасываем
      }
  
  /**
   * Создает ACK пакет для подтверждения приема данных
   * Формат: 4 байта с количеством принятых записей
   */
  override def ack(recordCount: Int): ByteBuf =
    val buffer = Unpooled.buffer(4)
    buffer.writeInt(recordCount)
    buffer
  
  /**
   * Создает ACK для IMEI
   * 0x01 = принят, 0x00 = отклонен
   */
  override def imeiAck(accepted: Boolean): ByteBuf =
    val buffer = Unpooled.buffer(1)
    buffer.writeByte(if accepted then 0x01 else 0x00)
    buffer
  
  /**
   * Кодирует команду для отправки на трекер (Codec 12)
   * 
   * Формат Codec 12:
   * [Preamble 4B 0x00000000][Data Length 4B][Codec ID 1B = 0x0C]
   * [Command Quantity 1B][Command Type 1B][Command Size 4B][Command Data]
   * [Command Quantity 1B][CRC 4B]
   */
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    ZIO.attempt {
      import com.wayrecall.tracker.domain.*
      
      val commandText = command match
        case _: RebootCommand => "restart"
        case SetIntervalCommand(_, _, _, interval) => s"setparam 1001:$interval"
        case _: RequestPositionCommand => "getrecord"
        case SetOutputCommand(_, _, _, idx, enabled) => s"setdigout ${if enabled then 1 else 0}"
        case CustomCommand(_, _, _, text) => text
      
      val commandBytes = commandText.getBytes(StandardCharsets.UTF_8)
      
      // Размер данных: codec(1) + qty(1) + type(1) + size(4) + data + qty(1)
      val dataLength = 1 + 1 + 1 + 4 + commandBytes.length + 1
      
      val buffer = Unpooled.buffer(4 + 4 + dataLength + 4) // preamble + length + data + crc
      
      // Preamble (4 bytes of zeros)
      buffer.writeInt(0)
      
      // Data length
      buffer.writeInt(dataLength)
      
      // Codec ID = 0x0C (Codec 12)
      buffer.writeByte(0x0C)
      
      // Command quantity
      buffer.writeByte(1)
      
      // Command type (0x05 = command)
      buffer.writeByte(0x05)
      
      // Command size
      buffer.writeInt(commandBytes.length)
      
      // Command data
      buffer.writeBytes(commandBytes)
      
      // Command quantity (again)
      buffer.writeByte(1)
      
      // Calculate CRC for data portion (from codec to second quantity)
      val dataForCrc = new Array[Byte](dataLength)
      buffer.getBytes(8, dataForCrc) // skip preamble and length
      val crc = calculateCrc16(dataForCrc)
      
      // CRC
      buffer.writeInt(crc)
      
      buffer
    }.mapError(e => ProtocolError.ParseError(s"Failed to encode Teltonika command: ${e.getMessage}"))
  
  /**
   * Вычисляет CRC-16-IBM (polynomial 0xA001)
   * 
   * Алгоритм:
   * 1. XOR текущего CRC с байтом данных
   * 2. 8 итераций: если младший бит = 1, то XOR с полиномом
   * 
   * ✅ Чисто функциональная реализация через foldLeft (без var)
   */
  private def calculateCrc16(data: Array[Byte]): Int =
    data.foldLeft(0) { (crc, byte) =>
      // XOR CRC с текущим байтом
      val xored = crc ^ (byte & 0xFF)
      
      // 8 итераций сдвига через foldLeft
      (0 until 8).foldLeft(xored) { (acc, _) =>
        if (acc & 1) != 0 then
          (acc >> 1) ^ 0xA001
        else
          acc >> 1
      }
    }

object TeltonikaParser:
  /**
   * ZIO Layer для TeltonikaParser
   */
  val live: ULayer[ProtocolParser] = ZLayer.succeed(new TeltonikaParser)
