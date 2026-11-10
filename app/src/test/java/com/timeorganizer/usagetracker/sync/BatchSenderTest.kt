package com.timeorganizer.usagetracker.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSenderTest {

    private class Recorder {
        val requests = mutableListOf<List<Int>>()
        val sent = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
    }

    private fun sender(recorder: Recorder, maxBatch: Int, answer: (List<Int>) -> BatchResult) =
        BatchSender(maxBatch) { batch: List<Int> ->
            recorder.requests += batch
            answer(batch)
        }

    private fun BatchSender<Int>.run(items: List<Int>, recorder: Recorder) = runBlocking {
        sendAll(items, onSent = { recorder.sent += it }, onRejected = { recorder.rejected += it })
    }

    @Test
    fun everythingIsSentInBatchesAndCounted() {
        val recorder = Recorder()
        val result = sender(recorder, 3) { BatchResult.Sent(SyncCounts(accepted = it.size)) }.run((1..7).toList(), recorder)

        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), recorder.requests)
        assertEquals((1..7).toList(), recorder.sent)
        assertEquals(SyncCounts(accepted = 7), result.counts)
        assertNull(result.stoppedBy)
    }

    @Test
    fun aRefusedBatch_costsOnlyItsBadItem() {
        val recorder = Recorder()
        val result = sender(recorder, 8) { batch ->
            if (5 in batch) BatchResult.Rejected else BatchResult.Sent(SyncCounts(accepted = batch.size))
        }.run((1..8).toList(), recorder)

        assertEquals(listOf(5), recorder.rejected)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), recorder.sent.sorted())
        assertEquals(1, result.rejected)
        assertEquals(7, result.counts.accepted)
        assertNull(result.stoppedBy)
    }

    @Test
    fun aBatchThatMustWait_stopsThePass_andSendsNothingAfterIt() {
        val recorder = Recorder()
        var calls = 0
        val result = sender(recorder, 2) {
            calls++
            if (calls == 2) BatchResult.RetryLater else BatchResult.Sent(SyncCounts(accepted = it.size))
        }.run((1..6).toList(), recorder)

        assertEquals(listOf(1, 2), recorder.sent)
        assertEquals(2, recorder.requests.size)
        assertEquals(BatchResult.RetryLater, result.stoppedBy)
    }

    @Test
    fun aServerRefusingEverything_dropsNothing_andStopsAfterAFewRequests() {
        // A bad deploy or a proxy answering 400 to everything: bisecting used to drop the whole outbox one item at a time.
        val recorder = Recorder()
        val result = sender(recorder, 200) { BatchResult.Rejected }.run((1..1_000).toList(), recorder)

        assertEquals(emptyList<Int>(), recorder.rejected)
        assertEquals(emptyList<Int>(), recorder.sent)
        assertEquals(0, result.rejected)
        assertEquals(BatchResult.RetryLater, result.stoppedBy)
        assertTrue(result.refusingEverything)
        assertTrue("${recorder.requests.size} requests", recorder.requests.size <= 20)
    }

    @Test
    fun upToTheLimit_badItemsAreDropped_asBefore() {
        val bad = setOf(3, 9, 14, 20, 27)
        val recorder = Recorder()
        val result = sender(recorder, 8) { batch ->
            if (batch.any { it in bad }) BatchResult.Rejected else BatchResult.Sent(SyncCounts(accepted = batch.size))
        }.run((1..30).toList(), recorder)

        assertEquals(BatchSender.MAX_REJECTED_PER_RUN, bad.size)
        assertEquals(bad, recorder.rejected.toSet())
        assertEquals((1..30).toList() - bad, recorder.sent.sorted())
        assertNull(result.stoppedBy)
        assertFalse(result.refusingEverything)
    }

    @Test
    fun theLimitIsPerRun_acrossPasses_andATrippedPassDropsNoneOfItsOwn() {
        val recorder = Recorder()
        val sender = sender(recorder, 4) { batch ->
            if (batch.any { it % 10 == 0 }) BatchResult.Rejected else BatchResult.Sent(SyncCounts(accepted = batch.size))
        }

        val first = sender.run((1..30).toList(), recorder) // 10, 20, 30: within the limit
        assertEquals(listOf(10, 20, 30), recorder.rejected)
        assertNull(first.stoppedBy)

        val second = sender.run((31..60).toList(), recorder) // 40, 50, 60: 3 + 3 > 5
        assertEquals("none of the second pass's refusals dropped", listOf(10, 20, 30), recorder.rejected)
        assertEquals(BatchResult.RetryLater, second.stoppedBy)
        assertTrue(second.refusingEverything)
    }

    @Test
    fun aWaitAfterARefusal_stillDropsTheRefusedItem() {
        val recorder = Recorder()
        val result = sender(recorder, 2) { batch ->
            when {
                3 in batch -> BatchResult.Rejected
                5 in batch -> BatchResult.RetryLater
                else -> BatchResult.Sent(SyncCounts(accepted = batch.size))
            }
        }.run((1..6).toList(), recorder)

        assertEquals(listOf(3), recorder.rejected)
        assertEquals(listOf(1, 2, 4), recorder.sent)
        assertEquals(1, result.rejected)
        assertEquals(BatchResult.RetryLater, result.stoppedBy)
        assertFalse(result.refusingEverything)
    }

    @Test
    fun onlyAPayloadComplaintIsARejection() {
        assertNull(ResponseClassifier.classify(200))
        assertNull(ResponseClassifier.classify(204))
        assertEquals(BatchResult.AuthRequired, ResponseClassifier.classify(401))
        for (code in listOf(400, 413, 422)) assertEquals("$code", BatchResult.Rejected, ResponseClassifier.classify(code))
        // A 404 from a proxy or a moved route says nothing about the sessions; dropping on it would empty the outbox.
        for (code in listOf(403, 404, 408, 409, 425, 429, 500, 502, 503)) {
            assertEquals("$code", BatchResult.RetryLater, ResponseClassifier.classify(code))
        }
    }
}
