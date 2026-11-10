package com.timeorganizer.usagetracker.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.util.holdBroadcastFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A button on a notification the server pushed (prompt 22): `POST tracker/android/notifications/{id}/action/{action}`, the
 * web's button route for the phone's token (decision 22-4). Done → the notification goes. Anything else — the thing is gone
 * (409), not ours (404), offline — leaves it showing and says so, so a tap on it opens the web app instead: the service
 * worker's rule ("opens the notification on any failure"), without starting an activity from a receiver, which Android 12
 * forbids. Not exported: only our own PendingIntents reach it.
 */
class PushActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAKE) return
        val id = intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0L).takeIf { it > 0 } ?: return
        val action = intent.getStringExtra(EXTRA_ACTION)?.takeIf { it.isNotBlank() } ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val app = context.applicationContext

        // The call is held for within the broadcast's budget; a slower one goes on and answers when it is done.
        holdBroadcastFor(
            scope.launch {
                val done = try {
                    RetrofitClient.apiService.takeNotificationAction(id, action).isSuccessful
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    if (done) PushNotifier.cancel(app, tag)
                    else Toast.makeText(app, R.string.push_action_failed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    companion object {
        const val ACTION_TAKE = "com.timeorganizer.usagetracker.push.TAKE_ACTION"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_TAG = "tag"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
