package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET tracker/android/budgets?deviceId=`: the daily limits a phone app counts toward (prompt 13), with today's figures.
 * Read through `budget/BudgetFacts`; every field may be missing on the wire and is then absent, never guessed.
 */
data class BudgetsResponse(
    /** The user's today, `yyyy-MM-dd`. The claim names it. */
    val day: String?,
    /** The user's midnight starting [day], UTC: where the phone's own count starts. */
    val dayStartsAt: String?,
    /** The next midnight, UTC. Past it the answer is yesterday's. */
    val dayEndsAt: String?,
    val budgets: List<BudgetDto>?
)

data class BudgetDto(
    val budgetId: Long,
    val activityId: Long?,
    val categoryId: Long?,
    /** The activity's or category's name. User-authored: never log it. */
    val targetName: String?,
    val limitSeconds: Long,
    val warnAtEightyPercent: Boolean,
    /** The 80 % stage was said today, by anyone. */
    val warnedToday: Boolean,
    /** The limit was said today, by anyone. */
    val reachedToday: Boolean,
    /** Today's time on the target from every source. */
    val totalSeconds: Long,
    /** The part of [totalSeconds] this phone's synced sessions put there; the phone replaces it with its own count. */
    val thisPhoneSeconds: Long,
    /** Exact packages, server-resolved: never matched on the phone. */
    val packages: List<String>?
)

/** `POST tracker/android/budgets/{id}/claim`: the phone is about to say [stage] (`approaching` / `reached`) for [day]. */
data class BudgetClaimRequest(val stage: String, val day: String)

/** `true`: the phone says it and the server never will. `false`: said already (or the day is over) — stay quiet. */
data class BudgetClaimResponse(val claimed: Boolean)
