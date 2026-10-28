package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET tracker/android/now`: the server's view of right now, which every nudge asks before it speaks. Instants and
 * flags only, no "now" and no ages, so an unchanged answer is a `304` and the phone measures ages on its own clock.
 * Read through `now/NowFacts`, never field by field.
 */
data class TrackerNowResponse(
    /** `null`: nudging is fine. Anything else (away day, quiet hours, absent): stay silent, whatever the rest says. */
    val silentReason: String?,
    val plan: TrackerNowPlanDto?,
    val desktop: TrackerNowDesktopDto?,
    /** Exact package names the user's rules call distractions. */
    val distractionPackages: List<String>?,
    /** A countdown or pomodoro is running. `null` from a server before prompt 10: unknown, never "no timer". */
    val timerRunning: Boolean? = null
)

data class TrackerNowPlanDto(
    /** The open foreground blocks covering now, earliest first. */
    val current: List<TrackerNowBlockDto>?,
    /** The first open one starting later today. */
    val next: TrackerNowBlockDto?
)

data class TrackerNowBlockDto(
    val plannerTaskId: Long,
    val activityId: Long,
    /** User-authored: never log it. */
    val activityName: String?,
    val startsAt: String?,
    val endsAt: String?,
    val inProgress: Boolean,
    /** The block's importance is Optional. Missing (an older server) reads as `false`: a block of no importance. */
    val isOptional: Boolean = false,
    /** Critical, or a level the user ranked above it. */
    val isCritical: Boolean = false
)

/** `POST tracker/android/start-block`: the start ramp's **Start now**. */
data class StartBlockRequest(val plannerTaskId: Long)

data class TrackerNowDesktopDto(
    /** When the desk's newest active minute ended (UTC); `null` with none in the last six hours. */
    val lastActiveAt: String?,
    val isIdle: Boolean,
    /** What the last five desk minutes resolved to, busiest first. */
    val recent: List<DesktopSliceDto>?
)

/** One slice of the desk's recent minutes: the seconds that resolved to one target. */
data class DesktopSliceDto(
    /** `Activity`, `RoleOrCategory`, `Ignored`, or `null` for unmapped time. */
    val target: String?,
    val activityId: Long?,
    /** The activity's name, or the role / category; `null` for ignored and unmapped time. User-authored. */
    val name: String?,
    val activeSeconds: Int,
    /** Fits the block covering that last desk minute; `null` when nothing was planned or it can't tell. */
    val onPlan: Boolean?
)
