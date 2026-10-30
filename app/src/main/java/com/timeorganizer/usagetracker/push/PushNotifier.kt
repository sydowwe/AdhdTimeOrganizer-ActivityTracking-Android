package com.timeorganizer.usagetracker.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.util.Constants

/**
 * Shows what the server pushed (prompt 22), in the server's own words and the user's language — the phone adds none. On
 * the channel its kind belongs to ([ServerChannel]), so each can be muted alone; a tap opens the web app at the producer's
 * route; the buttons are the kind's, carried out by [PushActionReceiver].
 *
 * One notification per server notification (its id in the tag), or per `tag` when the server gives one, so the next
 * boundary of a running timer replaces the last instead of stacking — the Web Push document's rule.
 */
object PushNotifier {

    private const val TAG_PREFIX = "server-"

    @SuppressLint("MissingPermission") // canPost checks it
    fun post(context: Context, message: PushMessage) {
        val channel = message.channel.id
        if (!Notifications.canPost(context, channel)) return
        val tag = tagOf(message)

        val open = PendingIntent.getActivity(
            context,
            requestCode(message, 0),
            Intent(Intent.ACTION_VIEW, Uri.parse(message.openUrl(Constants.WEB_APP_ROOT))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(if (message.channel == ServerChannel.OTHER) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (message.channel == ServerChannel.OTHER) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)

        message.actions.forEachIndexed { index, button ->
            val intent = Intent(context, PushActionReceiver::class.java)
                .setAction(PushActionReceiver.ACTION_TAKE)
                .putExtra(PushActionReceiver.EXTRA_NOTIFICATION_ID, message.id)
                .putExtra(PushActionReceiver.EXTRA_ACTION, button.action)
                .putExtra(PushActionReceiver.EXTRA_TAG, tag)
            val pending = PendingIntent.getBroadcast(
                context, requestCode(message, index + 1), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, button.title, pending)
        }

        NotificationManagerCompat.from(context).notify(tag, Notifications.ID_SERVER, builder.build())
    }

    fun cancel(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, Notifications.ID_SERVER)
    }

    /** Sign-out or an account switch: what is showing was the previous person's. */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications
            .filter { it.id == Notifications.ID_SERVER && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun tagOf(message: PushMessage): String = TAG_PREFIX + (message.tag?.let { "tag:$it" } ?: "id:${message.id}")

    /** Distinct per notification and button, so one notification's buttons never reuse another's PendingIntent. */
    private fun requestCode(message: PushMessage, slot: Int): Int = (message.id * 4 + slot).hashCode()
}
