package com.wayrecall.tracker.domain

import zio.json.*
import java.time.Instant

// ═══════════════════════════════════════════════════════════════════════════
// Command — полная иерархия команд для GPS трекеров
// ═══════════════════════════════════════════════════════════════════════════
//
// Команды делятся на два канала доставки:
// 1. TCP/GPRS — отправляются через активное соединение (Connection Manager)
// 2. SMS — отправляются через SMS-шлюз (Notification Service / Device Manager)
//
// CM отвечает ТОЛЬКО за TCP/GPRS доставку.
// SMS команды хранятся в модели для аудита и маршрутизации,
// но фактически отправляются через SMS-шлюз другим сервисом.
//
// Из legacy STELS:
// ┌──────────────────┬─────────┬─────────────────────────────────┐
// │ Протокол         │ Канал   │ Поддерживаемые команды          │
// ├──────────────────┼─────────┼─────────────────────────────────┤
// │ Teltonika        │ TCP+SMS │ Reboot, SetInterval, SetOutput, │
// │                  │         │ SetParameter, RequestPosition    │
// │ NavTelecom       │ TCP     │ Password → IOSwitch (auth seq)  │
// │ DTM              │ TCP     │ IOSwitch (binary 0x09+n)        │
// │ Ruptela          │ TCP+SMS │ Reboot, SetOutput, DeviceConfig │
// │ Galileosky       │ SMS     │ SetOutput (Out 0,1 / Out 1,0)   │
// │ Arnavi           │ SMS     │ SetOutput (SERV*10/SERV*9)      │
// │ UMKa/GlonassSoft │ SMS    │ Reboot, Coords, SetOutput       │
// │ Wialon, SkySim,  │ —       │ Receive-only (нет команд)       │
// │ GoSafe, Concox,  │         │                                 │
// │ TK102, GTLT,     │         │                                 │
// │ MicroMayak, ADM  │         │                                 │
// └──────────────────┴─────────┴─────────────────────────────────┘
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Канал доставки команды
 */
enum CommandDelivery derives JsonCodec:
  case Tcp    // Через активное TCP соединение (Connection Manager)
  case Sms    // Через SMS-шлюз (другой сервис)
  case Auto   // TCP если онлайн, иначе SMS fallback

/**
 * Базовый trait для всех команд GPS трекерам
 *
 * Каждая команда содержит:
 * - commandId: уникальный UUID (для дедупликации и аудита)
 * - imei: IMEI целевого трекера
 * - timestamp: время создания команды
 * - delivery: канал доставки (TCP / SMS / Auto)
 * - priority: приоритет (высокий = блокировка двигателя)
 */
sealed trait Command:
  def commandId: String
  def imei: String
  def timestamp: Instant
  def delivery: CommandDelivery
  def priority: Int // 0 = нормальный, 1 = высокий (Block Engine)

object Command:
  given JsonCodec[Command] = DeriveJsonCodec.gen[Command]

// ─────────────────────────────────────────────────────────
// Универсальные команды (поддерживаются большинством протоколов)
// ─────────────────────────────────────────────────────────

/**
 * Перезагрузка трекера
 *
 * Teltonika: "cpureset" (SMS: "{login} {password} cpureset")
 * Ruptela:   SMS "{password} reset"
 * UMKa:      SMS "{password} reset"
 */
final case class RebootCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    delivery: CommandDelivery = CommandDelivery.Auto,
    priority: Int = 0
) extends Command derives JsonCodec

/**
 * Установка интервала отправки данных (в секундах)
 *
 * Teltonika: "setparam 1001:{interval}" (SMS: параметр Data Sending Frequency)
 * Ruptela:   0x65 Set Parameter, param 0x01
 */
final case class SetIntervalCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    intervalSeconds: Int,
    delivery: CommandDelivery = CommandDelivery.Tcp,
    priority: Int = 0
) extends Command derives JsonCodec

/**
 * Запрос текущей позиции (однократный)
 *
 * Teltonika: "getrecord" / SMS "getgps"
 * Ruptela:   0x66 Get Position
 * UMKa:      SMS "{password} coords"
 */
final case class RequestPositionCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    delivery: CommandDelivery = CommandDelivery.Auto,
    priority: Int = 0
) extends Command derives JsonCodec

/**
 * Управление цифровым выходом (реле) трекера
 *
 * Используется для блокировки/разблокировки двигателя,
 * управления сиреной, иммобилайзером и т.д.
 *
 * outputIndex: номер выхода (0-based)
 * enabled: true = включить (замкнуть реле), false = выключить
 *
 * ПРОТОКОЛЫ (Legacy STELS):
 * ┌──────────────┬────────────────────────────────────────────┐
 * │ Teltonika    │ TCP: "setdigout {11|00}" (2 бита)          │
 * │ NavTelecom   │ TCP: ">PASS:{pwd}" → "!{n}Y" / "!{n}N"   │
 * │ DTM          │ TCP: binary [0x7B][len][0xFF][chk][0x09+n] │
 * │ Ruptela      │ TCP: 0x67 Set Output, idx, value           │
 * │ Galileosky   │ SMS: "Out {0|1},{1|0}"                     │
 * │ Arnavi       │ SMS: "123456*SERV*10.{1|0}" (out1)         │
 * │ UMKa         │ SMS: " OUTPUT0 {1|0}" (пробел в начале!)   │
 * └──────────────┴────────────────────────────────────────────┘
 */
