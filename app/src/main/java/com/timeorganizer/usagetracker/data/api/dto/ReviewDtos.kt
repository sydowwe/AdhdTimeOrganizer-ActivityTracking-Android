package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET activity-tracking/android/agent-unmapped`: today's phone time no active rule maps. [apps] empty is the
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

/** `GET activity-tracking/android/agent-activities`: the pick list. */
data class AgentActivityDto(
    val id: Long,
    val name: String
)

/**
 * `POST activity-tracking/android/agent-rules`: one package to an activity, or ignored. Exactly one of [activityId]
 * and [isIgnored] is set; the server builds the exact-package rule itself.
 */
data class CreateAgentRuleRequest(
    val packageName: String,
    val activityId: Long? = null,
    val isIgnored: Boolean? = null
)

data class CreateAgentRuleResponse(
    val ruleId: Long,
    /** What the mapping added to History from the package's time already stored today. */
    val attributedSeconds: Int
)

/** `GET user/evening-shutdown-moment`: when the user's evening begins, as a UTC instant. */
data class EveningMomentResponse(
    val enabled: Boolean,
    val momentUtc: String?
)
