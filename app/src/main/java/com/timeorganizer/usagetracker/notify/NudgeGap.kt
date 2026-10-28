package com.timeorganizer.usagetracker.notify

import android.content.Context

/** The nudges that keep [NudgeGap.MIN_GAP_MS] apart. The break nudge is exempt: a break's end is said when it comes. */
enum class NudgeFamily { CHIME, LOOP, DRIFT, RAMP }

/**
 * Nudges of different kinds keep 10 minutes apart (decision 08-6, the desktop's `HyperfocusPolicy.MinGapBetweenKinds`), so
 * one pickup never brings a loop check-in and a drift nudge seconds apart. Each family records when it posted; before
 * posting, it asks whether another family posted within the gap.
 *
 * What "held" means is the family's own rule: the drift nudge and the start ramp **wait** and are said once the gap has
 * passed, if still due; the time chime and the loop check **skip**, as they do for a call (a late "15 m" or "6 pick-ups" is
 * wrong by then). The ramp is evaluated first where several run together (a block's start doesn't come again, decision 10-8).
 * A family never holds itself back: that is each one's own rule (one per block, one per loop, one per mark).
 *
 * Kept in prefs, so a restart doesn't forget a nudge just posted. This phone's moments, not the account's.
 */
object NudgeGap {

    const val MIN_GAP_MS = 10 * 60_000L

    private const val PREFS = "nudge_gap"

    /**
     * **Pure.** Until when [family] must wait: the end of the gap after the latest other family's post, or `null` when
     * none is that recent. A post stamped after [nowMs] (the clock was moved back) holds nothing.
     */
    fun heldUntil(lastPostedMs: Map<NudgeFamily, Long>, family: NudgeFamily, nowMs: Long): Long? =
        lastPostedMs.filterKeys { it != family }.values
            .filter { nowMs - it in 0 until MIN_GAP_MS }
            .maxOrNull()
            ?.plus(MIN_GAP_MS)

    fun heldUntil(context: Context, family: NudgeFamily, nowMs: Long): Long? = heldUntil(lastPosted(context), family, nowMs)

    fun posted(context: Context, family: NudgeFamily, atMs: Long) {
        prefs(context).edit().putLong(family.name, atMs).apply()
    }

    private fun lastPosted(context: Context): Map<NudgeFamily, Long> {
        val prefs = prefs(context)
        return NudgeFamily.entries.filter { prefs.contains(it.name) }.associateWith { prefs.getLong(it.name, 0L) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