final case class SetOutputCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    outputIndex: Int,
    enabled: Boolean,
    delivery: CommandDelivery = CommandDelivery.Auto,
    priority: Int = 1
) extends Command derives JsonCodec

/**
 * Произвольная текстовая команда (для протоколов с текстовым интерфейсом)
 *
 * Teltonika Codec 12: передаётся as-is через TCP
 * Ruptela 0x66: передаётся как deviceConfigurationCommand
 * Wialon: "#M#{commandText}\r\n"
 */
final case class CustomCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    commandText: String,
    delivery: CommandDelivery = CommandDelivery.Tcp,
    priority: Int = 0
) extends Command derives JsonCodec

// ─────────────────────────────────────────────────────────
// Протокол-специфичные команды
// ─────────────────────────────────────────────────────────

/**
 * Установка параметра трекера (Teltonika setparam)
 *
 * Teltonika-специфичная команда для конфигурирования трекера.
 * Параметры идентифицируются числовым ID.
 *
 * Примеры параметров:
 * - 1001: Data Sending Frequency (интервал отправки)
 * - 1002: Data Sending Frequency (при стоянке)
 * - 50000+: пользовательские параметры
 *
 * Формат TCP: "setparam {paramId}:{paramValue}"
 */
final case class SetParameterCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    paramId: Int,
    paramValue: String,
    delivery: CommandDelivery = CommandDelivery.Tcp,
    priority: Int = 0
) extends Command derives JsonCodec

/**
 * Команда аутентификации (NavTelecom NTCB)
 *
 * NavTelecom требует аутентификацию перед IOSwitch:
 * 1. CM отправляет ">PASS:{password}"
 * 2. Трекер отвечает "PASS" (success) или "ERR" (failure)
 * 3. Только после PASS можно слать IOSwitch ("!{n}Y" / "!{n}N")
 *
 * Эта команда обычно НЕ создаётся пользователем напрямую —
 * она генерируется автоматически перед SetOutputCommand для NavTelecom.
 */
final case class PasswordCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    password: String,
    delivery: CommandDelivery = CommandDelivery.Tcp,
    priority: Int = 1
) extends Command derives JsonCodec

/**
 * Конфигурирование устройства (Ruptela deviceConfigurationCommand 0x66)
 *
 * Передаёт произвольную текстовую конфигурацию через TCP.
 * Формат бинарный: [2B length][0x66][UTF-8 config text][2B CRC16]
 */
final case class DeviceConfigCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    configText: String,
    delivery: CommandDelivery = CommandDelivery.Tcp,
    priority: Int = 0
) extends Command derives JsonCodec

/**
 * Перенастройка сервера (указать трекеру новый адрес приёма данных)
 *
 * Используется при миграции серверов.
 * Протокол-специфичные SMS-форматы (из legacy):
 * - Teltonika: setparam для параметра IP/PORT
 * - Arnavi: "123456*SERV*1.{ip}*2.{port}"
 */
final case class ChangeServerCommand(
    commandId: String,
    imei: String,
    timestamp: Instant,
    serverHost: String,
    serverPort: Int,
    delivery: CommandDelivery = CommandDelivery.Sms,
    priority: Int = 0
) extends Command derives JsonCodec

// ─────────────────────────────────────────────────────────
// Статус и результат выполнения
// ─────────────────────────────────────────────────────────

/**
 * Статус выполнения команды
 */
enum CommandStatus derives JsonCodec:
  case Pending       // Команда в очереди (трекер оффлайн)
  case Queued        // Добавлена в pending queue (Redis ZSET)
  case Sent          // Отправлена на трекер (TCP / SMS)
  case Acked         // Трекер подтвердил получение
  case Executed      // Трекер выполнил команду (проверено через IO/GPS)
  case Failed        // Ошибка выполнения (ProtocolError, UnsupportedCommand)
  case Timeout       // Трекер не ответил в течение timeout
  case Rejected      // Трекер отверг команду (неверный пароль, etc.)
  case Cancelled     // Отменена пользователем или системой
  case Expired       // TTL истёк (не отправлена за pendingCommandsTtlHours)

/**
 * Результат выполнения команды (для Kafka command-audit)
 */
final case class CommandResult(
    commandId: String,
    imei: String,
    status: CommandStatus,
    message: Option[String],
    responseData: Option[String] = None,
    timestamp: Instant
) derives JsonCodec

/**
 * Информация о pending команде (для Redis ZSET + in-memory queue)
 */
