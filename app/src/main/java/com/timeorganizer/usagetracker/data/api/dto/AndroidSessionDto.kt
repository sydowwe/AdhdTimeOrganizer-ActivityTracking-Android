package com.timeorganizer.usagetracker.data.api.dto

/**
 * One session on the wire. The instants are UTC ISO-8601 with milliseconds; `durationSeconds` is the floored
 * span, which is what the server compares it with (±2 s).
 */
data class AndroidSessionDto(
    val packageName: String,
    val appLabel: String,
    val sessionStartUtc: String,
    val sessionEndUtc: String,
    val durationSeconds: Long
)
