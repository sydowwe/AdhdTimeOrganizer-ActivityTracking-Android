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
    val stoppedBy: BatchResult?
)

/**
 * Sends items in batches and keeps a refused batch from costing more than its bad item: a
 * [BatchResult.Rejected] batch is split in half and each half retried, down to the single item that is then
 * dropped. The rest of the pass stops at the first batch that has to wait.
 */
class BatchSender<T>(
    private val maxBatch: Int,
    private val send: suspend (List<T>) -> BatchResult
) {
    suspend fun sendAll(
        items: List<T>,
        onSent: suspend (List<T>) -> Unit,
        onRejected: suspend (T) -> Unit
    ): PassResult {
        val queue = ArrayDeque(items.chunked(maxBatch))
        var counts = SyncCounts()
        var rejected = 0

        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            when (val result = send(batch)) {
                is BatchResult.Sent -> {
                    onSent(batch)
                    counts += result.counts
                }
                BatchResult.Rejected -> if (batch.size == 1) {
                    onRejected(batch.single())
                    rejected++
                } else {
                    val half = batch.size / 2
                    queue.addFirst(batch.subList(half, batch.size))
                    queue.addFirst(batch.subList(0, half))
                }
                BatchResult.RetryLater, BatchResult.AuthRequired -> return PassResult(counts, rejected, result)
            }
        }
        return PassResult(counts, rejected, null)
    }
}
