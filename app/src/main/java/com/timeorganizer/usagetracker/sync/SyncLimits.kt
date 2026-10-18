package com.timeorganizer.usagetracker.sync

import com.timeorganizer.usagetracker.usagestats.RawSession

/**
 * The server's ingest rules for one android session, applied before a session is queued.
 *
 * Mirrors `AndroidSyncValidator` / `AndroidSyncEndpoint` / `TrackingIngestLimits` in the Tracking slice of
 * `../AdhdTimeOrganizer`. The current server drops or repairs a bad session on its own, but servers from before
 * 2026-10-18 answer the same session with a 400 for the whole sync — and a phone may meet either. So nothing
 * leaves the phone that the old validator would refuse: a package of 1–255 characters, a label of 1–255, a
 * duration of 1 s to 24 h. When those server files change, change this with them.
 */
object SyncLimits {

    /** Sessions per request. The server accepts 1000; smaller requests lose less to a dropped connection. */
    const val MAX_SESSIONS_PER_REQUEST = 200

    const val MAX_PACKAGE_NAME_LENGTH = 255
    const val MAX_APP_LABEL_LENGTH = 255

    /** `TrackingIngestLimits.MaxAndroidDeviceIdLength`. */
    const val MAX_DEVICE_ID_LENGTH = 100

    /** `TrackingIngestLimits.MaxAndroidSessionSeconds` — longer sessions are sent as consecutive pieces. */
    const val MAX_SESSION_MS = 24 * 60 * 60 * 1000L

    /** Client policy, not a server rule: a glance under five seconds is noise in every dashboard. */
    const val MIN_SESSION_MS = 5_000L

    /**
     * What will be queued for [session]: nothing for a session the server could never store or that is too
     * short to matter, one piece normally, several consecutive pieces for one longer than a day.
     */
    fun prepare(session: RawSession, label: String?): List<OutgoingSession> {
        val packageName = session.packageName
        if (packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH) return emptyList()
        if (session.endMs - session.startMs < MIN_SESSION_MS) return emptyList()

        val appLabel = fitLabel(label, packageName)
        val pieces = mutableListOf<OutgoingSession>()
        var start = session.startMs
        while (start < session.endMs) {
            val end = minOf(start + MAX_SESSION_MS, session.endMs)
            // A sub-second tail would state a zero duration, which the old server refuses.
            if (end - start >= 1_000L) pieces.add(OutgoingSession(packageName, appLabel, start, end))
            start = end
        }
        return pieces
    }

    /**
     * A blank label (no label resource, or the app is gone) reads as the package name; an over-wide one is cut
     * to the column, never between the two halves of a surrogate pair.
     */
    fun fitLabel(label: String?, packageName: String): String {
        if (label.isNullOrBlank()) return packageName
        if (label.length <= MAX_APP_LABEL_LENGTH) return label
        val cut = if (Character.isHighSurrogate(label[MAX_APP_LABEL_LENGTH - 1])) MAX_APP_LABEL_LENGTH - 1 else MAX_APP_LABEL_LENGTH
        return label.substring(0, cut)
    }
}

data class OutgoingSession(
    val packageName: String,
    val appLabel: String,
    val startMs: Long,
    val endMs: Long
) {
    /** Floored, exactly as the server computes the span from the two instants it is sent. */
    val durationSeconds: Long get() = (endMs - startMs) / 1000L
}
