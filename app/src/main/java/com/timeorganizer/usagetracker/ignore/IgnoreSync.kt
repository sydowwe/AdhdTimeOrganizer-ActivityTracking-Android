package com.timeorganizer.usagetracker.ignore

import android.content.Context
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageRequest
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageResponse
import com.timeorganizer.usagetracker.data.local.outbox.OutboxDatabase
import com.timeorganizer.usagetracker.sync.SyncLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/** How an ignore or stop-ignoring from the screen went. [response] is the ignore's answer, when it was confirmed. */
data class IgnoreResult(val outcome: IgnoreCallOutcome, val response: IgnorePackageResponse? = null)

/**
 * The phone's half of the server's ignore routes. The sync run calls [syncWithServer] before it reads the usage log;
 * the "Ignored apps" screen and the evening review call [ignore] / [stopIgnoring], which act on the phone first and
 * then try the server once — a call that does not get through stays pending for the next run. Never logs a package or
 * a label (*Privacy and logging*).
 */
class IgnoreSync(context: Context) {

    private val store = IgnoreStore.get(context)
    private val outbox = OutboxDatabase.get(context).outboxDao()

    /** Refresh the rules, then send the pending ops. Signed in only; never throws into the sync. */
    suspend fun syncWithServer(nowMs: Long) = withContext(Dispatchers.IO) {
        refreshRules()
        for (op in store.state().sendable(nowMs)) {
            if (send(op).outcome == IgnoreCallOutcome.AUTH_REQUIRED) break
        }
    }

    /** `GET rules` with the stored `ETag`; a `304` or a failure keeps the cache. */
    suspend fun refreshRules() = withContext(Dispatchers.IO) {
        val response = call { RetrofitClient.apiService.rules(store.etag) } ?: return@withContext
        if (!response.isSuccessful) return@withContext
        val rules = response.body() ?: return@withContext
        store.fetched(rules, response.headers()["ETag"])
    }

    /**
     * From now on [packageName] is not recorded: the op is stored, the package's unsent sessions leave the outbox, and
     * the server is asked to ignore it (and delete what it stored).
     */
    suspend fun ignore(packageName: String, appLabel: String?, nowMs: Long = System.currentTimeMillis()): IgnoreResult =
        withContext(Dispatchers.IO) {
            val label = appLabel?.takeIf { it.isNotBlank() }?.let { SyncLimits.fitLabel(it, packageName) }
            val op = PendingIgnoreOp(packageName, IgnoreOpKind.IGNORE, nowMs, label)
            store.update { it.withOp(op) }
            outbox.deleteUnsentOf(listOf(packageName))
            send(op)
        }

    suspend fun stopIgnoring(packageName: String, nowMs: Long = System.currentTimeMillis()): IgnoreResult =
        withContext(Dispatchers.IO) {
            val op = PendingIgnoreOp(packageName, IgnoreOpKind.UNIGNORE, nowMs)
            store.update { it.withOp(op) }
            send(op)
        }

    private suspend fun send(op: PendingIgnoreOp): IgnoreResult {
        val response: Response<*>? = when (op.kind) {
            IgnoreOpKind.IGNORE -> call { RetrofitClient.apiService.ignorePackage(op.packageName, IgnorePackageRequest(op.appLabel)) }
            IgnoreOpKind.UNIGNORE -> call { RetrofitClient.apiService.stopIgnoringPackage(op.packageName) }
        }
        val outcome = IgnoreCallOutcome.of(response?.code())
        when (outcome) {
            IgnoreCallOutcome.CONFIRMED -> store.update { it.confirmed(op) }
            IgnoreCallOutcome.REJECTED -> store.update { it.rejected(op) }
            IgnoreCallOutcome.AUTH_REQUIRED, IgnoreCallOutcome.KEEP -> Unit
        }
        return IgnoreResult(outcome, response?.body() as? IgnorePackageResponse)
    }

    /** `null` when nothing was learned: offline, a timeout, an unreadable body. */
    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
