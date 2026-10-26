package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.ignore.AppUsage

/** One row of the "Mapped apps" screen: an app, the activity its rule maps it to, and the activity's flags. */
data class MappedAppRow(
    val ruleId: Long,
    val packageName: String,
    /** The installed app's own name, else the server's last label, else the package. */
    val label: String,
    val activityId: Long,
    val activityName: String,
    val activityArchived: Boolean,
    val categoryName: String?,
    val ownDistraction: Boolean?,
    val categoryDistraction: Boolean?,
    val isDistraction: Boolean,
    /** The other mapped apps on the same activity, by label — what a flag change also reaches. */
    val sharedWith: List<String>
)

/** **Pure.** The "Mapped apps" rows from the server's rules, and the lines the screen says about them. */
object MappedAppsList {

    /** How many other apps a warning names before "and N more". */
    const val NAMED_SHARERS = 2

    /**
     * The activity rules, by label (case-insensitive, then package). An ignore rule is not a mapping (prompt 26's card owns
     * it), and a rule from a cache older than prompt 27 (no id or activity) can't be changed, so neither is a row.
     * [installedLabel] is `PackageManager`'s name, `null` for an app no longer installed.
     */
    fun build(rules: List<AndroidTrackerRuleDto>, installedLabel: (String) -> String?): List<MappedAppRow> {
        val mapped = rules.filter { it.target == AndroidRuleTarget.ACTIVITY && it.ruleId > 0 && it.activityId != null }
        val labels = mapped.associate { it.packageName to (installedLabel(it.packageName) ?: it.appLabel ?: it.packageName) }

        return mapped
            .map { rule ->
                MappedAppRow(
                    ruleId = rule.ruleId,
                    packageName = rule.packageName,
                    label = labels.getValue(rule.packageName),
                    activityId = rule.activityId!!,
                    activityName = rule.targetName ?: "",
                    activityArchived = rule.activityArchived,
                    categoryName = rule.categoryName,
                    ownDistraction = rule.ownDistraction,
                    categoryDistraction = rule.categoryDistraction,
                    isDistraction = rule.isDistraction,
                    sharedWith = mapped
                        .filter { it.activityId == rule.activityId && it.packageName != rule.packageName }
                        .map { labels.getValue(it.packageName) }
                        .sortedBy { it.lowercase() }
                )
            }
            .sortedWith(compareBy<MappedAppRow> { it.label.lowercase() }.thenBy { it.packageName })
    }

    /** The packages a picker for "Add app" must not offer: already mapped, or ignored — both are the server's rules. */
    fun decided(rules: List<AndroidTrackerRuleDto>): Set<String> = rules
        .filter { it.target == AndroidRuleTarget.ACTIVITY || it.target == AndroidRuleTarget.IGNORED }
        .mapTo(mutableSetOf()) { it.packageName }

    /**
     * What "Add app" offers: the recently used apps nobody decided about yet — not mapped, not ignored (the server's or a
     * pending one), not [excluded] (the home apps, this app) — busiest first.
     */
    fun addable(usage: List<AppUsage>, decided: Set<String>, excluded: Set<String>): List<AppUsage> = usage
        .filter { it.packageName !in decided && it.packageName !in excluded }
        .sortedWith(compareByDescending<AppUsage> { it.foregroundMs }.thenBy { it.label.lowercase() })

    /** "Reddit, Instagram and 2 more" — the names the flag warning lists; `null` when the activity is this app's alone. */
    data class Sharers(val named: List<String>, val more: Int)

    fun sharers(row: MappedAppRow): Sharers? =
        if (row.sharedWith.isEmpty()) null
        else Sharers(row.sharedWith.take(NAMED_SHARERS), (row.sharedWith.size - NAMED_SHARERS).coerceAtLeast(0))

    /** How a row states its flag: the activity's own, or what following its category comes to. */
    enum class FlagText { OWN_YES, OWN_NO, FOLLOW_YES, FOLLOW_NO, FOLLOW_NONE }

    /** Following reads the server's effective flag, never re-derived from the category here. */
    fun flagText(row: MappedAppRow): FlagText = when (row.ownDistraction) {
        true -> FlagText.OWN_YES
        false -> FlagText.OWN_NO
        null -> when {
            row.categoryName == null -> FlagText.FOLLOW_NONE
            row.isDistraction -> FlagText.FOLLOW_YES
            else -> FlagText.FOLLOW_NO
        }
    }

    /** What "follow the category" would mean for the row's activity, for the flag dialog. */
    fun followMeaning(row: MappedAppRow): FollowMeaning =
        if (row.categoryName == null) FollowMeaning.NoCategory
        else FollowMeaning.Category(row.categoryName, row.categoryDistraction ?: false)
}
