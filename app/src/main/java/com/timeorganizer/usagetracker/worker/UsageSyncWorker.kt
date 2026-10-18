package com.timeorganizer.usagetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.data.repository.SyncOutcome
import com.timeorganizer.usagetracker.data.repository.UsageRepository
import com.timeorganizer.usagetracker.util.PermissionHelper

class UsageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val prefs = SyncPreferences(context)

    override suspend fun doWork(): Result {
        if (!PermissionHelper.hasUsageStatsPermission(applicationContext)) {
            prefs.lastSyncResult = SyncPreferences.RESULT_FAILED
            prefs.lastSyncTimeMillis = System.currentTimeMillis()
            return Result.failure()
        }

        return try {
            val repo = UsageRepository(applicationContext)
            when (val outcome = repo.syncNow()) {
                is SyncOutcome.NoData, is SyncOutcome.Success -> {
                    prefs.lastSyncResult = SyncPreferences.RESULT_SUCCESS
                    prefs.lastSyncTimeMillis = System.currentTimeMillis()
                    Result.success()
                }
                is SyncOutcome.ClientError -> {
                    // Bad request — don't retry, something is wrong with data or auth
                    prefs.lastSyncResult = SyncPreferences.RESULT_FAILED
                    prefs.lastSyncTimeMillis = System.currentTimeMillis()
                    Result.failure()
                }
                is SyncOutcome.ServerError -> {
                    // Transient server/network issue — let WorkManager retry with backoff
                    prefs.lastSyncResult = SyncPreferences.RESULT_FAILED
                    prefs.lastSyncTimeMillis = System.currentTimeMillis()
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            prefs.lastSyncResult = SyncPreferences.RESULT_FAILED
            prefs.lastSyncTimeMillis = System.currentTimeMillis()
            Result.retry()
        }
    }
}
