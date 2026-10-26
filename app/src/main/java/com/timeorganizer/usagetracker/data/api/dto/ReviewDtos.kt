package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET tracker/android/unmapped`: today's phone time no active rule maps. [apps] empty is the
 * server's "don't ask" — too little unmapped time in all, or no package holding enough of it.
 */
data class UnmappedTodayResponse(
    val date: String?,
    val trackedSeconds: Long,
    val unmappedSeconds: Long,
    val apps: List<UnmappedAppDto>?
)

data class UnmappedAppDto(
    val packageName: String,
    val appLabel: String,
    val seconds: Long,
    /** The activity the user's own timers point this package at, when the server has a suggestion. */
    val suggestedActivityId: Long?,
    val suggestedActivityName: String?
)

/** One activity of `GET tracker/android/activities` ([TrackerActivityOptionsResponse.activities]). */
data class TrackerActivityDto(
    val id: Long,
    val name: String,
    /** What the picker filters by (`mapping/ActivityPickerFilter`). */
    val roleId: Long = 0,
    val categoryId: Long? = null
)

/**
 * `POST tracker/android/rules`: one package to an activity; the server builds the exact-package rule itself. The route
 * also takes `isIgnored`, but the phone ignores only through `PUT tracker/android/ignored/{packageName}` — one ignore,
 * which the phone also stops uploading (`ignore/IgnoreSync`).
 */
data class CreateTrackerRuleRequest(
    val packageName: String,
    val activityId: Long,
    /** 0 = today only; `MappingRules.WEEK_BACKFILL_DAYS` is "also add this week's time". */
    val backfillDays: Int = 0
)

data class CreateTrackerRuleResponse(
    val ruleId: Long,
    /** What the mapping added to History from the package's time already stored today. */
    val attributedSeconds: Int
)

/** `GET tracker/evening-shutdown-moment`: when the user's evening begins, as a UTC instant. */
data class EveningMomentResponse(
    val enabled: Boolean,
    val momentUtc: String?
)
