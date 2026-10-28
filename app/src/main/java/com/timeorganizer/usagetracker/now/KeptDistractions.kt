package com.timeorganizer.usagetracker.now

import android.content.Context
import com.timeorganizer.usagetracker.data.local.AuthPreferences

/**
 * `/now`'s distraction packages as the server last gave them, kept in prefs so a restart offline still knows them. Shared
 * by the nudges that act on them (the time chime, the drift nudge); [NowClient] stays in memory only. Package names are
 * personal: never logged.
 *
 * Until prompt 08 the chime kept this list in its own prefs (`time_chime`, `distractions`). The first read here takes that
 * copy over once, so an update doesn't forget the list until the next sync.
 */
object KeptDistractions {

    private const val PREFS = "now_kept"
    private const val KEY_DISTRACTIONS = "distractions"

    private const val LEGACY_PREFS = "time_chime"
    private const val LEGACY_KEY = "distractions"

    fun get(context: Context): Set<String> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // getStringSet's own set must not be changed or kept: copy it.
        prefs.getStringSet(KEY_DISTRACTIONS, null)?.let { return it.toSet() }

        val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val taken = legacy.getStringSet(LEGACY_KEY, null)?.toSet() ?: return emptySet()
        save(app, taken)
        legacy.edit().remove(LEGACY_KEY).apply()
        return taken
    }

    fun save(context: Context, packages: Set<String>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DISTRACTIONS, packages).apply()
    }

    /** Asks `/now` (or takes its answer from the last 30 s) and keeps the list. `false` when it couldn't. Never throws. */
    suspend fun refresh(context: Context): Boolean {
        val app = context.applicationContext
        if (!AuthPreferences.get(app).isLoggedIn) return false
        val facts = NowClient.get() ?: return false
        save(app, facts.distractionPackages)
        return true
    }

    /** An account switch: the list was the previous person's. */
    fun clear(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DISTRACTIONS).apply()
        app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).apply()
    }
}
