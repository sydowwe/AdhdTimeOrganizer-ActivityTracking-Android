package com.timeorganizer.usagetracker.now

import com.timeorganizer.usagetracker.data.api.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one reader of `GET tracker/android/now`, shared by every nudge. The server lets an answer be reused for 30 s
 * (`max-age=30`) and answers an unchanged one with a `304` against its `ETag`, so asking once a minute costs a few
 * hundred bytes. Kept in memory only: an answer is worth seconds, not a restart.
 */
object NowClient {

    /** The server's `max-age`. */
    private const val REUSE_MS = 30_000L

    private val mutex = Mutex()
    private var facts: NowFacts? = null
    private var etag: String? = null
    private var fetchedAtMs = 0L

    /** Fresh facts, or `null` when the server could not be asked (offline, signed out, an error). Never throws. */
    suspend fun get(nowMs: Long = System.currentTimeMillis()): NowFacts? = mutex.withLock {
        facts?.takeIf { nowMs - fetchedAtMs in 0 until REUSE_MS }?.let { return it }

        val response = try {
            RetrofitClient.apiService.now(etag.takeIf { facts != null })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        when {
            response.code() == 304 && facts != null -> fetchedAtMs = nowMs
            response.isSuccessful && response.body() != null -> {
                facts = NowFacts.from(response.body()!!)
                etag = response.headers()["ETag"]
                fetchedAtMs = nowMs
            }
            else -> return null
        }
        facts
    }

    /** An account switch or sign-out: the last answer was someone else's day. */
    suspend fun clear() = mutex.withLock {
        facts = null
        etag = null
        fetchedAtMs = 0L
    }
}
