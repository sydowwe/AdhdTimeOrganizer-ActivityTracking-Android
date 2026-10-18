package com.timeorganizer.usagetracker

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.worker.UsageSyncWorker
import java.util.concurrent.TimeUnit

class TimeOrganizerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RetrofitClient.init(this)
        enqueuePeriodicSync(this)
    }

    companion object {
        /**
         * Every 15 minutes, with no network or battery constraint: recording into the outbox needs neither, and
         * an offline run just leaves its sessions queued. WorkManager reschedules itself after a reboot or an
         * update; a force-stop cancels it until the app is opened again, which this call then restores.
         * `UPDATE` so an install that scheduled the old, network-constrained request gets this one.
         */
        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** "Sync Now": one at a time — a second tap while one is queued or running does nothing. */
        fun enqueueSyncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.SYNC_NOW_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UsageSyncWorker>().build()
            )
        }
    }
}
