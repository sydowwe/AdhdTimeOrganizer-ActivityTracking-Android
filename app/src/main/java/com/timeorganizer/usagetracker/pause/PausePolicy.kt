package com.timeorganizer.usagetracker.pause

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The status card's four choices (prompt 17). */
enum class PauseLength { FIFTEEN_MINUTES, ONE_HOUR, UNTIL_MORNING, UNTIL_RESUMED }

/** The "Tracking paused" notification as last shown: for which pause (its start), and when. */
data class PauseNotice(val shownForStartMs: Long?, val postedAtMs: Long) {
    companion object {
        val NONE = PauseNotice(null, 0L)
    }
}

enum class NoticeStep { POST, CANCEL, NOTHING }

/**
 * **Pure.** The rules of a pause that aren't the clipping ([PauseClipper]): where each choice ends, when "the morning" is,
 * when the notification is shown again, and when an empty sync keeps the server's tracker-silence rule from firing.
 */
object PausePolicy {

    const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** The morning when the server has never said (no `/now` answer yet, or one from before prompt 17). */
    val FALLBACK_WAKE_UP: LocalTime = LocalTime.of(7, 0)

    /** "Until I resume" is shown again once a day, so a forgotten pause doesn't go on for a week unseen (decision 17-4). */
    const val NOTICE_REPEAT_MS = 24 * HOUR_MS

    /**
     * How often a paused phone with nothing to send says it's alive. The server calls a phone silent after 48 h without a
     * sync (`TrackerSilenceRule.SilentAfter`), and a pause sends nothing; every 6 h leaves room for a phone offline a day.
     */
    const val HEARTBEAT_EVERY_MS = 6 * HOUR_MS

    /**
     * An ended pause is kept this long after the cursors pass it: the daily limits' estimate reads the whole day's log and
     * leaves paused time out, so it must still know a pause from this morning.
     */
    const val KEEP_ENDED_MS = 48 * HOUR_MS

    fun endMs(length: PauseLength, nowMs: Long, morningMs: Long): Long = when (length) {
        PauseLength.FIFTEEN_MINUTES -> nowMs + 15 * MINUTE_MS
        PauseLength.ONE_HOUR -> nowMs + HOUR_MS
        PauseLength.UNTIL_MORNING -> morningMs
        PauseLength.UNTIL_RESUMED -> PauseRange.UNTIL_RESUMED
    }

    /**
     * The next morning after [nowMs]. [keptWakeUpMs] is the server's last `nextWakeUpAt` (the planner's wake-up for that day,
     * else the user's default): used as it is while ahead, else moved on by whole days on the wall clock — the same time
     * tomorrow is the best guess offline. Never heard: [FALLBACK_WAKE_UP], today's if still ahead, else tomorrow's.
     */
    fun morning(nowMs: Long, keptWakeUpMs: Long?, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs)
        var morning = keptWakeUpMs?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
            ?: ZonedDateTime.ofInstant(now, zone).with(FALLBACK_WAKE_UP)
        while (!morning.toInstant().isAfter(now)) morning = morning.plusDays(1)
        return morning.toInstant().toEpochMilli()
    }

    /**
     * Whether to show the notification, take it away, or leave it. Shown once per pause (a timed one clears itself at its end
     * — `setTimeoutAfter`); "until I resume" again every [NOTICE_REPEAT_MS]; taken away once no pause is going.
     */
    fun notice(active: PauseRange?, shown: PauseNotice, nowMs: Long): NoticeStep = when {
        active == null -> if (shown.shownForStartMs != null) NoticeStep.CANCEL else NoticeStep.NOTHING
        shown.shownForStartMs != active.startMs -> NoticeStep.POST
        active.untilResumed && nowMs - shown.postedAtMs !in 0 until NOTICE_REPEAT_MS -> NoticeStep.POST
        else -> NoticeStep.NOTHING
    }

    /** An empty sync is due: paused, nothing else reached the server this run, and none was sent in the last [HEARTBEAT_EVERY_MS]. */
    fun heartbeatDue(paused: Boolean, sentAnything: Boolean, lastHeartbeatMs: Long, nowMs: Long): Boolean =
        paused && !sentAnything && nowMs - lastHeartbeatMs !in 0 until HEARTBEAT_EVERY_MS
}
