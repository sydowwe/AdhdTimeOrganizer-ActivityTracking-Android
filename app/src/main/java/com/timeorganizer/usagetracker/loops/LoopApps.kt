package com.timeorganizer.usagetracker.loops

import com.timeorganizer.usagetracker.ignore.AppUsage

data class LoopAppRow(
    val packageName: String,
    val label: String,
    /** Foreground time in the week; `null` for a ticked app that isn't among the week's. */
    val foregroundMs: Long?,
    val notCounted: Boolean
)

data class LoopAppList(
    /** The week's apps: the ticked ones first, then busiest first. */
    val recent: List<LoopAppRow>,
    /** Ticked apps that weren't used this week, so every tick can be undone. */
    val others: List<LoopAppRow>
)

/** **Pure.** The "Apps that don't count" screen's rows: the chat apps a pickup into isn't part of a loop. */
object LoopAppsList {

    /** [excluded]: the home apps and this app. [installedLabel]: `null` for an app not on this phone. */
    fun build(
        usage: List<AppUsage>,
        excluded: Set<String>,
        notCounted: Set<String>,
        installedLabel: (String) -> String?
    ): LoopAppList {
        val recent = usage
            .filter { it.packageName !in excluded }
            .map { LoopAppRow(it.packageName, it.label, it.foregroundMs, it.packageName in notCounted) }
            .sortedWith(
                compareByDescending<LoopAppRow> { it.notCounted }
                    .thenByDescending { it.foregroundMs }
                    .thenBy { it.label.lowercase() }
            )

        val shown = recent.mapTo(mutableSetOf()) { it.packageName }
        val others = notCounted
            .filter { it !in shown && it !in excluded }
            .map { LoopAppRow(it, installedLabel(it) ?: it, null, notCounted = true) }
            .sortedBy { it.label.lowercase() }

        return LoopAppList(recent, others)
    }
}
