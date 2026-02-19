package com.wayrecall.tracker.domain

/**
 * ADT иерархия ошибок парсинга протоколов GPS-трекеров
 * 
 * Заменяет throw new Exception(...) на типобезопасные Either[ParseError, Result]
 * Все ошибки парсинга должны быть явными и обрабатываемыми.
 * 
 * Принципы:
 * - Никогда не падать с exception при ошибке парсинга
 * - Логировать ошибку + метрика + отправка в DLQ (Kafka)
 * - НЕ отключать TCP соединение при единичной ошибке
 * - Трекер продолжает работу
 */
sealed trait ParseError extends Product with Serializable:
  def message: String
  def protocol: String

object ParseError:
  /**
   * Недостаточно данных в буфере для парсинга
   * 
   * @param protocol Протокол (teltonika, wialon, ruptela, navtelecom)
   * @param expected Ожидаемое количество байт
   * @param actual Фактическое количество байт
   */
  final case class InsufficientData(
      protocol: String,
      expected: Int,
      actual: Int
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Недостаточно данных: ожидается $expected байт, получено $actual"

  /**
   * Неверное значение поля в пакете
   * 
   * @param protocol Протокол
   * @param field Название поля (codec, packet_type, signature, etc.)
   * @param value Неверное значение (для логирования)
   * @param context Дополнительный контекст (опционально)
   */
  final case class InvalidField(
      protocol: String,
      field: String,
      value: String,
      context: Option[String] = None
  ) extends ParseError:
    def message: String =
      val ctx = context.map(c => s" ($c)").getOrElse("")
      s"[$protocol] Неверное значение поля '$field': $value$ctx"

  /**
   * Неверный формат IMEI
   * 
   * IMEI должен быть 15 цифр (международный идентификатор мобильного оборудования)
   * Проверка: только цифры, длина 15, checksum валиден (Luhn algorithm)
   * 
   * @param protocol Протокол
   * @param imei Невалидный IMEI
   * @param reason Причина невалидности (length, format, checksum)
   */
  final case class InvalidImei(
      protocol: String,
      imei: String,
      reason: String
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Неверный IMEI '$imei': $reason"

  /**
   * Несовпадение CRC/контрольной суммы
   * 
   * @param protocol Протокол
   * @param expected Ожидаемая CRC
   * @param actual Полученная CRC
   * @param algorithm Алгоритм (CRC16, CRC32, etc.)
   */
  final case class CrcMismatch(
      protocol: String,
      expected: Long,
      actual: Long,
      algorithm: String = "CRC16"
  ) extends ParseError:
    def message: String = 
      s"[$protocol] $algorithm не совпадает: ожидается 0x${expected.toHexString}, " +
      s"получено 0x${actual.toHexString}"

  /**
   * Неподдерживаемый codec ID (Teltonika)
   * 
   * Teltonika использует codec ID для определения формата пакета:
   * - 0x08 - Codec 8
   * - 0x8E - Codec 8 Extended
   * - 0x10 - Codec 16
   * 
   * @param protocol Протокол (обычно teltonika)
   * @param codecId ID кодека
   */
  final case class UnsupportedCodec(
      protocol: String,
      codecId: Int
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Неподдерживаемый codec ID: 0x${codecId.toHexString}"

  /**
   * Несовпадение количества записей
   * 
   * В некоторых протоколах (Teltonika, Ruptela) пакет содержит:
   * - Заголовок с количеством записей
   * - N записей GPS
   * - Футер с количеством записей (для проверки)
   * 
   * @param protocol Протокол
   * @param expected Ожидаемое количество (из заголовка)
   * @param actual Фактическое количество (распарсенных записей)
   */
  final case class RecordCountMismatch(
      protocol: String,
      expected: Int,
      actual: Int
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Несовпадение количества записей: ожидается $expected, получено $actual"

  /**
   * Неверная сигнатура пакета (magic bytes)
   * 
   * Многие протоколы начинаются с фиксированной последовательности байт:
   * - Wialon: #
   * - NavTelecom: ~A или ~S
   * - EGTS: 0x01
   * 
   * @param protocol Протокол
   * @param expectedSignature Ожидаемая сигнатура (для документации)
   */
  final case class InvalidSignature(
      protocol: String,
      expectedSignature: Option[String] = None
  ) extends ParseError:
    def message: String = 
      val expected = expectedSignature
        .map(s => s": ожидается '$s'")
        .getOrElse("")
      s"[$protocol] Неверная сигнатура пакета$expected"

  /**
   * Неизвестный тип пакета
   * 
   * Протоколы могут содержать разные типы пакетов:
   * - Wialon: #L# (login), #D# (data), #P# (ping)
   * - NavTelecom: ~A (authentication), ~S (data)
   * 
   * @param protocol Протокол
   * @param packetType Неизвестный тип пакета
   */
  final case class UnknownPacketType(
      protocol: String,
      packetType: String
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Неизвестный тип пакета: '$packetType'"

  /**
   * Невалидная GPS координата
   * 
   * Проверки:
   * - Широта: -90 до +90
   * - Долгота: -180 до +180
   * - Точность: не 0 (если протокол поддерживает)
   * 
   * @param protocol Протокол
   * @param lat Широта
   * @param lon Долгота
   * @param reason Причина (out_of_range, zero_coordinates, etc.)
   */
  final case class InvalidCoordinates(
      protocol: String,
      lat: Double,
      lon: Double,
      reason: String
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Невалидные координаты ($lat, $lon): $reason"

  /**
   * Общая ошибка парсинга (fallback)
   * 
   * Используется для неожиданных ситуаций, которые не покрыты типизированными ошибками.
   * Старайтесь избегать этого case class — лучше добавить специфичный тип ошибки.
   * 
   * @param protocol Протокол
   * @param reason Описание ошибки
   * @param throwable Опциональное исключение (если было поймано)
   */
  final case class GenericParseError(
      protocol: String,
      reason: String,
      throwable: Option[Throwable] = None
  ) extends ParseError:
    def message: String = 
      s"[$protocol] Ошибка парсинга: $reason"

end ParseError
