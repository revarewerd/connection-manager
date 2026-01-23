package com.wayrecall.tracker.domain

/**
 * Перечисления для протоколов и ошибок
 */

enum Protocol:
  case Teltonika
  case Wialon
  case NavTelecom

sealed trait ProtocolError extends Throwable:
  def message: String

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
