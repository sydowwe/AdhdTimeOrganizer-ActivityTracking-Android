package com.timeorganizer.usagetracker.data.api

import java.net.URI

/**
 * **Pure.** Which build this is, as the server and the status screen read it (prompt 25). The values are
 * `BuildConfig.VERSION_NAME` / `VERSION_CODE` and the build type's server root, passed in.
 */
object ClientVersion {

    /**
     * The server's `TrackerClientVersion.HeaderName`. Sent on every request; the server keeps the last one per source
     * on `TrackerSourceStatus` (throttled like its `LastSeenAt`), so a silent phone can be told "out of date".
     */
    const val HEADER = "X-Client-Version"

    /** `android/1.4.0 (7)`. The server reads the version between the `/` and the first space. */
    fun header(versionName: String, versionCode: Int): String = "android/$versionName ($versionCode)"

    /**
     * The server's host for the status screen — `test.backend.adhdtimeorganizer.com`, or `10.0.2.2:8081` with the
     * port it names. A root that isn't a URL is shown as given.
     */
    fun host(serverRoot: String): String {
        val uri = runCatching { URI(serverRoot) }.getOrNull()
        val host = uri?.host ?: return serverRoot
        return if (uri.port == -1) host else "$host:${uri.port}"
    }
}