final case class PendingCommand(
    command: Command,
    createdAt: Instant,
    retryCount: Int = 0,
    maxRetries: Int = 3,
    lastAttemptAt: Option[Instant] = None,
    lastError: Option[String] = None
) derives JsonCodec

// ─────────────────────────────────────────────────────────
// Возможности протокола (what commands it supports)
// ─────────────────────────────────────────────────────────

/**
 * Описание возможностей конкретного протокола по командам
 *
 * Используется для:
 * - Валидации: можно ли отправить эту команду данному трекеру?
 * - UI: какие кнопки показывать оператору?
 * - Маршрутизация: TCP или SMS?
 */
final case class CommandCapability(
    protocol: String,
    supportedCommands: Set[String],
    tcpCommands: Set[String],
    smsCommands: Set[String],
    requiresAuth: Boolean = false,
    maxOutputs: Int = 1,
    notes: Option[String] = None
)

/**
 * Реестр возможностей протоколов по командам
 */
object CommandCapability:
  
  val Teltonika: CommandCapability = CommandCapability(
    protocol = "teltonika",
    supportedCommands = Set("reboot", "setInterval", "requestPosition", "setOutput", "setParameter", "custom"),
    tcpCommands = Set("reboot", "setInterval", "requestPosition", "setOutput", "setParameter", "custom"),
    smsCommands = Set("reboot", "requestPosition", "setOutput", "setParameter"),
    maxOutputs = 2,
    notes = Some("Codec 12 для TCP, SMS через login/password")
  )

  val NavTelecom: CommandCapability = CommandCapability(
    protocol = "navtelecom",
    supportedCommands = Set("setOutput", "password", "custom"),
    tcpCommands = Set("setOutput", "password", "custom"),
    smsCommands = Set.empty,
    requiresAuth = true,
    maxOutputs = 4,
    notes = Some("Требуется auth: >PASS:{pwd} → PASS перед IOSwitch")
  )

  val Dtm: CommandCapability = CommandCapability(
    protocol = "dtm",
    supportedCommands = Set("setOutput"),
    tcpCommands = Set("setOutput"),
    smsCommands = Set.empty,
    maxOutputs = 2,
    notes = Some("Binary IOSwitch: [0x7B][len][0xFF][checksum][0x09+n][val][0x7D]")
  )

  val Ruptela: CommandCapability = CommandCapability(
    protocol = "ruptela",
    supportedCommands = Set("reboot", "setInterval", "requestPosition", "setOutput", "deviceConfig"),
    tcpCommands = Set("reboot", "setInterval", "requestPosition", "setOutput", "deviceConfig"),
    smsCommands = Set("reboot", "requestPosition", "setOutput"),
    maxOutputs = 2,
    notes = Some("SMS setio инвертирован! 0,0 = ON, 1,1 = OFF")
  )

  val Galileosky: CommandCapability = CommandCapability(
    protocol = "galileosky",
    supportedCommands = Set("setOutput"),
    tcpCommands = Set.empty,
    smsCommands = Set("setOutput"),
    maxOutputs = 2,
    notes = Some("Только SMS: 'Out {0|1},{1|0}'")
  )

  val Arnavi: CommandCapability = CommandCapability(
    protocol = "arnavi",
    supportedCommands = Set("setOutput", "changeServer"),
    tcpCommands = Set.empty,
    smsCommands = Set("setOutput", "changeServer"),
    maxOutputs = 2,
    notes = Some("SMS: '123456*SERV*10.{val}' (out1), '*SERV*9.{val}' (out2)")
  )

  val Wialon: CommandCapability = CommandCapability(
    protocol = "wialon",
    supportedCommands = Set("custom"),
    tcpCommands = Set("custom"),
    smsCommands = Set.empty,
    notes = Some("Только произвольные текстовые команды: #M#text\\r\\n")
  )

  /** Протоколы без поддержки команд (receive-only) */
  val ReceiveOnly: CommandCapability = CommandCapability(
    protocol = "receive-only",
    supportedCommands = Set.empty,
    tcpCommands = Set.empty,
    smsCommands = Set.empty,
    notes = Some("Протокол не поддерживает отправку команд")
  )

  /**
   * Получить capabilities по имени протокола
   */
  def forProtocol(protocol: String): CommandCapability =
    protocol.toLowerCase match
      case "teltonika"                  => Teltonika
      case "navtelecom"                 => NavTelecom
      case "dtm"                        => Dtm
      case "ruptela"                    => Ruptela
      case "galileosky"                 => Galileosky
      case "arnavi"                     => Arnavi
      case "wialon" | "wialon-binary"   => Wialon
      case _                            => ReceiveOnly

  /** Все протоколы с поддержкой TCP команд */
  val allTcpCapable: List[CommandCapability] = List(
    Teltonika, NavTelecom, Dtm, Ruptela, Wialon
  )

  /** Все протоколы с поддержкой SMS команд */
  val allSmsCapable: List[CommandCapability] = List(
    Teltonika, Ruptela, Galileosky, Arnavi
  )
