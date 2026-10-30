package com.timeorganizer.usagetracker.push

/**
 * When the phone tells the server where to push it (prompt 22). **Pure**, JVM-tested.
 *
 * The server keeps one row per install and deletes one not heard from in 180 days, so the phone registers: when it has a
 * token it hasn't registered, when another account signed in, and once a day otherwise — the server's "last seen", which
 * also repairs a row the server dropped (a gone token, an erasure, a deletion scheduled and kept).
 */
object PushRegistrationPolicy {

    const val REFRESH_MS = 24 * 60 * 60 * 1000L

    /** What was last registered: the token, for which account, and when. All null before the first registration. */
    data class Registered(val token: String?, val account: String?, val atMs: Long?)

    fun due(token: String?, account: String?, registered: Registered, nowMs: Long): Boolean {
        if (token.isNullOrBlank() || account.isNullOrBlank()) return false
        if (token != registered.token || account != registered.account) return true
        val at = registered.atMs ?: return true
        // A clock moved backwards reads as due, never as "registered in the future".
        return nowMs - at >= REFRESH_MS || nowMs < at
    }
}
