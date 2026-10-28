package com.timeorganizer.usagetracker.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.CaptureRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Quick capture's queue (prompt 18): a capture is stored in `capture.db` first and sent after — at once when there is a
 * network ([CaptureSendWorker], unique work with a network constraint), and again after every sync run — so it is never
 * lost offline or signed out. A row leaves only when the server has it ([CaptureRules]).
 */
object CaptureSender {

    private const val WORK_NAME = "capture_send"

    private val lock = Mutex()

    /** Stores [draft] and asks for it to be sent. Returns once the row is committed: the thought is safe from here. */
    suspend fun capture(context: Context, draft: CaptureDraft, nowMs: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            CaptureDatabase.get(context).captureDao()
                .insert(PendingCaptureEntity(text = draft.text, url = draft.url, createdMs = nowMs))
        }
        enqueue(context)
    }

    /**
     * Sends as soon as a network is there. `KEEP`: a waiting job reads the whole queue when it runs, and a running one
     * reads it again before it ends ([sendAll]), so a capture made meanwhile is not left for the next sync.
     */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CaptureSendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** After a sync run, which may have just come back online or signed in. Never throws. */
    suspend fun afterSync(context: Context) {
        try {
            sendAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The rows are kept; the next run or the job tries again.
        }
    }

    /**
     * Oldest first, one request each, until one has to wait. A refused one is kept and passed over. Signed out, nothing
     * is sent and nothing is lost. Each row is tried once per pass; rows made during the pass are picked up before it ends.
     */
    suspend fun sendAll(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!AuthPreferences.get(context).isLoggedIn) return@withContext
            val dao = CaptureDatabase.get(context).captureDao()
            val tried = mutableSetOf<Long>()
            while (true) {
                val next = dao.all().firstOrNull { it.id !in tried } ?: return@withContext
                tried += next.id
                val outcome = CaptureRules.classify(send(next))
                when (outcome) {
                    CaptureSendOutcome.SENT -> dao.delete(next.id)
                    CaptureSendOutcome.REFUSED -> dao.markRefused(next.id, true)
                    CaptureSendOutcome.AUTH_REQUIRED, CaptureSendOutcome.WAIT -> Unit
                }
                if (!CaptureRules.continues(outcome)) return@withContext
            }
        }
    }

    /** Every capture is gone: a different account signed in, and these were written for the one before (`SyncEngine.onSignedIn`). */
    suspend fun reset(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) { CaptureDatabase.get(context).captureDao().clear() }
    }

    /** The answer's status, or `null` when nothing was learned (offline, a timeout). */
    private suspend fun send(row: PendingCaptureEntity): Int? = try {
        RetrofitClient.apiService.capture(CaptureRequest(row.text, row.url)).code()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** [CaptureSender.sendAll] once a network is there. A capture that has to wait stays queued; the next sync sends it. */
class CaptureSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        CaptureSender.afterSync(applicationContext)
        return Result.success()
    }
}
