package com.timeorganizer.usagetracker.util

object Constants {
//    const val SERVER_ROOT = "http://10.0.2.2:8081/"
    const val SERVER_ROOT = "https://test.backend.adhdtimeorganizer.com/"
    const val API_BASE_URL = SERVER_ROOT + "api/"

    /**
     * The web app that goes with [SERVER_ROOT] (its own origin: the API is not the web app). Switched with it for a
     * production release (`https://adhdtimeorganizer.com/`). The drift nudge's "Switch plan" opens its day planner.
     */
//    const val WEB_APP_ROOT = "https://10.0.2.2:3001/"
    const val WEB_APP_ROOT = "https://test.adhdtimeorganizer.com/"

    /**
     * "Sign in with Google": the server's **web** OAuth client id (its `OAUTH2_GOOGLE_CLIENT_ID`), which Credential
     * Manager names as the ID token's audience. Public, not a secret. Goes with [SERVER_ROOT]: switch them together.
     * Blank hides the Google button. Google accepts the request only from a build whose signing key's SHA-1 is on an
     * Android OAuth client in the same Google Cloud project — the ids the server lists in `OAUTH2_GOOGLE_ANDROID_CLIENT_IDS`.
     */
    const val GOOGLE_SERVER_CLIENT_ID = ""

    /** The anonymous liveness route — outside `/api` (`Program.HealthCheckPath`). */
    const val HEALTH_URL = SERVER_ROOT + "health"

    const val SYNC_WORK_NAME = "usage_sync_periodic"
    const val SYNC_NOW_WORK_NAME = "usage_sync_now"

    /** How far back the first run reads the usage log. The OS keeps roughly a week of events. */
    const val FIRST_RUN_BACKFILL_MS = 7 * 24 * 60 * 60 * 1000L

    /** Unsent sessions older than this are given up; the outbox is otherwise unbounded in time offline. */
    const val OUTBOX_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000L

    /** Unsent sessions kept at most — far above a month of use; the oldest go first. */
    const val OUTBOX_MAX_UNSENT = 50_000

    /** The server drops sessions more than five minutes in its future; warn a little before that. */
    const val CLOCK_AHEAD_WARNING_MS = 4 * 60 * 1000L
}
