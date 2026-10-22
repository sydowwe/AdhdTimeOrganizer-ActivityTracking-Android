package com.timeorganizer.usagetracker.util

object Constants {
//    const val SERVER_ROOT = "http://10.0.2.2:8081/"
    const val SERVER_ROOT = "https://test.backend.adhdtimeorganizer.com/"
    const val API_BASE_URL = SERVER_ROOT + "api/"

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
