package com.timeorganizer.usagetracker.data.api.dto

/**
 * One active rule in `GET tracker/android/rules` — the phone's whole view of what the user decided about a package.
 * The rules are exact packages (the server builds them; the phone never holds a matcher), so the phone's ignored set
 * is simply the packages whose [target] is [AndroidRuleTarget.IGNORED], and the "Mapped apps" screen is the
 * [AndroidRuleTarget.ACTIVITY] ones.
 *
 * Every field added by prompt 27 is nullable or primitive with a default: the list is also cached as JSON
 * (`IgnoreStore`), and Gson fills a field an older cached copy lacks with `null` / `0` / `false` whatever Kotlin says.
 */
data class AndroidTrackerRuleDto(
    val packageName: String,
    /** The app's name as the server last saw it; `null` for a rule on an app it never stored a session of. */
    val appLabel: String?,
    /** One of [AndroidRuleTarget]; an unknown value from a newer server is neither ignored nor a mapping shown. */
    val target: String,
    /** The activity the rule maps to; `null` for an ignore rule. */
    val targetName: String?,
    /** The key of `PATCH` / `DELETE tracker/android/rules/{id}`; 0 in a cache written before prompt 27. */
    val ruleId: Long = 0,
    val activityId: Long? = null,
    /** The activity is archived; its rule still maps. */
    val activityArchived: Boolean = false,
    val categoryName: String? = null,
    /** The activity's own flag; `null` follows its category. */
    val ownDistraction: Boolean? = null,
    /** The activity's category's flag — what "follow category" means; `null` without a category. */
    val categoryDistraction: Boolean? = null,
    /** The effective flag, the server's — never re-derived here. */
    val isDistraction: Boolean = false
)

/**
 * The spellings of [AndroidTrackerRuleDto.target] and [PreviousTargetDto.target] (the server's `AndroidRuleTarget`). The
 * server's `roleCategory` went with prompt 27: a phone app maps to an activity or is ignored.
 */
object AndroidRuleTarget {
    const val IGNORED = "ignored"
    const val ACTIVITY = "activity"
}

/**
 * `PUT tracker/android/ignored/{packageName}`. The package is the route's; the label is display only. [appCategory] is
 * as on a synced session ([AndroidSessionDto.appCategory]): an ignored app is never synced again, so this is the one
 * time the server learns its category.
 */
data class IgnorePackageRequest(
    val appLabel: String?,
    val appCategory: String? = null
)

data class IgnorePackageResponse(
    val ruleId: Long,
    /** Stored sessions of the package the ignore deleted. */
    val purgedSessions: Int,
    /** The mapping in force that the ignore replaced; `null` when there was none (or it already was ignored). */
    val previousTarget: PreviousTargetDto?
)

data class PreviousTargetDto(
    val target: String,
    val targetName: String?
)
