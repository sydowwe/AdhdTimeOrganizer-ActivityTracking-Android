package com.timeorganizer.usagetracker.data.api.dto

/**
 * [pickups] and [loops] since prompt 14. A server from before ignores them; the phone sends them in requests of their own
 * (empty [sessions]), after the sessions, so an older server's refusal of either could never cost the other.
 */
data class AndroidSyncRequest(
    val sessions: List<AndroidSessionDto>,
    val syncedUpToUtc: String,
    val deviceId: String,
    val pickups: List<AndroidPickupDto> = emptyList(),
    val loops: List<AndroidPickupLoopDto> = emptyList()
)

/** A pickup that is over. Instants as a session's: UTC ISO-8601 with milliseconds. */
data class AndroidPickupDto(
    val startUtc: String,
    val endUtc: String,
    val unlocked: Boolean
)

/** A loop the check saw. [rule] and [answer] are the server's member names (`LoopRule.wire`, `LoopAnswer.wire`). */
data class AndroidPickupLoopDto(
    val startUtc: String,
    val endUtc: String,
    val rule: String,
    val pickups: Int,
    val apps: Int,
    val answer: String
)
