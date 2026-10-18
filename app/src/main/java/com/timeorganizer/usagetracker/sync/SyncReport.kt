package com.timeorganizer.usagetracker.sync

enum class SyncState {
    NEVER,

    /** Sessions were sent in this run. */
    SENT,

    /** Nothing was waiting. */
    UP_TO_DATE,

    /** Offline, the server busy or throttling: sessions stay in the outbox for the next run. */
    WAITING,

    /** No usable session — sign in again. Sessions keep being recorded meanwhile. */
    SIGNED_OUT,

    /** Usage access was revoked: nothing new can be recorded. */
    NO_USAGE_ACCESS
}

data class SyncReport(
    val state: SyncState,
    val atMs: Long,
    /** When sessions last reached the server, across runs. */
    val lastSentAtMs: Long,
    val sent: SyncCounts,
    /** Sessions the server refused as malformed and that were dropped from the outbox, this run. */
    val rejected: Int,
    /** Sessions still in the outbox after this run. */
    val pending: Int,
    /** How far the phone's clock was ahead of the server's, from the last response's `Date` header. */
    val clockAheadMs: Long
)
