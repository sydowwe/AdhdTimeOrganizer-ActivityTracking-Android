package com.timeorganizer.usagetracker.update

import java.net.URI

/** A production build newer than this one, as the status line and the notification show it. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    /** Absolute, on the server this build talks to; the browser downloads and installs it. */
    val downloadUrl: String,
)

/** What the update check remembers between runs. */
data class UpdateState(
    /** When the server last answered (ms); 0 = never. A failed call leaves it, so the next sync asks again. */
    val checkedAtMs: Long = 0L,
    /** The newest build the server named, if it was newer than the one installed then. */
    val available: AvailableUpdate? = null,
    /** The versionCode a notification was posted for: one notification per new version, never one per check. */
    val notifiedCode: Int? = null,
)

/** The server's `GET android-release/latest`, reduced to what the check reads; null fields are a malformed answer. */
data class LatestRelease(
    val versionCode: Int?,
    val versionName: String?,
    val downloadPath: String?,
)

/**
 * **Pure.** The phone's update check (android prompt 21): once a day, after a sync, the release build asks its server
 * for the newest production build; a newer one shows as a status-screen line and **one** notification per version, both
 * opening the download in the browser — so the app needs no `REQUEST_INSTALL_PACKAGES`, and Android's installer does the
 * signature check.
 */
object UpdatePolicy {

    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Due a day after the last answer — or at once when the clock has moved back past it. */
    fun isDue(state: UpdateState, nowMs: Long): Boolean =
        state.checkedAtMs == 0L || nowMs - state.checkedAtMs >= CHECK_INTERVAL_MS || nowMs < state.checkedAtMs

    /**
     * The state after the server answered. [latest] null is "nothing published" (204, or a server without the route).
     * A newer build is kept only when its download is on [serverRoot]'s own host: the answer must not be able to send the
     * browser anywhere else. [notifiedCode][UpdateState.notifiedCode] survives, so the same version is not announced twice.
     */
    fun afterCheck(state: UpdateState, latest: LatestRelease?, installedCode: Int, serverRoot: String, nowMs: Long): UpdateState {
        val code = latest?.versionCode
        val name = latest?.versionName?.takeIf { it.isNotBlank() }
        val url = latest?.downloadPath?.let { downloadUrl(serverRoot, it) }
        val available = if (code != null && name != null && url != null && code > installedCode) {
            AvailableUpdate(code, name, url)
        } else {
            null
        }
        return state.copy(checkedAtMs = nowMs, available = available)
    }

    /** The update to show now: none once this build is that version or newer (installed since the check). */
    fun current(state: UpdateState, installedCode: Int): AvailableUpdate? =
        state.available?.takeIf { it.versionCode > installedCode }

    fun shouldNotify(state: UpdateState, installedCode: Int): Boolean {
        val update = current(state, installedCode) ?: return false
        return state.notifiedCode != update.versionCode
    }

    fun notified(state: UpdateState, update: AvailableUpdate): UpdateState = state.copy(notifiedCode = update.versionCode)

    /** [path] resolved against [serverRoot], or null when it isn't on that same scheme and host. */
    fun downloadUrl(serverRoot: String, path: String): String? {
        val root = runCatching { URI(serverRoot) }.getOrNull() ?: return null
        val resolved = runCatching { root.resolve(path.trim()) }.getOrNull() ?: return null
        if (resolved.scheme != root.scheme || resolved.host == null || resolved.host != root.host || resolved.port != root.port) {
            return null
        }
        return resolved.toString()
    }
}
