package com.timeorganizer.usagetracker.review

import android.content.Context
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.notify.Notifications
import kotlinx.coroutines.CancellationException

/**
 * Carries out [EveningReviewDecider] after a sync run: hears the evening's moment from the server, and when it has
 * come asks the server what of today is unmapped and posts the question. Runs from the sync worker, so the sessions
 * the question is about were sent a moment ago.
 *
 * **It never throws and never blocks the sync**: every network failure just leaves the state as it was, and the next
 * run (15 minutes on) tries again inside the window. The review is stamped as done only once the server has
 * answered — even with "nothing worth asking" — so a failed call does not use the evening up.
 *
 * Nothing here logs a package name or an activity name; they say what someone does on their phone.
 */
class EveningReviewRunner(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = ReviewPreferences(appContext)
    private val authPrefs = AuthPreferences.get(appContext)

    suspend fun run(nowMs: Long = System.currentTimeMillis()) {
        if (!prefs.enabled || !authPrefs.isLoggedIn) return
        // Nothing to say it with: leave the state alone, so the review is still due the moment notifications are allowed.
        if (!Notifications.canPost(appContext, Notifications.CHANNEL_EVENING_REVIEW)) return

        if (EveningReviewDecider.needsMoment(prefs.state, nowMs)) hearMoment(nowMs)

        if (EveningReviewDecider.shouldReview(prefs.state, nowMs) && review()) {
            prefs.state = prefs.state.copy(lastReviewedAtMs = nowMs)
        }

        // The review used this evening's moment up; the next one is wanted now, not at the next stale check.
        if (EveningReviewDecider.needsMoment(prefs.state, nowMs)) hearMoment(nowMs)
    }

    /** True when the server answered, whether or not there was anything to ask. */
    private suspend fun review(): Boolean {
        val response = try {
            RetrofitClient.apiService.unmappedToday()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }

        // A server from before the route: nothing will ever come of asking again tonight.
        if (response.code() == 404) return true
        val today = response.body()
        if (!response.isSuccessful || today == null) return false

        EveningReviewNotifier.post(appContext, today)
        return true
    }

    private suspend fun hearMoment(nowMs: Long) {
        val response = try {
            RetrofitClient.apiService.eveningMoment()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val momentMs = ReviewText.instantMs(response.body()?.momentUtc)
        if (!response.isSuccessful || momentMs == null) return
        prefs.state = prefs.state.copy(momentMs = momentMs, fetchedAtMs = nowMs)
    }
}
