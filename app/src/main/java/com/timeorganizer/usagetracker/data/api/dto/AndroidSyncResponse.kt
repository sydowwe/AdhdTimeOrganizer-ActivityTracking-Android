package com.timeorganizer.usagetracker.data.api.dto

data class AndroidSyncResponse(
    val accepted: Int,
    val duplicatesSkipped: Int,
    /** Sessions the server refused one at a time. Absent (0) from servers before 2026-10-18. */
    val dropped: Int,
    val syncedUpToUtc: String?
)
