package com.timeorganizer.usagetracker.today

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.timeorganizer.usagetracker.MainActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.now.NowClient
import com.timeorganizer.usagetracker.pause.PauseNotifier
import com.timeorganizer.usagetracker.pause.PauseRange
import com.timeorganizer.usagetracker.pause.Pauses
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.sync.SyncLimits
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Date

/**
 * Draws the "Phone today" widget (prompt 19): [TodaySummary] from the usage log, the block [WidgetPlan] keeps, or the pause
 * going now. A tap opens the status screen.
 *
 * **When it draws.** After every sync run, which alone asks `/now` ([afterSync]); on a pause starting or ending; and at the
 * next moment the text would be wrong (a block's start or end, a pause's end, midnight), on one inexact alarm. The alarm is
 * `RTC`, not a wake-up: it comes when the phone is next awake, which is when the widget can be seen. So the total can be up to
 * a sync old, but a block or a day never outlives its end.
 *
 * With no widget on the home screen it does nothing at all: no read, no `/now`, no alarm. It never throws into its callers and
 * logs nothing: package and activity names are personal.
 */
object TodayWidget {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** After a sync run: the worker keeps the process for it, so this is the one place that asks `/now`. */
    suspend fun afterSync(context: Context) = draw(context.applicationContext, ask = true)

    /** From what is kept: no network, so a receiver's short life is enough. */
    suspend fun refresh(context: Context) = draw(context.applicationContext, ask = false)

    /** [refresh] off the caller's thread: a sign-out, the last widget going. */
    fun kick(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    /** An account switch: the blocks were the previous person's plan. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock { TodayPreferences(app).blocks = emptyList() }
        refresh(app)
    }

    private suspend fun draw(app: Context, ask: Boolean) {
        try {
            withContext(Dispatchers.IO) { mutex.withLock { update(app, ask) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next sync draws it again.
        }
    }

    private suspend fun update(app: Context, ask: Boolean) {
        val prefs = TodayPreferences(app)
        val manager = AppWidgetManager.getInstance(app) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) {
            prefs.blocks = emptyList()
            lookAgainAt(app, null)
            return
        }

        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // Signed out, a plan is nobody's. Offline, the last one heard stands: its blocks end by themselves.
        if (!AuthPreferences.get(app).isLoggedIn) prefs.blocks = emptyList()
        else if (ask) NowClient.get(nowMs)?.let { prefs.blocks = WidgetPlan.keep(it) }

        val blocks = prefs.blocks
        val pause = Pauses.active(app, nowMs)
        manager.updateAppWidget(ids, views(app, summary(app, nowMs, zone), pause, WidgetPlan.current(blocks, nowMs)))
        lookAgainAt(app, WidgetPlan.nextRefreshMs(blocks, pause, TodaySummary.nextDayStartMs(nowMs, zone), nowMs))
    }

    /** `null` with usage access off: nothing can be read, and "0 m" would be a lie. */
    private suspend fun summary(app: Context, nowMs: Long, zone: ZoneId): TodaySummary? {
        if (!PermissionHelper.hasUsageStatsPermission(app)) return null
        return try {
            val fromMs = TodaySummary.readFromMs(nowMs, zone)
            TodaySummary.from(
                UsageStatsCollector(app).events(fromMs, nowMs), nowMs, zone,
                InstalledApps(app).homePackages, IgnoreStore.get(app).state().ignored(nowMs), Pauses.over(app, fromMs, nowMs)
            )
        } catch (_: SecurityException) {
            null
        }
    }

    private fun views(app: Context, summary: TodaySummary?, pause: PauseRange?, block: KeptBlock?): RemoteViews {
        val views = RemoteViews(app.packageName, R.layout.widget_today)
        views.setOnClickPendingIntent(R.id.widgetRoot, openApp(app))
        views.setTextViewText(
            R.id.widgetTotal,
            summary?.let { ReviewText.duration(it.totalMs / 1000) } ?: app.getString(R.string.widget_total_unknown)
        )

        // The duration first: a long app name is what gets cut.
        val installedApps = InstalledApps(app)
        APP_ROWS.forEachIndexed { index, row ->
            val text = summary?.top?.getOrNull(index)?.let {
                val label = SyncLimits.fitLabel(installedApps.label(it.packageName), it.packageName)
                app.getString(R.string.widget_app_row, ReviewText.duration(it.ms / 1000), label)
            }
            views.show(row, text)
        }

        views.show(
            R.id.widgetLine,
            when {
                summary == null -> app.getString(R.string.widget_no_usage_access)
                pause != null -> app.getString(R.string.widget_paused, PauseNotifier.until(app, pause))
                block != null -> app.getString(
                    R.string.widget_block_now, block.name, DateFormat.getTimeFormat(app).format(Date(block.endMs))
                )
                else -> null
            }
        )
        return views
    }

    private fun RemoteViews.show(viewId: Int, text: String?) {
        setViewVisibility(viewId, if (text == null) View.GONE else View.VISIBLE)
        setTextViewText(viewId, text.orEmpty())
    }

    private fun openApp(app: Context): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun lookAgainAt(app: Context, atMs: Long?) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val lookAgain = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, TodayWidgetProvider::class.java).setAction(TodayWidgetProvider.ACTION_LOOK_AGAIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (atMs == null) alarms.cancel(lookAgain) else alarms.setAndAllowWhileIdle(AlarmManager.RTC, atMs, lookAgain)
    }

    private val APP_ROWS = listOf(R.id.widgetApp1, R.id.widgetApp2, R.id.widgetApp3)
}
