package com.timeorganizer.usagetracker.budget

import android.content.Context
import com.google.gson.Gson

/**
 * Daily limits' switch, the server's last figures, the stages settled today and the claims still to send. The figures are
 * kept so a restart offline can still count; they hold the user's own activity names, so like the break nudge's subject
 * they stay here and never reach a log. Not backed up, like the rest.
 */
class BudgetPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("daily_budgets", Context.MODE_PRIVATE)

    /** On by default: making a budget on the web is the opt-in (decision 13-4). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** The server's last answer; `null` before the first, or unreadable. */
    var facts: BudgetFacts?
        get() = prefs.getString(KEY_FACTS, null)?.let {
            try {
                gson.fromJson(it, BudgetFacts::class.java)
            } catch (_: Exception) {
                null
            }
        }
        set(value) {
            prefs.edit().putString(KEY_FACTS, value?.let(gson::toJson)).apply()
        }

    var state: BudgetState
        get() = BudgetState(prefs.getString(KEY_DAY, null), stringSet(KEY_SETTLED))
        set(value) {
            prefs.edit().putString(KEY_DAY, value.day).putStringSet(KEY_SETTLED, value.settled).apply()
        }

    /**
     * Stages said while the server couldn't be told, as `"<budgetId>|<stage>|<day>"`. Sent before the next sync, so the sync
     * that carries the crossing finds the mark set and the server's push stays quiet.
     */
    var pendingClaims: Set<String>
        get() = stringSet(KEY_PENDING)
        set(value) {
            prefs.edit().putStringSet(KEY_PENDING, value).apply()
        }

    /** An account switch: the figures, the settled stages and the claims were the previous person's. */
    fun clearAccount() {
        prefs.edit().remove(KEY_FACTS).remove(KEY_DAY).remove(KEY_SETTLED).remove(KEY_PENDING).apply()
    }

    // getStringSet's own set must not be changed or kept: copy it.
    private fun stringSet(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private companion object {
        val gson = Gson()

        const val KEY_ENABLED = "enabled"
        const val KEY_FACTS = "facts"
        const val KEY_DAY = "settled_day"
        const val KEY_SETTLED = "settled"
        const val KEY_PENDING = "pending_claims"
    }
}
