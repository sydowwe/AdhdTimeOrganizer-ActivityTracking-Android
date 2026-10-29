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
    /**
     * Pickups the server holds after this request, out of those sent (prompt 14). **The acknowledgement**: `null` from a
     * server before 14, which ignored them, so they stay unsent.
     */
    val pickupsStored: Int?,
    val pickupsDropped: Int?,
    /** The loops' acknowledgement, as [pickupsStored]. */
    val loopsStored: Int?,
    val loopsDropped: Int?,
    val syncedUpToUtc: String?
)
