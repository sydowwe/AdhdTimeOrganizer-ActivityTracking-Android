package com.timeorganizer.usagetracker.data.api.dto

data class AndroidSyncResponse(
    val accepted: Int,
    val duplicatesSkipped: Int,
    /** Sessions the server refused one at a time. Absent (0) from servers before 2026-10-18. */
    val dropped: Int,
    /**
     * Sessions of a package an active ignore rule covers, not stored. Absent (0) from servers before prompt 26a. The
     * phone drops its ignored packages before the outbox, so this counts only what it did not know about yet.
     */
    val ignored: Int,
    val syncedUpToUtc: String?
)
