package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Protocol, Command}

/**
 * Мульти-парсер протоколов — определяет протокол по magic bytes первого пакета.
 * 
 * При получении IMEI-пакета (первый пакет соединения) пробует каждый парсер
 * из зарегистрированного списка, пока один из них не успешно спарсит IMEI.
 * Затем кэширует определённый протокол — все дальнейшие пакеты этого
 * соединения обрабатываются уже конкретным парсером без повторного определения.
 * 
 * Используется когда один TCP-порт принимает несколько протоколов.
 * Для выделенных портов (один протокол = один порт) используется конкретный парсер напрямую.
 * 
 * Порядок определения (от наиболее специфичного к общему):
 * 1. Teltonika — [2B length][IMEI ASCII] — первые 2 байта = 0x000F (length=15)
 * 2. NavTelecom — сигнатура "*>" (0x2A3E)
 * 3. Ruptela — 2B length + 8B IMEI как long
 * 4. GoSafe — ASCII, начинается с "$" или "*"
 * 5. SkySim — binary, начинается с 0xF0
 * 6. AutophoneMayak — binary, начинается с 0x4D ('M')
 * 7. DTM — binary, начинается с 0x7B ('{') или fixed-size header
 * 8. Wialon — текстовый (#) или бинарный (fallback)
 * 
 * КРИТИЧНО: buffer.readerIndex() сбрасывается перед каждой попыткой (mark/reset),
 * чтобы следующий парсер мог прочитать с начала.
 */
