package com.wayrecall.tracker.domain

import zio.json.*

/**
 * Информация о транспортном средстве с данными подключения
 */
case class VehicleInfo(
    id: Long,
    imei: String,
    name: Option[String],
    deviceType: String,
    isActive: Boolean
) derives JsonCodec
