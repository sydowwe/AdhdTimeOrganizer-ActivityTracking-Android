package com.timeorganizer.usagetracker.data.api.dto

/** `POST tracker/android/capture`: one thought. User-authored: never log it. */
data class CaptureRequest(
    val text: String,
    /** A link shared with the text; `null` is left out by Gson. */
    val url: String? = null
)

/** What the server did with it. Every [outcome] means the thought is on the capture list. */
data class CaptureResponse(
    /** `added`, `alreadyOpen` (a retry finds it there) or `reopened`. */
    val outcome: String?,
    val todoListItemId: Long,
    val todoListId: Long
)
