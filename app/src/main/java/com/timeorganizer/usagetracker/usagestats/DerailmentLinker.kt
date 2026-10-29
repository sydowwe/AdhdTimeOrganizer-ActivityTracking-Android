package com.timeorganizer.usagetracker.usagestats

/** A notification that interrupted (prompt 15): which app, and when. No text — the usage log has none. */
data class Interruption(val packageName: String, val atMs: Long)

/** Why a derailment ended. [wire] is the server's member name (`AndroidDerailmentEnd`). */
enum class DerailmentEnd(val wire: String) {
    /** The app that was in front when the notification came is in front again. */
    RETURNED("Returned"),

    /** The screen went off or the lock screen came back. */
    SCREEN_OFF("ScreenOff"),

    /** [DerailmentLinker.CAP_MS] after the app was opened, with neither. */
    CAPPED("Capped")
}

/**
 * A notification that pulled the phone's user into its app: [packageName]'s notification at [notifiedMs], the app in front
 * from [startMs] (within [DerailmentLinker.OPEN_WITHIN_MS]), until [endMs]. The length is `endMs - startMs`, phone time.
 */
data class Derailment(
    val packageName: String,
    val notifiedMs: Long,
    val startMs: Long,
    val endMs: Long,
    val endedBy: DerailmentEnd
)

/**
 * One read's interruptions and settled derailments. [resumeFromMs] is where the next read's interruptions must start
 * (the caller reads [DerailmentLinker.LOOKBACK_MS] before it too).
 */
data class DerailmentRead(val interruptions: List<Interruption>, val derailments: List<Derailment>, val resumeFromMs: Long)

/**
 * **Pure.** Which notifications derailed the user (prompt 15): `NOTIFICATION_INTERRUPTION` events joined to the sittings
 * [SessionBuilder] builds from the same log.
 *
 * - **Every interruption is kept**, linked or not, as soon as it is read: the instant and the package are all there is.
 *   [dropped] (the ignored apps, this app's own nudges) are never read.
 * - **A derailment starts** when the notifying app comes to the front within [OPEN_WITHIN_MS] of its notification. Not
 *   when it was in front already (a message arriving in the open chat), and not when the phone was picked up without
 *   opening it: that is an interruption, not a derailment.
 * - **It ends** when the app that was in front at the notification is in front again (the user went back to what they
 *   were doing), when the screen goes off, or [CAP_MS] after the app was opened — whichever comes first. The home screen
 *   and this app ([excluded]) are nothing to go back to: with them, or the screen off, at the notification, only the screen
 *   going off ends it.
 * - **One derailment at a time.** A notification whose app is opened inside a derailment already going (a burst of
 *   messages, Slack while still in WhatsApp's) starts none of its own; the first one owns the stretch.
 * - **Only settled derailments leave**: one still going is read again, whole, next time. So [DerailmentRead.resumeFromMs]
 *   rests at the earliest notification still undecided — a derailment going on, or a notification whose minute isn't up —
 *   and never inside a settled derailment that a later notification depends on. **Re-reading in pieces gives what one long
 *   read gives**, as for the sessions and the pickups; the outbox's unique keys skip what was stored before.
 */
object DerailmentLinker {

    /** How soon the notifying app must come to the front for the notification to have started it. */
    const val OPEN_WITHIN_MS = 60_000L

    /** The longest derailment: past two hours it is an evening on the phone, not one notification's doing. */
    const val CAP_MS = 2 * 60 * 60_000L

    /**
     * How far before the first notification the log is read, so the app in front at it is known (its resume may be long
     * before). An app in front for longer than this reads as nothing to go back to.
     */
    const val LOOKBACK_MS = 3 * 60 * 60_000L

    /** A derailment that ended this close to the read's end may not be over: the next read decides it. */
    const val SETTLE_MS = SessionBuilder.MERGE_GAP_MS

    private class Sitting(val packageName: String, val startMs: Long, val endMs: Long?) {
        fun covers(atMs: Long) = startMs <= atMs && (endMs == null || endMs > atMs)
    }

