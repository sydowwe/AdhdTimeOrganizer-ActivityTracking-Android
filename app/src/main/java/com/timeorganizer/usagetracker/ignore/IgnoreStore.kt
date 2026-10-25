package com.timeorganizer.usagetracker.ignore

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto

/**
 * The persisted [IgnoreState]: the server's rules (body + `ETag`) and the pending ops, in SharedPreferences as JSON —
 * one small blob each, no query needed, so not Room. Every change is one synchronized read-modify-write, because the
 * card's immediate call and the sync run's both settle ops. Not backed up, like the rest; cleared on an account switch.
 */
class IgnoreStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ignored_apps", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var cached: IgnoreState? = null

    @Synchronized
    fun state(): IgnoreState = cached ?: load().also { cached = it }

    /** The `ETag` of the stored rules; `null` once they were patched locally, so the next `GET` reads them whole. */
    @get:Synchronized
    val etag: String?
        get() = prefs.getString(KEY_ETAG, null)

    @Synchronized
    fun update(transform: (IgnoreState) -> IgnoreState): IgnoreState {
        val before = state()
        val after = transform(before)
        if (after != before) save(after, etag = if (after.rules == before.rules) etag else null)
        return after
    }

    @Synchronized
    fun fetched(rules: List<AndroidTrackerRuleDto>, etag: String?) {
        save(state().fetched(rules), etag)
    }

    /** Another account signed in: the rules and the pending ops are the previous person's. */
    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
        cached = IgnoreState()
    }

    private fun save(state: IgnoreState, etag: String?) {
        prefs.edit()
            .putString(KEY_RULES, gson.toJson(state.rules))
            .putString(KEY_PENDING, gson.toJson(state.pending))
            .apply { if (etag != null) putString(KEY_ETAG, etag) else remove(KEY_ETAG) }
            .commit()
        cached = state
    }

    private fun load(): IgnoreState = try {
        IgnoreState(
            rules = prefs.getString(KEY_RULES, null)?.let { gson.fromJson<List<AndroidTrackerRuleDto>>(it, RULES) }.orEmpty(),
            pending = prefs.getString(KEY_PENDING, null)?.let { gson.fromJson<List<PendingIgnoreOp>>(it, PENDING) }.orEmpty()
        )
    } catch (_: JsonParseException) {
        // Unreadable: start over. The next sync run fetches the rules again; a lost pending op is the user's to redo.
        IgnoreState()
    }

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_ETAG = "rules_etag"
        private const val KEY_PENDING = "pending"
        private val RULES = object : TypeToken<List<AndroidTrackerRuleDto>>() {}.type
        private val PENDING = object : TypeToken<List<PendingIgnoreOp>>() {}.type

        @Volatile
        private var instance: IgnoreStore? = null

        fun get(context: Context): IgnoreStore =
            instance ?: synchronized(this) {
                instance ?: IgnoreStore(context).also { instance = it }
            }
    }
}
