package com.timeorganizer.usagetracker.watch

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Which app is in front, within seconds, for the features that nudge. A feature asks with [watch] and lets go
 * with [stop]; [ForegroundWatchService] runs while any reason is active and reports every change to the
 * listeners. Nothing is reported while no one is watching.
 *
 * **The watcher never records time.** Sessions come only from the sync's read of the usage log; a change
 * reported here is a cue for a nudge, nothing more.
 */
object ForegroundWatcher {

    fun interface Listener {
        /** Called on a background thread, one change at a time, in order. */
        fun onForegroundChange(change: ForegroundChange)
    }

    private const val TAG = "ForegroundWatcher"
    private const val PREFS = "foreground_watch"

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var reasons: MutableStateFlow<WatchReasons>? = null

    /** Set by the service while it runs; a running service follows [reasonsFlow] instead of being started again. */
    @Volatile
    internal var running = false

    /** The apps in front at the last read; empty while not watching. */
    @Volatile
    var inFront: Set<String> = emptySet()
        internal set

    /** Register from `TimeOrganizerApp.onCreate`, so a process the system restarted has its listeners too. */
    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Watches for [reason] until [untilMs], or moves that reason's end. `false` when nothing is watched: the end
     * has passed, or Android refused to start the service — since Android 12 it starts from the background only
     * for an app excluded from battery optimization, which the settings screen asks for.
     */
    @Synchronized
    fun watch(context: Context, reason: String, untilMs: Long): Boolean {
        val app = context.applicationContext
        if (untilMs <= System.currentTimeMillis()) {
            update(app) { it.stop(reason) }
            return false
        }
        update(app) { it.watch(reason, untilMs) }
        if (running) return true

        return try {
            ContextCompat.startForegroundService(app, Intent(app, ForegroundWatchService::class.java))
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one.
            update(app) { it.stop(reason) }
            false
        }
    }

    /** The service stops itself once no reason is left. */
    @Synchronized
    fun stop(context: Context, reason: String) {
        update(context.applicationContext) { it.stop(reason) }
    }

    internal fun reasonsFlow(context: Context): StateFlow<WatchReasons> = flow(context.applicationContext)

    internal fun dispatch(changes: List<ForegroundChange>) {
        for (change in changes) {
            for (listener in listeners) {
                try {
                    listener.onForegroundChange(change)
                } catch (e: Exception) {
                    // One feature's bug must not end the watch for the others. No package name in the log.
                    Log.w(TAG, "A foreground listener failed: ${e.javaClass.name}")
                }
            }
        }
    }

    private fun update(app: Context, change: (WatchReasons) -> WatchReasons) {
        val flow = flow(app)
        val next = change(flow.value).active(System.currentTimeMillis())
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            clear()
            next.untilMsByReason.forEach { (reason, untilMs) -> putLong(reason, untilMs) }
        }.apply()
        flow.value = next
    }

    private fun flow(app: Context): MutableStateFlow<WatchReasons> =
        reasons ?: synchronized(this) {
            reasons ?: MutableStateFlow(load(app)).also { reasons = it }
        }

    private fun load(app: Context): WatchReasons = WatchReasons(
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (reason, untilMs) -> (untilMs as? Long)?.let { reason to it } }
            .toMap()
    )
}
