package com.timeorganizer.usagetracker.review

/** What the screen does with the server's answer to "map this package". */
enum class RuleOutcome {
    /** The rule is in force. */
    SAVED,

    /** An active rule already covers the package (another device or tab got there first): reload the list. */
    ALREADY_MAPPED,

    /** The activity is not the user's any more (deleted on the web): reload the pick list and choose again. */
    ACTIVITY_GONE,

    /** Nothing was learned about the rule: offline, the server busy, a sign-in lapse. The row stays as it was. */
    TRY_AGAIN;

    companion object {
        fun of(status: Int): RuleOutcome = when (status) {
            in 200..299 -> SAVED
            409 -> ALREADY_MAPPED
            404 -> ACTIVITY_GONE
            else -> TRY_AGAIN
        }
    }
}
