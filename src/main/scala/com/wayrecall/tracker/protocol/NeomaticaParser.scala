package com.wayrecall.tracker.protocol

import zio.*
import io.netty.buffer.{ByteBuf, Unpooled}
import com.wayrecall.tracker.domain.{GpsRawPoint, ProtocolError, Command}

/**
 * Парсер протокола Neomatica.
 *
 * В полевом трафике Neomatica встречается с разными форматами пакетов,
 * поэтому здесь используется fallback-стратегия: пробуем несколько
 * стабильных парсеров по очереди и фиксируем первый успешный.
 *
 * Это позволяет поднять отдельный Neomatica-порт и не терять данные,
 * даже если конкретная прошивка шлёт формат, совместимый с Teltonika/
 * Arnavi/Galileosky/Wialon.
 */
object NeomaticaParser extends ProtocolParser:

  override val protocolName: String = "neomatica"

  // Порядок важен: от более строгих форматов к более общим.
  private val delegates: List[ProtocolParser] = List(
    new TeltonikaParser,
    ArnaviParser,
    GalileoskyParser,
    WialonAdapterParser
  )

  @volatile private var selectedParser: Option[ProtocolParser] = None

  override def parseImei(buffer: ByteBuf): IO[ProtocolError, String] =
    selectedParser match
      case Some(parser) => parser.parseImei(buffer)
      case None         => tryParsers(buffer, _.parseImei(buffer))

  override def parseData(buffer: ByteBuf, imei: String): IO[ProtocolError, List[GpsRawPoint]] =
    selectedParser match
      case Some(parser) => parser.parseData(buffer, imei)
      case None         => tryParsers(buffer, _.parseData(buffer, imei))

  override def ack(recordCount: Int): ByteBuf =
    selectedParser
      .map(_.ack(recordCount))
      .getOrElse(Unpooled.wrappedBuffer(Array[Byte](0x01)))

  override def imeiAck(accepted: Boolean): ByteBuf =
    selectedParser
      .map(_.imeiAck(accepted))
      .getOrElse(Unpooled.wrappedBuffer(Array[Byte](if accepted then 0x01 else 0x00)))

  override def encodeCommand(command: Command): IO[ProtocolError, ByteBuf] =
    selectedParser match
      case Some(parser) => parser.encodeCommand(command)
      case None =>
        ZIO.fail(ProtocolError.UnsupportedProtocol("neomatica: parser ещё не определён (нет успешного parseImei)"))

  private def tryParsers[A](buffer: ByteBuf, action: ProtocolParser => IO[ProtocolError, A]): IO[ProtocolError, A] =
    def loop(candidates: List[ProtocolParser], errors: List[String]): IO[ProtocolError, A] =
      candidates match
        case Nil =>
          ZIO.fail(
            ProtocolError.ParseError(
              s"Neomatica: ни один fallback-парсер не подошёл. Ошибки: ${errors.reverse.mkString("; ")}"
            )
          )

        case parser :: tail =>
          val readerIndex = buffer.readerIndex()
          action(parser)
            .tap { _ =>
              ZIO.succeed {
                selectedParser = Some(parser)
              } *>
              ZIO.logInfo(s"[NEOMATICA] Выбран fallback-парсер: ${parser.protocolName}")
            }
            .catchAll { err =>
              ZIO.succeed(buffer.readerIndex(readerIndex)) *>
              loop(tail, s"${parser.protocolName}: ${err.message}" :: errors)
            }

    loop(delegates, Nil)
