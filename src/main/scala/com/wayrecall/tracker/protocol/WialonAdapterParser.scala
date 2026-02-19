package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Adapter для протокола Wialon, поддерживающий оба формата:
 * - Текстовый IPS: #D#..., #L#..., #P#...
 * - Бинарный: как в старом Stels
 * 
 * Auto-detection на основе первого байта:
 * - 0x23 ('#') = текстовый IPS
 * - другое = бинарный (размер пакета в little-endian)
 */
object WialonAdapterParser extends ProtocolParser:
  
  override val protocolName: String = "wialon"
  
  /**
   * Определяет какой тип протокола используется
   * @return true если текстовый IPS, false если бинарный
   */
  private def isTextFormat(buffer: ByteBuf): Boolean =
    if buffer.readableBytes() > 0 then
      val firstByte = buffer.getUnsignedByte(buffer.readerIndex())
      firstByte == 0x23 // '#'
    else
      true // по умолчанию текстовый
  
  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    if isTextFormat(buffer) then
      WialonParser.parseImei(buffer)
        .tapError(e => ZIO.logWarning(s"[WIALON-TEXT] IMEI parse error: ${e.message}"))
    else
      WialonBinaryParser.parseImei(buffer)
        .tap(imei => ZIO.logDebug(s"[WIALON-BINARY] Parsed IMEI: $imei (binary format)"))
        .tapError(e => ZIO.logWarning(s"[WIALON-BINARY] IMEI parse error: ${e.message}"))
  
  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    if isTextFormat(buffer) then
      WialonParser.parseData(buffer, imei)
        .tapError(e => ZIO.logWarning(s"[WIALON-TEXT] Data parse error for IMEI=$imei: ${e.message}"))
    else
      WialonBinaryParser.parseData(buffer, imei)
        .tap(points => ZIO.logDebug(s"[WIALON-BINARY] Parsed ${points.size} points for IMEI=$imei (binary format)"))
        .tapError(e => ZIO.logWarning(s"[WIALON-BINARY] Data parse error for IMEI=$imei: ${e.message}"))
  
  override def ack(recordCount: Int): ByteBuf =
    // Отправляем ACK только для текстового протокола
    // Бинарный протокол не требует ACK
    WialonParser.ack(recordCount)
  
  override def imeiAck(accepted: Boolean): ByteBuf =
    // Отправляем ACK только для текстового протокола
    WialonParser.imeiAck(accepted)
  
  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    WialonParser.encodeCommand(command)

