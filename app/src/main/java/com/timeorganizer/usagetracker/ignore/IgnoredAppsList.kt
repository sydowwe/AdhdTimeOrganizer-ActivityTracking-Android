package com.timeorganizer.usagetracker.ignore

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget

/** One app's foreground time in the window, as `UsageStatsManager` aggregates it; only apps with a launcher entry. */
data class AppUsage(
    val packageName: String,
    val label: String,
    val foregroundMs: Long
)

enum class AppIgnoreState {
    /** Recorded as usual. */
    NONE,

    /** The server's ignore rule. */
    IGNORED,

    /** Ignored on the phone, not on the server yet. */
    IGNORING,

    /** Recorded again on the phone, not confirmed by the server yet. */
    STOPPING,

    /** The ignore never reached the server (a week, or refused): the phone records the app again. */
    IGNORE_FAILED,

    /** The server refused the stop: the app stays ignored. */
    STOP_FAILED;

    /** Whether the phone drops the app now — the row offers "Stop ignoring" rather than "Ignore". */
    val isIgnored: Boolean get() = this == IGNORED || this == IGNORING || this == STOP_FAILED
}

data class AppRow(
    val packageName: String,
    val label: String,
    /** Foreground time in the window; `null` for an ignored app that is not among the recent ones. */
    val foregroundMs: Long?,
    val state: AppIgnoreState,
    /** The activity or "Role · Category" a mapping rule points the app at — what an ignore replaces. */
    val mappedTo: String?
)

data class AppList(
    /** The apps used in the window, busiest first. The screen shows the first [IgnoredAppsList.TOP_COUNT] until "Show all". */
    val recent: List<AppRow>,
    /** Ignored (or pending) apps not among [recent] — unused lately or uninstalled — so every ignore can be undone. */
    val others: List<AppRow>
)

/** **Pure.** The "Ignored apps" screen's rows from the week's usage and what the phone knows about ignores. */
object IgnoredAppsList {

    const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    const val MIN_FOREGROUND_MS = 60_000L
    const val TOP_COUNT = 30

    /** [excluded]: the home apps and this app — never offered. */
    fun build(usage: List<AppUsage>, excluded: Set<String>, state: IgnoreState, nowMs: Long): AppList {
        val recent = usage
            .filter { it.foregroundMs >= MIN_FOREGROUND_MS && it.packageName !in excluded }
            .sortedWith(compareByDescending<AppUsage> { it.foregroundMs }.thenBy { it.label.lowercase() })
            .map { row(it.packageName, it.label, it.foregroundMs, state, nowMs) }

        val shown = recent.mapTo(mutableSetOf()) { it.packageName }
        val others = (state.serverIgnored + state.pending.map { it.packageName })
            .filter { it !in shown }
            .map { packageName ->
                val label = state.ruleFor(packageName)?.appLabel ?: state.opFor(packageName)?.appLabel ?: packageName
                row(packageName, label, null, state, nowMs)
            }
            .sortedBy { it.label.lowercase() }

        return AppList(recent, others)
    }

    fun stateOf(packageName: String, state: IgnoreState, nowMs: Long): AppIgnoreState {
        val op = state.opFor(packageName)
            ?: return if (packageName in state.serverIgnored) AppIgnoreState.IGNORED else AppIgnoreState.NONE
        val givenUp = state.isGivenUp(op, nowMs)
        return when (op.kind) {
            IgnoreOpKind.IGNORE -> if (givenUp) AppIgnoreState.IGNORE_FAILED else AppIgnoreState.IGNORING
            IgnoreOpKind.UNIGNORE -> if (givenUp) AppIgnoreState.STOP_FAILED else AppIgnoreState.STOPPING
        }
    }

    private fun row(packageName: String, label: String, foregroundMs: Long?, state: IgnoreState, nowMs: Long): AppRow {
        val rule = state.ruleFor(packageName)
        val mappedTo = rule?.takeIf { it.target == AndroidRuleTarget.ACTIVITY || it.target == AndroidRuleTarget.ROLE_CATEGORY }?.targetName
        return AppRow(packageName, label, foregroundMs, stateOf(packageName, state, nowMs), mappedTo)
    }
}
