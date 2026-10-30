package com.timeorganizer.usagetracker.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.timeorganizer.usagetracker.auth.JwtSubject
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.PushDeviceRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tells the server where to push this phone (prompt 22): `PUT tracker/android/push-device` with the device id the sync uses
 * and Firebase's token — after a sign-in, when Firebase rotates the token, and once a day (`PushRegistrationPolicy`). Always
 * through [PushRegisterWorker], so it waits for a network and survives the process. Signs out with [unregister].
 *
 * The token is a credential of sorts (whoever holds it can push here): never logged.
 */
object PushRegistrar {

    private const val WORK_NAME = "push_register"
    private const val PREFS = "push_registration"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_AT = "at_ms"

    /**
     * Asks for a registration. `REPLACE`: a new token or account must not wait behind an older request. Cheap to call often —
     * the worker registers only when the policy says it is due.
     */
    fun enqueue(context: Context) {
        if (!PushSetup.isConfigured) return
        val request = OneTimeWorkRequestBuilder<PushRegisterWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** True when there is nothing to do or it was done; false when it should be tried again later. */
    suspend fun registerIfDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!PushSetup.isConfigured) return true
        val accessToken = AuthPreferences.get(context).token ?: return true
        val account = JwtSubject.of(accessToken) ?: return true
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val registered = PushRegistrationPolicy.Registered(
            token = prefs.getString(KEY_TOKEN, null),
            account = prefs.getString(KEY_ACCOUNT, null),
            atMs = prefs.getLong(KEY_AT, -1L).takeIf { it >= 0 },
        )
        if (!PushRegistrationPolicy.due(token, account, registered, nowMs)) return true

        val response = try {
            RetrofitClient.apiService.registerPushDevice(PushDeviceRequest(SyncPreferences(context).deviceId, token))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (!response.isSuccessful) return response.code() in 400..499 && response.code() != 401 && response.code() != 429

        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_ACCOUNT, account).putLong(KEY_AT, nowMs).apply()
        return true
    }

    /**
     * Sign-out, before the tokens are dropped: the server forgets this phone, and Firebase's token is deleted, so even a
     * call that never reached the server leaves a token FCM calls `UNREGISTERED` — the server prunes it on its next send.
     * Best effort and never throws: signing out must not wait on the network. [accessToken] is the one being signed out.
     */
    suspend fun unregister(context: Context, accessToken: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        if (!PushSetup.isConfigured) return
        try {
            RetrofitClient.authApiService.unregisterPushDevice("Bearer $accessToken", SyncPreferences(context).deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The token deleted below is what makes it final.
        }
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Firebase rotates it on its own in the end; the phone drops pushes while signed out meanwhile.
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }
}

/** [PushRegistrar.registerIfDue] once a network is there; tried again later when it couldn't. */
class PushRegisterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (PushRegistrar.registerIfDue(applicationContext)) Result.success() else Result.retry()
}