object MultiProtocolParser:

  /**
   * Результат определения протокола
   * 
   * @param protocol Определённый протокол (для кэширования в ConnectionState)
   * @param parser Конкретный парсер, который успешно спарсил IMEI
   * @param imei Спарсенный IMEI (чтобы не парсить повторно)
   */
  final case class DetectionResult(
    protocol: Protocol,
    parser: ProtocolParser,
    imei: String
  )

  /**
   * Запись в реестре парсеров — связывает протокол с его парсером
   */
  final case class ParserEntry(
    protocol: Protocol,
    parser: ProtocolParser
  )

  /**
   * Реестр всех поддерживаемых парсеров по умолчанию
   * 
   * Порядок ВАЖЕН — парсеры проверяются последовательно.
   * Более специфичные протоколы (с уникальными magic bytes) идут первыми.
   */
  val defaultParsers: List[ParserEntry] = List(
    ParserEntry(Protocol.Teltonika, new TeltonikaParser),
    ParserEntry(Protocol.NavTelecom, NavTelecomParser),
    ParserEntry(Protocol.Ruptela, RuptelaParser),
    ParserEntry(Protocol.Galileosky, GalileoskyParser),
    ParserEntry(Protocol.Concox, ConcoxParser),
    ParserEntry(Protocol.Adm, AdmParser),
    ParserEntry(Protocol.GoSafe, GoSafeParser),
    ParserEntry(Protocol.SkySim, SkySimParser),
    ParserEntry(Protocol.AutophoneMayak, AutophoneMayakParser),
    ParserEntry(Protocol.MicroMayak, MicroMayakParser),
    ParserEntry(Protocol.Dtm, DtmParser),
    ParserEntry(Protocol.TK102, TK102Parser.tk102),
    ParserEntry(Protocol.TK103, TK102Parser.tk103),
    ParserEntry(Protocol.Arnavi, ArnaviParser),
    ParserEntry(Protocol.Neomatica, NeomaticaParser),
    ParserEntry(Protocol.Gtlt, GtltParser),
    // Wialon последний — у него наименее уникальные magic bytes
    ParserEntry(Protocol.Wialon, WialonAdapterParser)
  )

  /**
   * Определяет протокол по первому пакету (IMEI-пакет)
   * 
   * Пробует каждый парсер из списка, пока один не успешно спарсит IMEI.
   * Перед каждой попыткой сбрасывает readerIndex буфера.
   * 
   * @param buffer ByteBuf с первым пакетом (IMEI)
   * @param parsers Список парсеров для проверки (по умолчанию — все)
   * @return DetectionResult с протоколом, парсером и IMEI
   */
  def detect(
    buffer: ByteBuf,
    parsers: List[ParserEntry] = defaultParsers
  ): IO[ProtocolError, DetectionResult] =
    detectRecursive(buffer, parsers, errors = List.empty)

  /**
   * Рекурсивная попытка определения протокола
   * 
   * Если все парсеры провалились — возвращает ProtocolDetectionFailed
   * с описанием всех попыток (для диагностики).
   */
  private def detectRecursive(
    buffer: ByteBuf,
    remaining: List[ParserEntry],
    errors: List[String]
  ): IO[ProtocolError, DetectionResult] =
    remaining match
      case Nil =>
        // Ни один парсер не смог распознать пакет
        val errorDetails = errors.mkString("; ")
        ZIO.fail(ProtocolError.ProtocolDetectionFailed(
          s"Ни один парсер не распознал пакет (${errors.size} попыток). Детали: $errorDetails"
        ))
      
      case entry :: rest =>
        // Сохраняем позицию чтения для отката
        val savedReaderIndex = buffer.readerIndex()
        
        entry.parser.parseImei(buffer)
          .map { imei =>
            // Успех! Протокол определён
            DetectionResult(
              protocol = entry.protocol,
              parser = entry.parser,
              imei = imei
            )
          }
          .tapError { _ =>
            // Откатываем позицию чтения для следующего парсера
            ZIO.succeed(buffer.readerIndex(savedReaderIndex))
          }
          .catchAll { error =>
            val errorMsg = s"${entry.protocol}: ${error.message}"
            ZIO.logDebug(s"[DETECT] Парсер ${entry.protocol} не подошёл: ${error.message}") *>
              detectRecursive(buffer, rest, errors :+ errorMsg)
          }

  /**
   * Определяет протокол по magic bytes без полного парсинга IMEI
   * 
   * Быстрая проверка по первым байтам пакета.
   * Используется для предварительной фильтрации, когда полный парсинг слишком дорогой.
   * 
   * @param buffer ByteBuf с данными (readerIndex не изменяется!)
   * @return Some(Protocol) если определён, None если нет
   */
  def quickDetect(buffer: ByteBuf): Option[Protocol] =
    if buffer.readableBytes() < 2 then None
    else
      val b0 = buffer.getUnsignedByte(buffer.readerIndex())
      val b1 = buffer.getUnsignedByte(buffer.readerIndex() + 1)
      
      if b0 == 0x2A && b1 == 0x3E then
        // "*>" — NavTelecom FLEX signature
        Some(Protocol.NavTelecom)
      else if b0 == 0x00 && b1 == 0x0F then
        // 0x000F = length 15 — Teltonika IMEI packet
        Some(Protocol.Teltonika)
      else if b0 == 0x78 && b1 == 0x78 then
        // 0x7878 — Concox/GL06 framing
        Some(Protocol.Concox)
      else if b0 == 0x01 then
        // 0x01 header — Galileosky tag-based protocol
        Some(Protocol.Galileosky)
      else if b0 == 0x23 then
        // '#' — Wialon IPS текстовый
        Some(Protocol.Wialon)
      else if b0 == 0x28 then
        // '(' — TK102/TK103 ASCII framing
        Some(Protocol.TK102)
      else if b0 == 0x2A && b1 == 0x48 then
        // "*H" — GTLT3MT1 (*HQ header)
        Some(Protocol.Gtlt)
      else if b0 == 0x24 && b1 == 0x41 then
        // "$A" — Arnavi ($AV header)
        Some(Protocol.Arnavi)
      else if b0 == 0x24 && (b1 == 0x47 || b1 == 0x2A) then
        // "$G" or "$*" — GoSafe GPRMC-подобный ASCII
        Some(Protocol.GoSafe)
      else if b0 == 0x24 then
        // 0x24 — MicroMayak start marker ($ без Arnavi/GoSafe сигнатур)
        Some(Protocol.MicroMayak)
      else if b0 == 0xF0 then
        // 0xF0 — SkySim binary header
        Some(Protocol.SkySim)
      else if b0 == 0x4D then
        // 'M' — AutophoneMayak binary
        Some(Protocol.AutophoneMayak)
      else if b0 == 0x7B then
        // '{' — DTM binary fixed header
        Some(Protocol.Dtm)
      else
        // ADM (binary LE, no unique magic), Ruptela (2B length + long IMEI), Wialon Binary
        None

  /**
   * Создаёт ProtocolParser-обёртку для конкретного набора парсеров.
   * 
   * Возвращает ProtocolParser который при первом вызове parseImei
   * определяет протокол через detect(), а потом делегирует всё
   * определённому парсеру. Используется для TCP-портов, принимающих
   * любой протокол (multi-protocol mode).
   * 
   * @param parsers Парсеры для проверки
   * @return ProtocolParser-обёртка
   */
  def asProtocolParser(parsers: List[ParserEntry] = defaultParsers): ProtocolParser =
    new ProtocolParser:
      // Кэш для определённого парсера (volatile для thread-safety в Netty)
      @volatile private var detectedParser: Option[ProtocolParser] = None
      @volatile private var detectedProtocolName: String = "multi"
      
      override val protocolName: String = "multi"
      
      override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
        detect(buffer, parsers).flatMap { result =>
          detectedParser = Some(result.parser)
          detectedProtocolName = result.parser.protocolName
          ZIO.logInfo(s"[MULTI] Определён протокол: ${result.protocol} (${result.parser.protocolName}) для IMEI=${result.imei}") *>
            ZIO.succeed(result.imei)
        }
      
      override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
        detectedParser match
          case Some(parser) => parser.parseData(buffer, imei)
          case None => ZIO.fail(ProtocolError.ProtocolDetectionFailed(
            "Протокол не определён — parseImei не был вызван или не нашёл подходящий парсер"
          ))
      
      override def ack(recordCount: Int): ByteBuf =
        detectedParser.map(_.ack(recordCount))
          .getOrElse(Unpooled.EMPTY_BUFFER)
      
      override def imeiAck(accepted: Boolean): ByteBuf =
        detectedParser.map(_.imeiAck(accepted))
          .getOrElse(Unpooled.EMPTY_BUFFER)
      
      override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
        detectedParser match
          case Some(parser) => parser.encodeCommand(command)
          case None => ZIO.fail(ProtocolError.UnsupportedProtocol("multi — протокол не определён"))
