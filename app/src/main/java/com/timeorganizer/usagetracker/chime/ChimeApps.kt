package com.timeorganizer.usagetracker.chime

import com.timeorganizer.usagetracker.ignore.AppUsage

/**
 * Which apps the chime is for: the server's distraction apps while [followDistractions] is on, plus the ones ticked by
 * hand, minus the distraction apps unticked by hand. Exact package names only: what is a distraction is the server's
 * call (`/now`'s `distractionPackages`), never a matcher here.
 */
data class ChimeChoices(
    val followDistractions: Boolean = true,
    /** Ticked by hand: chimed whatever the server says. */
    val added: Set<String> = emptySet(),
    /** Distraction apps unticked by hand: never chimed, even when the server flags them again. */
    val removed: Set<String> = emptySet()
) {
    fun chosen(distractions: Set<String>): Set<String> =
        (if (followDistractions) distractions else emptySet()) + added - removed

    /** The row's switch. A distraction app the switch covers is ticked by leaving it be, and unticked by remembering it. */
    fun set(packageName: String, on: Boolean, distractions: Set<String>): ChimeChoices {
        val followed = followDistractions && packageName in distractions
        return if (on) {
            copy(added = if (followed) added - packageName else added + packageName, removed = removed - packageName)
        } else {
            copy(added = added - packageName, removed = if (followed) removed + packageName else removed)
        }
    }
}

enum class ChimeReason {
    /** Not chimed. */
    NONE,

    /** A distraction app, chimed because the switch follows them. */
    DISTRACTION,

    /** Ticked by hand. */
    CHOSEN,

    /** A distraction app unticked by hand. */
    UNTICKED
}

data class ChimeAppRow(
    val packageName: String,
    val label: String,
    /** Foreground time in the week; `null` for a chosen app that isn't among the week's. */
    val foregroundMs: Long?,
    val distraction: Boolean,
    val reason: ChimeReason
) {
    val chimed: Boolean get() = reason == ChimeReason.DISTRACTION || reason == ChimeReason.CHOSEN
}

data class ChimeAppList(
    /** The week's apps: the distraction ones first, then busiest first. */
    val recent: List<ChimeAppRow>,
    /** Chosen apps on this phone that weren't used this week, so every choice can be undone. */
    val others: List<ChimeAppRow>
)

/** **Pure.** The "Time chime" screen's rows. */
object ChimeAppsList {

    /** [excluded]: the home apps and this app. [installedLabel]: `null` for an app not on this phone. */
    fun build(
        usage: List<AppUsage>,
        excluded: Set<String>,
        choices: ChimeChoices,
        distractions: Set<String>,
        installedLabel: (String) -> String?
    ): ChimeAppList {
        val recent = usage
            .filter { it.packageName !in excluded }
            .map { row(it.packageName, it.label, it.foregroundMs, choices, distractions) }
            .sortedWith(
                compareByDescending<ChimeAppRow> { it.distraction }
                    .thenByDescending { it.foregroundMs }
                    .thenBy { it.label.lowercase() }
            )

        val shown = recent.mapTo(mutableSetOf()) { it.packageName }
        // A distraction app from another of the user's devices isn't on this phone: nothing to show for it.
        val others = choices.chosen(distractions)
            .filter { it !in shown && it !in excluded }
            .mapNotNull { packageName ->
                val label = installedLabel(packageName) ?: packageName.takeIf { it in choices.added } ?: return@mapNotNull null
                row(packageName, label, null, choices, distractions)
            }
            .sortedBy { it.label.lowercase() }

        return ChimeAppList(recent, others)
    }

    /** How many apps on this phone the chime is for — the status card's line. */
    fun chimedCount(choices: ChimeChoices, distractions: Set<String>, installed: (String) -> Boolean): Int =
        choices.chosen(distractions).count { it in choices.added || installed(it) }

    private fun row(
        packageName: String,
        label: String,
        foregroundMs: Long?,
        choices: ChimeChoices,
        distractions: Set<String>
    ): ChimeAppRow {
        val distraction = packageName in distractions
        val reason = when {
            packageName in choices.removed && distraction && choices.followDistractions -> ChimeReason.UNTICKED
            packageName in choices.added -> ChimeReason.CHOSEN
            distraction && choices.followDistractions -> ChimeReason.DISTRACTION
            else -> ChimeReason.NONE
        }
        return ChimeAppRow(packageName, label, foregroundMs, distraction, reason)
    }
}
