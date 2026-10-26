package com.timeorganizer.usagetracker.data.api.dto

/**
 * One session on the wire. The instants are UTC ISO-8601 with milliseconds; `durationSeconds` is the floored
 * span, which is what the server compares it with (±2 s).
 *
 * [appCategory] is the category the app declares (`mapping/AppCategory`'s server name). It is the only way the server
 * learns it; the web groups the user's apps by it. `null` (left out by Gson) reads there as "declares none".
 */
data class AndroidSessionDto(
    val packageName: String,
    val appLabel: String,
    val sessionStartUtc: String,
    val sessionEndUtc: String,
    val durationSeconds: Long,
    val appCategory: String? = null
)
