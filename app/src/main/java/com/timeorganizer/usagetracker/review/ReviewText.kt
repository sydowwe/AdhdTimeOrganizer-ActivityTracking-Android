package com.timeorganizer.usagetracker.review

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The review's wording rules that need no Android: durations, the app list, and the server's instants. */
object ReviewText {

    /** "50 m", "1 h 40 m", "2 h". Rounded to the nearest minute; any time at all reads at least "1 m". */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0 m"
        val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest m"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest m"
        }
    }

    /** [listed] is "Reddit 50 m, Chrome 30 m" for the first [max]; [more] counts the apps left out. */
    data class AppLine(val listed: String, val more: Int)

    fun appLine(apps: List<Pair<String, Long>>, max: Int = 3): AppLine =
        AppLine(
            listed = apps.take(max).joinToString(", ") { (label, seconds) -> "$label ${duration(seconds)}" },
            more = (apps.size - max).coerceAtLeast(0)
        )

    /**
     * The server's instant as epoch milliseconds, or `null` when unreadable. A .NET `DateTime` of kind Utc serializes
     * with a `Z`; one of unspecified kind has none, and is UTC all the same — the contract says UTC.
     */
    fun instantMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
