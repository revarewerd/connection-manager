package com.wayrecall.tracker.domain

import zio.json.*

/**
 * Перечисления протоколов GPS-трекеров
 * 
 * Каждый протокол соответствует конкретному производителю / семейству трекеров.
 * Используется для:
 * - Кэширования определённого протокола в ConnectionState
 * - Логирования и маршрутизации в Kafka
 * - Выбора парсера в MultiProtocolParser
 * 
 * Из legacy STELS: wialon(9087), teltonika(9088), ruptela(9089),
 * gosafe(9086), navtelecomf6(9085), skysim(9084), autophonemayak(9083), dtm(9082)
 */
enum Protocol derives JsonCodec:
  case Teltonika        // Teltonika Codec 8/8E — binary, CRC-16-IBM
  case Wialon           // Wialon IPS — text (#L#, #D#, #SD#)
  case WialonBinary     // Wialon Binary — LE, null-terminated IMEI, posinfo blocks
  case Ruptela          // Ruptela GPS Plus/Pro — binary, coords × 10^7, CRC-16
  case NavTelecom       // NavTelecom FLEX — binary LE, signature "*>", CRC-16-CCITT
  case GoSafe           // GoSafe G3S/G6S — ASCII, GPRMC-подобный формат
  case SkySim           // SkySim (SkyPatrol) — binary, Motorola modem
  case AutophoneMayak   // Автофон Маяк — binary пакеты, координаты × 10^6
  case Dtm              // ДТМ (DTM) — binary, фиксированная структура
  case Galileosky       // Galileosky — binary LE, tag-based protocol, CRC echo
  case Concox           // Concox/GL06 — binary BE, 0x7878 framing, CRC16-X25
  case TK102            // TK102 — ASCII, '(' framing, NMEA coords
  case TK103            // TK103 — ASCII, идентичен TK102 с другим именем
  case Arnavi           // Arnavi — CSV $AV,V2/V3, NMEA coords, RCPTOK ACK
  case Neomatica        // Neomatica — отдельный порт, гибридный парсер с авто-fallback
  case Adm              // ADM-107 — binary LE, float coords, conditional ACK
  case Gtlt             // GTLT3MT1 (GT-LITE) — CSV '#'-delimited, *HQ header
  case MicroMayak       // Автофон МикроМаяк — binary, packed GPS bitfields
  case Unknown          // Неопределённый протокол (до автодетекта)

sealed trait ProtocolError extends Throwable:
  def message: String
  override def getMessage: String = message

object ProtocolError:
  case class InvalidChecksum(actual: Int, expected: Int) extends ProtocolError:
    def message = s"CRC checksum mismatch: actual=$actual, expected=$expected"
  
  case class InvalidCodec(codec: Byte) extends ProtocolError:
    def message = s"Unsupported codec ID: $codec"
  
  case class ParseError(reason: String) extends ProtocolError:
    def message = s"Parse error: $reason"
  
  case class InsufficientData(required: Int, available: Int) extends ProtocolError:
    def message = s"Insufficient data: need $required, have $available"
  
  case class InvalidImei(imei: String) extends ProtocolError:
    def message = s"Invalid IMEI: $imei"
  
  case class UnknownDevice(imei: String) extends ProtocolError:
    def message = s"Unknown device IMEI: $imei"
  
  case class UnsupportedProtocol(protocol: String) extends ProtocolError:
    def message = s"Unsupported protocol: $protocol"
  
  case class ProtocolDetectionFailed(reason: String) extends ProtocolError:
    def message = s"Protocol detection failed: $reason"

sealed trait FilterError extends Throwable:
  def message: String

object FilterError:
  case class ExcessiveSpeed(speed: Int, maxAllowed: Int) extends FilterError:
    def message = s"Speed $speed km/h exceeds maximum $maxAllowed km/h"
  
  case class InvalidCoordinates(lat: Double, lon: Double) extends FilterError:
    def message = s"Invalid coordinates: lat=$lat, lon=$lon (must be -90..90, -180..180)"
  
  case class FutureTimestamp(timestamp: Long, now: Long) extends FilterError:
    def message = s"Timestamp $timestamp is in the future (now=$now)"
  
  case class Teleportation(distance: Double, maxAllowed: Double) extends FilterError:
    def message = s"Teleportation detected: distance $distance meters exceeds max $maxAllowed"

sealed trait RedisError extends Throwable:
  def message: String

object RedisError:
  case class ConnectionFailed(reason: String) extends RedisError:
    def message = s"Redis connection failed: $reason"
  
  case class OperationFailed(reason: String) extends RedisError:
    def message = s"Redis operation failed: $reason"
  
  case class DecodingFailed(reason: String) extends RedisError:
    def message = s"Failed to decode Redis value: $reason"

sealed trait KafkaError extends Throwable:
  def message: String

object KafkaError:
  case class ProducerError(reason: String) extends KafkaError:
    def message = s"Kafka producer error: $reason"
  
  case class SerializationError(reason: String) extends KafkaError:
    def message = s"Failed to serialize message: $reason"
