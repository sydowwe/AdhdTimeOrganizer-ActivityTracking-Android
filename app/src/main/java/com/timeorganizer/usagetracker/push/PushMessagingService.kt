package com.timeorganizer.usagetracker.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.timeorganizer.usagetracker.data.local.AuthPreferences

/**
 * FCM's entry point (prompt 22). A message is data only, so it arrives here whether the app is open or not, and the app
 * posts it itself ([PushNotifier]). **Signed out, every message is dropped**: a phone that signed out offline is still
 * registered until its next registration moves it or the server prunes it, and must not show the previous session's
 * notifications. A new token is registered by [PushRegistrar].
 */
class PushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (!AuthPreferences.get(applicationContext).isLoggedIn) return
        val parsed = PushMessage.parse(message.data) ?: return
        PushNotifier.post(applicationContext, parsed)
    }

    override fun onNewToken(token: String) {
        PushRegistrar.enqueue(applicationContext)
    }
}
