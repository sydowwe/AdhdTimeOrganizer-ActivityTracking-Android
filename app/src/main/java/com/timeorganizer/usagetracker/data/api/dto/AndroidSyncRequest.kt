package com.timeorganizer.usagetracker.data.api.dto

data class AndroidSyncRequest(
    val sessions: List<AndroidSessionDto>,
    val syncedUpToUtc: String,
    val deviceId: String
)
