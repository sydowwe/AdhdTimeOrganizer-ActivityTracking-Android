package com.timeorganizer.usagetracker.data.api.dto

data class AndroidSyncResponse(
    val accepted: Int,
    val duplicatesSkipped: Int,
    val syncedUpToUtc: String
)
