package com.timeorganizer.usagetracker.sync

/** What a sync request's answer means for the sessions in it. */
sealed interface BatchResult {
    data class Sent(val counts: SyncCounts) : BatchResult

    /** Offline, a 5xx, a throttle, a concurrent-duplicate 409: the batch is fine, try it again later. */
    data object RetryLater : BatchResult

    /** A 401 the token refresh could not cure — the user is signed out, or the refresh is unavailable. */
    data object AuthRequired : BatchResult

    /** The server called the payload malformed. Re-sending it unchanged can only fail the same way. */
    data object Rejected : BatchResult
}

data class SyncCounts(val accepted: Int = 0, val duplicates: Int = 0, val dropped: Int = 0, val ignored: Int = 0) {
    operator fun plus(other: SyncCounts) =
        SyncCounts(accepted + other.accepted, duplicates + other.duplicates, dropped + other.dropped, ignored + other.ignored)
}

object ResponseClassifier {

    /**
     * Only a payload complaint (400, 413, 422) is [BatchResult.Rejected]. Every other 4xx is retried: a 404
     * from a proxy or a moved route says nothing about the sessions, and dropping on it would empty the
     * outbox. 409 is the server's answer to two syncs storing the same rows at once; on the retry they are
     * duplicates.
     */
    fun classify(statusCode: Int): BatchResult? = when (statusCode) {
        in 200..299 -> null
        401 -> BatchResult.AuthRequired
        400, 413, 422 -> BatchResult.Rejected
        else -> BatchResult.RetryLater
    }
}

data class PassResult(
    val counts: SyncCounts,
    val rejected: Int,
    /** Why the pass ended early, or `null` when every item was either sent or rejected. */
    val stoppedBy: BatchResult?,
    /** The pass stopped because the server refused more single items than one run may drop ([BatchSender.MAX_REJECTED_PER_RUN]). */
    val refusingEverything: Boolean = false
)

/**
 * Sends items in batches and keeps a refused batch from costing more than its bad item: a
 * [BatchResult.Rejected] batch is split in half and each half retried, down to the single item that is then
 * dropped. The rest of the pass stops at the first batch that has to wait.
 *
 * **The breaker.** A bad deploy, a WAF or a reverse proxy answering 400 / 413 to everything looks exactly like a payload
 * complaint, and bisecting would then drop the whole outbox one item at a time. Real refusals are rare (`SyncLimits`
 * applies every server rule before an item is queued, and a server since 2026-10-18 drops a bad session itself), so one
 * sender — one run — drops at most [maxRejectedPerRun] items across all its passes. One more refused single means the
 * server is broken, not the data: the pass stops as [BatchResult.RetryLater] and drops **none** of the items it refused,
 * because a pass's refusals are dropped only once it ends. So a server refusing everything costs nothing, run after run.
 * The price is an outbox held behind more than [maxRejectedPerRun] genuinely bad items in one page, which waits (and, after
 * 24 h, is notified) instead of being dropped.
 */
class BatchSender<T>(
    private val maxBatch: Int,
    private val maxRejectedPerRun: Int = MAX_REJECTED_PER_RUN,
    private val send: suspend (List<T>) -> BatchResult
) {
    /** Dropped by the passes so far: the breaker counts per run, and a run uses one sender. */
    private var rejectedThisRun = 0

    suspend fun sendAll(
        items: List<T>,
        onSent: suspend (List<T>) -> Unit,
        onRejected: suspend (T) -> Unit
    ): PassResult {
        val queue = ArrayDeque(items.chunked(maxBatch))
        var counts = SyncCounts()
        val refused = mutableListOf<T>()

        suspend fun dropRefused(): Int {
            refused.forEach { onRejected(it) }
            rejectedThisRun += refused.size
            return refused.size
        }

        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            when (val result = send(batch)) {
                is BatchResult.Sent -> {
                    onSent(batch)
                    counts += result.counts
                }
                BatchResult.Rejected -> if (batch.size == 1) {
                    refused += batch.single()
                    if (rejectedThisRun + refused.size > maxRejectedPerRun) {
                        return PassResult(counts, 0, BatchResult.RetryLater, refusingEverything = true)
                    }
                } else {
                    val half = batch.size / 2
                    queue.addFirst(batch.subList(half, batch.size))
                    queue.addFirst(batch.subList(0, half))
                }
                // A wait says nothing against the refusals before it: those items were answered for one by one.
                BatchResult.RetryLater, BatchResult.AuthRequired -> return PassResult(counts, dropRefused(), result)
            }
        }
        return PassResult(counts, dropRefused(), null)
    }

    companion object {
        /** Single items one run may drop as refused before it reads the server as refusing everything. */
        const val MAX_REJECTED_PER_RUN = 5
    }
}
