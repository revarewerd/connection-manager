package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.ByteBuf
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Общий trait для всех парсеров протоколов GPS трекеров
 */
trait ProtocolParser:
  /**
   * Парсит IMEI из первого пакета соединения
   */
  def parseImei(buffer: ByteBuf): IO[ProtocolError, String]
  
  /**
   * Парсит данные GPS точек из пакета
   */
  def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]]
  
  /**
   * Создает ACK пакет для подтверждения приема
   */
  def ack(recordCount: Int): ByteBuf
  
  /**
   * Создает ACK для подтверждения IMEI
   */
  def imeiAck(accepted: Boolean): ByteBuf
  
  /**
   * Кодирует команду для отправки на трекер
   */
  def encodeCommand(command: Command): IO[ProtocolError, ByteBuf]
