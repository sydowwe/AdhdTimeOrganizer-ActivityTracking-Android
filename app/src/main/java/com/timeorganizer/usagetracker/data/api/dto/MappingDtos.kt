package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET tracker/android/activities`: everything the activity picker and its "+ New activity" dialog need, in one read.
 * Every name is the user's: never log one.
 */
data class TrackerActivityOptionsResponse(
    val activities: List<TrackerActivityDto>?,
    val roles: List<TrackerRoleDto>?,
    val categories: List<TrackerCategoryDto>?,
    /** The user's "Phone app" role, a new activity's default; `null` until the server provisions it on the first create. */
    val phoneAppRoleId: Long?,
    val appCategoryLinks: List<AppCategoryLinkDto>?
)

data class TrackerRoleDto(
    val id: Long,
    val name: String
)

data class TrackerCategoryDto(
    val id: Long,
    val name: String,
    /** What an activity that follows its category reads as. */
    val isDistraction: Boolean
)

/**
 * An Android app category (`ApplicationInfo.category`, by the server's member name — see `mapping/AppCategory`) linked to
 * a role and/or category: the picker's filter for apps of that category. At least one id is set.
 */
data class AppCategoryLinkDto(
    val appCategory: String,
    val roleId: Long?,
    val categoryId: Long?
)

/** `PUT tracker/android/app-category-links/{appCategory}`; both `null` deletes the link (204). */
data class AppCategoryLinkRequest(
    val roleId: Long?,
    val categoryId: Long?
)

/**
 * `POST tracker/android/activities`: a new activity, optionally mapping a package in the same transaction. [roleId]
 * `null` is the server's "Phone app" role. [isDistraction] `null` follows the category.
 */
data class CreateTrackerActivityRequest(
    val name: String,
    val roleId: Long?,
    val categoryId: Long?,
    val isDistraction: Boolean?,
    val mapPackage: String? = null,
    val appLabel: String? = null,
    val backfillDays: Int = 0
)

data class CreateTrackerActivityResponse(
    val activityId: Long,
    val roleId: Long,
    /** The package's rule, when the request mapped one. */
    val ruleId: Long?,
    val attributedSeconds: Int
)

/** `PATCH tracker/android/rules/{id}`: the rule points at this activity from now on. */
data class RepointTrackerRuleRequest(
    val activityId: Long
)

/** `PATCH tracker/android/activities/{id}/distraction`. Gson writes no `null`, and an absent field is `null` on the server too. */
data class SetDistractionRequest(
    val isDistraction: Boolean?
)

data class DistractionResponse(
    val ownDistraction: Boolean?,
    val isDistraction: Boolean
)

/** The body of a mapping route's 404 / 409 — see `mapping/MappingOutcome`. */
data class TrackerProblemResponse(
    val reason: String?,
    val activityId: Long?
)

/** The spellings of [TrackerProblemResponse.reason] (the server's `AndroidTrackerProblem`). */
object TrackerProblem {
    const val NAME_TAKEN = "nameTaken"
    const val NAME_ARCHIVED = "nameArchived"
    const val ALREADY_MAPPED = "alreadyMapped"
    const val RULE_GONE = "ruleGone"
    const val ACTIVITY_GONE = "activityGone"
}
