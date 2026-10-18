package com.timeorganizer.usagetracker.data.api.dto

data class AndroidSessionDto(
    val packageName: String,
    val appLabel: String,
    val sessionStartUtc: String,
    val sessionEndUtc: String,
    val durationSeconds: Long
)
