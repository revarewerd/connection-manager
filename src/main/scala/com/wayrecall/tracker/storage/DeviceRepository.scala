package com.wayrecall.tracker.storage

import zio.*
import com.wayrecall.tracker.domain.VehicleInfo

/**
 * Репозиторий для работы с устройствами в PostgreSQL
 * 
 * Используется как fallback когда данных нет в Redis.
 * Реализует паттерн Repository для абстракции доступа к БД.
 * 
 * В production должна быть реализация через Doobie или Quill.
 */
trait DeviceRepository:
  /**
   * Найти устройство по IMEI
   * 
   * @param imei 15-значный IMEI устройства
   * @return VehicleInfo если найдено, None если нет
   */
  def findByImei(imei: String): Task[Option[VehicleInfo]]
  
  /**
   * Найти все активные устройства
   * 
   * Используется для предзагрузки кеша Redis при старте сервиса.
   * Может быть дорогой операцией при большом количестве устройств.
   * 
   * @return Список активных устройств
   */
  def findAllEnabled: Task[List[VehicleInfo]]
  
  /**
   * Проверить, активно ли устройство
   * 
   * @param imei IMEI устройства
   * @return true если устройство существует и активно
   */
  def isEnabled(imei: String): Task[Boolean]

object DeviceRepository:
  
  // Accessor методы
  def findByImei(imei: String): RIO[DeviceRepository, Option[VehicleInfo]] =
    ZIO.serviceWithZIO(_.findByImei(imei))
  
  def findAllEnabled: RIO[DeviceRepository, List[VehicleInfo]] =
    ZIO.serviceWithZIO(_.findAllEnabled)
  
  def isEnabled(imei: String): RIO[DeviceRepository, Boolean] =
    ZIO.serviceWithZIO(_.isEnabled(imei))
  
  /**
   * Тестовая реализация репозитория (in-memory)
   * 
   * ⚠️ НЕ ИСПОЛЬЗОВАТЬ В PRODUCTION!
   * 
   * Содержит захардкоженные устройства для локального тестирования.
   * В production должна быть заменена на Doobie/Quill реализацию,
   * подключенную к PostgreSQL базе Device Manager.
   */
  final case class Dummy() extends DeviceRepository:
    
    /**
     * Тестовые устройства
     * 
     * Включает:
     * - Teltonika трекер (активный)
     * - Wialon трекер (активный)
     * - Ruptela трекер (отключен - для тестирования disabled сценария)
     */
    private val devices = Map(
      "860719020025346" -> VehicleInfo(1L, "860719020025346", Some("Test Vehicle 1"), "teltonika", true),
      "860719020025347" -> VehicleInfo(2L, "860719020025347", Some("Test Vehicle 2"), "wialon", true),
      "860719020025348" -> VehicleInfo(3L, "860719020025348", Some("Test Vehicle 3"), "ruptela", false)
    )
    
    override def findByImei(imei: String): Task[Option[VehicleInfo]] =
      ZIO.succeed(devices.get(imei).filter(_.isActive))
    
    override def findAllEnabled: Task[List[VehicleInfo]] =
      ZIO.succeed(devices.values.filter(_.isActive).toList)
    
    override def isEnabled(imei: String): Task[Boolean] =
      ZIO.succeed(devices.get(imei).exists(_.isActive))
  
  val dummy: ULayer[DeviceRepository] =
    ZLayer.succeed(Dummy())
  
  // TODO: Добавить Doobie реализацию
  // val live: ZLayer[DataSource, Nothing, DeviceRepository] = ???
