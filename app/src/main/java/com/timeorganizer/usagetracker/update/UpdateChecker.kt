package com.timeorganizer.usagetracker.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.BuildConfig
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.util.Constants
import kotlinx.coroutines.CancellationException

/**
 * Carries out [UpdatePolicy] after a sync run: at most once a day asks the server for its newest production build, and
 * posts **one** notification per newer version. Release builds only (`BuildConfig.UPDATE_CHECK`): a debug build is
 * debug-signed and talks to the test server, so a production APK could never install over it.
 *
 * Never throws and never blocks the sync: a failed call leaves the state as it was, and the next run asks again.
 */
object UpdateChecker {

    suspend fun afterSync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            run(context.applicationContext, nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sync succeeded; the check waits for the next run.
        }
    }

    /** The update to show on the status screen, or null. Reads only what the last check stored. */
    fun available(context: Context): AvailableUpdate? =
        if (BuildConfig.UPDATE_CHECK) UpdatePolicy.current(UpdatePreferences(context).state, BuildConfig.VERSION_CODE) else null

    /** Opens the download in the browser, which hands the APK to Android's installer. */
    fun downloadIntent(update: AvailableUpdate): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun run(context: Context, nowMs: Long) {
        if (!BuildConfig.UPDATE_CHECK) return
        val prefs = UpdatePreferences(context)
        var state = prefs.state

        if (UpdatePolicy.isDue(state, nowMs)) {
            val response = RetrofitClient.authApiService.latestRelease()
            // 404: a server from before the route — the same as nothing published, asked again tomorrow.
            if (!response.isSuccessful && response.code() != 404) return
            val body = if (response.code() == 200) response.body() else null
            val latest = body?.let { LatestRelease(it.versionCode, it.versionName, it.downloadPath) }
            state = UpdatePolicy.afterCheck(state, latest, BuildConfig.VERSION_CODE, Constants.SERVER_ROOT, nowMs)
            prefs.state = state
        }

        // Without a way to show it, it isn't marked shown: it goes out once notifications are allowed.
        if (UpdatePolicy.shouldNotify(state, BuildConfig.VERSION_CODE) && Notifications.canPost(context, Notifications.CHANNEL_UPDATES)) {
            val update = UpdatePolicy.current(state, BuildConfig.VERSION_CODE) ?: return
            post(context, update)
            prefs.state = UpdatePolicy.notified(state, update)
        }
    }

    private fun post(context: Context, update: AvailableUpdate) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.notify_update_title, update.versionName))
            .setContentText(context.getString(R.string.notify_update_text, BuildConfig.VERSION_NAME))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    Notifications.ID_UPDATE,
                    downloadIntent(update),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_UPDATE, notification)
    }
}
