package com.timeorganizer.usagetracker.data.api.dto

/**
 * `GET android-release/latest` (prompt 21): the newest production build. [downloadPath] is relative to the server root
 * (`/api/android-release/10/apk`). Nullable throughout: Gson fills what the server sent, `UpdatePolicy` judges the rest.
 */
data class LatestReleaseResponse(
    val versionCode: Int?,
    val versionName: String?,
    val sha256: String?,
    val sizeBytes: Long?,
    val downloadPath: String?,
)
