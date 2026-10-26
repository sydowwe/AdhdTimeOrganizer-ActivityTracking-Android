package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.TrackerProblem

/**
 * **Pure.** What a screen does with the answer of a mapping call (create activity, re-point, unlink, the flag, a link).
 * The status alone can't tell "the name is taken" from "the package is mapped" (both 409) or "the rule is gone" from
 * "the activity is gone" (both 404), so the body's `reason` decides where it is given.
 */
sealed interface MappingOutcome {
    data object Saved : MappingOutcome

    /** An active activity has the name: offer to use it ([activityId]) instead. */
    data class NameTaken(val activityId: Long?) : MappingOutcome

    /** An archived activity has the name: the phone doesn't un-archive — restore it on the web, or pick another name. */
    data object NameArchived : MappingOutcome

    /** Another device mapped the package first: reload. */
    data object AlreadyMapped : MappingOutcome

    /** Another device unlinked or ignored the app: reload the list. */
    data object RuleGone : MappingOutcome

    /** The activity, role or category is not the user's any more (deleted on the web): reload the options. */
    data object Gone : MappingOutcome

    /** Refused as malformed — nothing a retry fixes. */
    data object Refused : MappingOutcome

    /** Nothing was learned: offline, the server busy, a sign-in lapse. Leave things as they were. */
    data object TryAgain : MappingOutcome

    companion object {
        /** [status] `null` = no answer at all. */
        fun of(status: Int?, reason: String?, activityId: Long? = null): MappingOutcome = when (status) {
            null -> TryAgain
            in 200..299 -> Saved
            409 -> when (reason) {
                TrackerProblem.NAME_TAKEN -> NameTaken(activityId)
                TrackerProblem.NAME_ARCHIVED -> NameArchived
                else -> AlreadyMapped
            }
            404 -> if (reason == TrackerProblem.RULE_GONE) RuleGone else Gone
            400, 422 -> Refused
            else -> TryAgain
        }
    }
}
