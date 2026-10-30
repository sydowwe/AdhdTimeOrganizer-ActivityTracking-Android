package com.timeorganizer.usagetracker.data.api.dto

/** `PUT tracker/android/push-device` (prompt 22): this phone, under the device id it syncs with, is pushed at [token]. */
data class PushDeviceRequest(
    val deviceId: String,
    val token: String,
)
