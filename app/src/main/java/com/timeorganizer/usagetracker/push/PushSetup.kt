package com.timeorganizer.usagetracker.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.timeorganizer.usagetracker.BuildConfig

/**
 * Starts Firebase by hand (prompt 22), from the build's own settings (`firebase.properties` → `BuildConfig.FIREBASE_*`),
 * never from a `google-services.json`: neither is committed, and a checkout without them must still build. With any value
 * blank the app never touches Firebase — no token, no registration, no push — and everything else works as before.
 */
object PushSetup {

    val isConfigured: Boolean
        get() = listOf(
            BuildConfig.FIREBASE_APP_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID,
        ).all { it.isNotBlank() }

    /** From `TimeOrganizerApp.onCreate`, before anything asks Firebase for a token. Idempotent. */
    fun init(context: Context) {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
        )
    }
}
