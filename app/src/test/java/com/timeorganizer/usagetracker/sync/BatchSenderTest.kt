package com.timeorganizer.usagetracker.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
