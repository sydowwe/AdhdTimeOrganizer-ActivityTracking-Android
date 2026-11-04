package com.timeorganizer.usagetracker.today

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.timeorganizer.usagetracker.TimeOrganizerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The widget's entry points: the system's "draw it" (a new widget, after a boot) and [TodayWidget]'s own alarm. Not exported:
 * the system reaches it anyway, and only the app's own PendingIntent sends [ACTION_LOOK_AGAIN].
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LOOK_AGAIN) drawInBackground(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = drawInBackground(context)

    /** The first widget: no block was kept while there was none, so a sync hears `/now` rather than waiting for the next. */
    override fun onEnabled(context: Context) = TimeOrganizerApp.enqueueSyncNow(context)

    /** The last one gone: its kept blocks and its alarm go too. */
    override fun onDisabled(context: Context) = TodayWidget.kick(context)

    private fun drawInBackground(context: Context) {
        val app = context.applicationContext
        // The usage log is read off the main thread; keep the process until the widget is drawn.
        val pending = goAsync()
        scope.launch {
            try {
                TodayWidget.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOOK_AGAIN = "com.timeorganizer.usagetracker.today.LOOK_AGAIN"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
