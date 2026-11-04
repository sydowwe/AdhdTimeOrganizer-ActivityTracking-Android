package com.timeorganizer.usagetracker.today

import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.pause.PauseRange

/** A planner block the widget keeps between syncs. Stored as JSON in prefs (`proguard-rules.pro` keeps its names). */
data class KeptBlock(
    /** User-authored: never log it. */
    val name: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * **Pure.** What the widget keeps from `/now`, which kept block is current, and when it goes stale. `NowClient`'s answer lives
 * in memory for 30 s, so the widget keeps its own: the blocks covering now and the next one, which lets a block's start or end
 * show on time with no network (decision 19-5).
 */
object WidgetPlan {

    /** An unnamed block says nothing on a widget. */
    fun keep(facts: NowFacts): List<KeptBlock> =
        (facts.current + listOfNotNull(facts.next))
            .filter { it.activityName.isNotBlank() }
            .map { KeptBlock(it.activityName, it.startMs, it.endMs) }

    /** The earliest kept block covering [nowMs], as `/now` orders them; one past its end is never shown. */
    fun current(blocks: List<KeptBlock>, nowMs: Long): KeptBlock? =
        blocks.filter { nowMs >= it.startMs && nowMs < it.endMs }.minByOrNull { it.startMs }

    /** The next moment the widget's text is wrong: a block's start or end, the pause's end, or [nextDayStartMs]. */
    fun nextRefreshMs(blocks: List<KeptBlock>, pause: PauseRange?, nextDayStartMs: Long, nowMs: Long): Long {
        val pauseEnd = pause?.takeUnless { it.untilResumed }?.endMs
        return (blocks.flatMap { listOf(it.startMs, it.endMs) } + listOfNotNull(pauseEnd, nextDayStartMs))
            .filter { it > nowMs }
            .min()
    }
}