    /** A derailment found in this read; [endMs] `null` while it is still going. */
    private class Chain(val notifiedMs: Long, val startMs: Long, val endMs: Long?) {
        fun holds(atMs: Long) = startMs <= atMs && (endMs == null || endMs > atMs)
    }

    /**
     * @param events the log from [LOOKBACK_MS] before [fromMs] to [toMs], in order.
     * @param fromMs the earliest notification to read.
     * @param excluded the home screen and this app: never "what the user was doing".
     * @param dropped packages whose notifications are not read at all.
     */
    fun link(
        events: List<UsageEventRecord>,
        fromMs: Long,
        toMs: Long,
        excluded: Set<String>,
        dropped: Set<String>
    ): DerailmentRead {
        val sittings = sittings(events, toMs)
        val screenOffs = events.filter { it.kind == EventKind.CLOSE_ALL }.map { it.timestampMs }.sorted()
        val interruptions = events
            .filter { it.kind == EventKind.NOTIFIED && it.timestampMs in fromMs until toMs && it.packageName !in dropped }
            .map { Interruption(it.packageName, it.timestampMs) }
            .distinct()
            .sortedWith(compareBy({ it.atMs }, { it.packageName }))

        val settledBy = toMs - SETTLE_MS
        val chains = mutableListOf<Chain>()
        val derailments = mutableListOf<Derailment>()
        var undecidedFromMs: Long? = null

        for (interruption in interruptions) {
            val at = interruption.atMs
            val app = interruption.packageName
            if (sittings.any { it.packageName == app && it.covers(at) }) continue // already in front

            val opened = sittings
                .filter { it.packageName == app && it.startMs in at..at + OPEN_WITHIN_MS }
                .minOfOrNull { it.startMs }
            if (opened == null) {
                if (at + OPEN_WITHIN_MS > settledBy) undecidedFromMs = undecidedFromMs ?: at
                continue
            }
            if (chains.any { it.holds(opened) }) continue // inside a derailment already going

            val before = sittings
                .filter { it.packageName != app && it.packageName !in excluded && it.covers(at) }
                .maxByOrNull { it.startMs }
                ?.packageName
            val returnedMs = before?.let { pkg ->
                sittings.filter { it.packageName == pkg && it.startMs > opened }.minOfOrNull { it.startMs }
            }
            val screenOffMs = screenOffs.firstOrNull { it >= opened }
            val ends = listOfNotNull(
                returnedMs?.let { it to DerailmentEnd.RETURNED },
                screenOffMs?.let { it to DerailmentEnd.SCREEN_OFF },
                opened + CAP_MS to DerailmentEnd.CAPPED
            )
            val (endMs, endedBy) = ends.minBy { it.first }

            if (endMs > settledBy) {
                chains += Chain(at, opened, null)
                undecidedFromMs = undecidedFromMs ?: at
            } else {
                chains += Chain(at, opened, endMs)
                derailments += Derailment(app, at, opened, endMs, endedBy)
            }
        }

        return DerailmentRead(interruptions, derailments, resumeFrom(undecidedFromMs ?: toMs, chains))
    }

    /**
     * Never after a settled derailment's notification while the derailment still holds there: a notification read again
     * from inside it would no longer see it, and would start a derailment of its own.
     */
    private fun resumeFrom(candidateMs: Long, chains: List<Chain>): Long {
        var resumeMs = candidateMs
        while (true) {
            val holding = chains.filter { it.notifiedMs < resumeMs && (it.endMs == null || it.endMs > resumeMs) }
            resumeMs = holding.minOfOrNull { it.notifiedMs } ?: return resumeMs
        }
    }

    /** Every sitting the log shows, closed or still open at [toMs] (`endMs` null), the home screen's included. */
    private fun sittings(events: List<UsageEventRecord>, toMs: Long): List<Sitting> {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        val closed = builder.build(toMs).sessions.map { Sitting(it.packageName, it.startMs, it.endMs) }
        val open = builder.openSessions().map { (packageName, startMs) -> Sitting(packageName, startMs, null) }
        return closed + open
    }
}
