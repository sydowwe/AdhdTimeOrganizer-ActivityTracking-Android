package com.timeorganizer.usagetracker.ignore

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto

enum class IgnoreOpKind { IGNORE, UNIGNORE }

/**
 * An ignore or stop-ignoring the user asked for that the server has not confirmed yet. The phone acts on it at once
 * (offline included); it clears when the call succeeds or the server's rule list agrees. Stored as JSON (R8 keeps it).
 */
data class PendingIgnoreOp(
    val packageName: String,
    val kind: IgnoreOpKind,
    val sinceMs: Long,
    /** Sent with the ignore so a rule for an app the server never saw a session of still has a name. */
    val appLabel: String? = null,
    /** The server called the call malformed (400 / 422): sending it again can only fail the same way. */
    val rejected: Boolean = false
)

/**
 * **Pure.** What the phone knows about ignored packages: the server's active rules (cached from `GET rules`) and the
 * user's pending ops. What the phone drops is
 * `server ignored ∪ pending ignores − pending un-ignores`, minus every op that was given up: a pending ignore that has
 * not reached the server in [PENDING_IGNORE_MAX_AGE_MS], or any op the server refused. A given-up ignore stops
 * counting so a failed call never leaves the phone dropping an app the server never heard about; it stays in [pending]
 * so the screen can say "couldn't save" until the user retries or the server's list agrees.
 *
 * At most one op per package: a newer one replaces it.
 */
data class IgnoreState(
    val rules: List<AndroidTrackerRuleDto> = emptyList(),
    val pending: List<PendingIgnoreOp> = emptyList()
) {
    val serverIgnored: Set<String>
        get() = rules.filter { it.target == AndroidRuleTarget.IGNORED }.mapTo(mutableSetOf()) { it.packageName }

    fun isGivenUp(op: PendingIgnoreOp, nowMs: Long): Boolean =
        op.rejected || (op.kind == IgnoreOpKind.IGNORE && nowMs - op.sinceMs >= PENDING_IGNORE_MAX_AGE_MS)

    /** The ops the next sync run sends. */
    fun sendable(nowMs: Long): List<PendingIgnoreOp> = pending.filterNot { isGivenUp(it, nowMs) }

    /** The packages whose sessions never enter the outbox. */
    fun ignored(nowMs: Long): Set<String> {
        val live = sendable(nowMs)
        return serverIgnored +
            live.filter { it.kind == IgnoreOpKind.IGNORE }.map { it.packageName } -
            live.filter { it.kind == IgnoreOpKind.UNIGNORE }.map { it.packageName }.toSet()
    }

    fun opFor(packageName: String): PendingIgnoreOp? = pending.firstOrNull { it.packageName == packageName }

    fun ruleFor(packageName: String): AndroidTrackerRuleDto? = rules.firstOrNull { it.packageName == packageName }

    /** The user asked; it replaces whatever was pending for the package. */
    fun withOp(op: PendingIgnoreOp): IgnoreState =
        copy(pending = pending.filterNot { it.packageName == op.packageName } + op)

    /**
     * The server answered [op] with a 2xx. The cached rules take its effect at once — otherwise the package would fall
     * out of the ignored set between now and the next `GET rules`. The op clears only if it is still the pending one:
     * a newer op for the package has yet to be sent.
     */
    fun confirmed(op: PendingIgnoreOp): IgnoreState {
        val rule = ruleFor(op.packageName)
        val others = rules.filterNot { it.packageName == op.packageName }
        val patched = when (op.kind) {
            // PUT turns any rule of the package into the ignore rule.
            IgnoreOpKind.IGNORE ->
                others + AndroidTrackerRuleDto(op.packageName, rule?.appLabel ?: op.appLabel, AndroidRuleTarget.IGNORED, null)
            // DELETE switches an ignore rule off and never touches a mapping.
            IgnoreOpKind.UNIGNORE -> if (rule?.target == AndroidRuleTarget.IGNORED) others else rules
        }
        return IgnoreState(patched.sortedBy { it.packageName }, pending - op)
    }

    /** The server refused [op] as malformed: it stops counting and is never sent again. */
    fun rejected(op: PendingIgnoreOp): IgnoreState =
        copy(pending = pending.map { if (it == op) it.copy(rejected = true) else it })

    /**
     * A fresh `GET rules` answer. An op the list already agrees with is done (its call went through even if the
     * answer was lost, or the web did the same); one it contradicts stays and is sent.
     */
    fun fetched(serverRules: List<AndroidTrackerRuleDto>): IgnoreState {
        val ignoredThere = serverRules.filter { it.target == AndroidRuleTarget.IGNORED }.map { it.packageName }.toSet()
        val stillPending = pending.filterNot { op -> (op.kind == IgnoreOpKind.IGNORE) == (op.packageName in ignoredThere) }
        return IgnoreState(serverRules, stillPending)
    }

    companion object {
        /** A pending ignore that has not reached the server in a week stops counting; the phone uploads the app again. */
        const val PENDING_IGNORE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/** What an ignore or stop-ignoring call's status means for its pending op. */
enum class IgnoreCallOutcome {
    /** The server applied it. */
    CONFIRMED,

    /** 400 / 422: the request itself is wrong; the op is given up. */
    REJECTED,

    /** A 401 the refresh could not cure: stop sending; the op stays. */
    AUTH_REQUIRED,

    /**
     * Nothing learned: offline, 5xx, 429, a 409 race on the server's unique index, a 404 from a proxy or a server from
     * before the route. The op stays and the next sync run sends it again.
     */
    KEEP;

    companion object {
        /** [status] is `null` when no answer came. */
        fun of(status: Int?): IgnoreCallOutcome = when (status) {
            null -> KEEP
            in 200..299 -> CONFIRMED
            400, 422 -> REJECTED
            401 -> AUTH_REQUIRED
            else -> KEEP
        }
    }
}

object OutboxPurge {
    /**
     * The packages whose unsent outbox rows go: queued before the phone knew the rule, they must never leave. Sent rows
     * stay — they only keep a re-read of the log from queuing a session again, and an ignored one is not queued anyway.
     */
    fun packagesToDelete(unsentPackages: Collection<String>, ignored: Set<String>): List<String> =
        unsentPackages.filter { it in ignored }.distinct()
}
