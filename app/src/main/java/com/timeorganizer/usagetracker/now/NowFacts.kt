package com.timeorganizer.usagetracker.now

import com.timeorganizer.usagetracker.data.api.dto.TrackerNowBlockDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerNowResponse
import com.timeorganizer.usagetracker.review.ReviewText

/** A planner block from `/now`, its instants read. */
data class NowBlock(
    val plannerTaskId: Long,
    /** User-authored: never log it. */
    val activityName: String,
    val startMs: Long,
    val endMs: Long,
    val inProgress: Boolean,
    /** The block's activity; `null` when the wire had none. The drift nudge compares it with the apps' rules. */
    val activityId: Long? = null,
    /** Its importance is Optional: the start ramp's "All but Optional" leaves it out. */
    val isOptional: Boolean = false,
    /** Critical or ranked above it: all the start ramp's "Critical only" asks about. */
    val isCritical: Boolean = false
)

/** What the desk was doing in its last minutes: the busiest slice the server could name. */
data class DeskWork(
    /** The activity, or the role / category. User-authored. */
    val name: String,
    /** Fits the block that covered the desk's last minute; `null` when nothing was planned then. */
    val onPlan: Boolean?
)

/**
 * `GET tracker/android/now` as the nudges read it — pure, so it is pinned by the wire sample. The server decides
 * every part (the plan, what the desk's minutes were, whether to be silent); this only reads the instants and picks
 * what a nudge needs. A field the phone can't read counts as absent, never as a guess: an unreadable block is not a
 * plan, an unreadable desk instant is no desk.
 */
data class NowFacts(
    /** Stay silent whatever the rest says: the server named a reason (an away day, quiet hours, absent). */
    val silent: Boolean,
    /** The open foreground blocks covering now, earliest first. */
    val current: List<NowBlock>,
    /** When the desk's newest active minute ended; `null` with none in the last six hours. */
    val deskLastActiveMs: Long?,
    val deskIdle: Boolean,
    /** The busiest named slice of the desk's last minutes; `null` when they were all unmapped or ignored. */
    val deskWork: DeskWork?,
    val distractionPackages: Set<String>,
    /** The first open block starting later today; `null` with none, or when it can't be read. */
    val next: NowBlock? = null,
    /** A countdown or pomodoro runs; `null` when the server didn't say (one from before prompt 10): unknown. */
    val timerRunning: Boolean? = null
) {
    companion object {
        fun from(response: TrackerNowResponse): NowFacts {
            val desktop = response.desktop
            return NowFacts(
                // Any reason, a new one included: being silent by mistake costs a nudge, speaking costs trust.
                silent = response.silentReason != null,
                current = response.plan?.current.orEmpty().mapNotNull(::block),
                deskLastActiveMs = ReviewText.instantMs(desktop?.lastActiveAt),
                deskIdle = desktop?.isIdle ?: true,
                // Busiest first on the wire; ignored and unmapped time has no name.
                deskWork = desktop?.recent.orEmpty()
                    .firstOrNull { !it.name.isNullOrBlank() && it.target != TARGET_IGNORED }
                    ?.let { DeskWork(it.name!!, it.onPlan) },
                distractionPackages = response.distractionPackages.orEmpty().toSet(),
                next = response.plan?.next?.let(::block),
                timerRunning = response.timerRunning
            )
        }

        /** `null` when an instant can't be read: an unreadable block is no plan. Gson leaves a missing id at `0`. */
        private fun block(block: TrackerNowBlockDto): NowBlock? {
            val startMs = ReviewText.instantMs(block.startsAt) ?: return null
            val endMs = ReviewText.instantMs(block.endsAt) ?: return null
            return NowBlock(
                block.plannerTaskId, block.activityName.orEmpty(), startMs, endMs, block.inProgress,
                block.activityId.takeIf { it > 0 },
                isOptional = block.isOptional,
                isCritical = block.isCritical
            )
        }

        private const val TARGET_IGNORED = "Ignored"
    }
}
