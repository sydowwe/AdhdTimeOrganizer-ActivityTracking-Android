package com.timeorganizer.usagetracker.data.api.dto

/**
 * One active rule in `GET tracker/android/rules` — the phone's whole view of what the user decided about a package.
 * The rules are exact packages (the server builds them; the phone never holds a matcher), so the phone's ignored set
 * is simply the packages whose [target] is [AndroidRuleTarget.IGNORED].
 */
data class AndroidTrackerRuleDto(
    val packageName: String,
    /** The app's name as the server last saw it; `null` for a rule on an app it never stored a session of. */
    val appLabel: String?,
    /** One of [AndroidRuleTarget]; an unknown value from a newer server is neither ignored nor a mapping shown. */
    val target: String,
    /** The activity, or "Role · Category", the rule maps to; `null` for an ignore rule. */
    val targetName: String?
)

/** The spellings of [AndroidTrackerRuleDto.target] and [PreviousTargetDto.target] (the server's `AndroidRuleTarget`). */
object AndroidRuleTarget {
    const val IGNORED = "ignored"
    const val ACTIVITY = "activity"
    const val ROLE_CATEGORY = "roleCategory"
}

/** `PUT tracker/android/ignored/{packageName}`. The package is the route's; the label is display only. */
data class IgnorePackageRequest(
    val appLabel: String?
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
