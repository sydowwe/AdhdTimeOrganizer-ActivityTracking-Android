package com.timeorganizer.usagetracker.data.local

import android.content.Context
import com.timeorganizer.usagetracker.auth.KeystoreCipher
import com.timeorganizer.usagetracker.auth.SignOutReason

/**
 * The token pair, encrypted with a Keystore key, plus why the last session ended.
 *
 * Writes use `commit()`, not `apply()`: the server rotates the refresh token on every refresh, so a new pair
 * lost to a process kill means replaying the old one — which the server answers by revoking every session of
 * the account. One instance per process ([get]) so the authenticator and the screens see the same tokens.
 */
class AuthPreferences private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    @Volatile
    private var tokens: Pair<String, String>? = null

    init {
        migratePlaintextTokens(context)
        tokens = load()
    }

    val token: String? get() = tokens?.first
    val refreshToken: String? get() = tokens?.second
    val isLoggedIn: Boolean get() = tokens != null

    /** Why the phone is signed out, or `null` while signed in / before the first sign-in. */
    val signOutReason: SignOutReason?
        get() = prefs.getString(KEY_SIGN_OUT_REASON, null)?.let { name ->
            SignOutReason.entries.firstOrNull { it.name == name }
        }

    /**
     * `false` when the Keystore refused to encrypt. The pair is still held in memory, because after a refresh the server
     * has already rotated and the old pair is spent; what is stored is removed with [SignOutReason.SESSION_ENDED], so the
     * next process start is signed out and says so, instead of replaying the spent token.
     */
    @Synchronized
    fun saveTokens(accessToken: String, refreshToken: String): Boolean {
        tokens = accessToken to refreshToken
        val encrypted = try {
            cipher.encrypt(accessToken + SEPARATOR + refreshToken)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_TOKENS).putString(KEY_SIGN_OUT_REASON, SignOutReason.SESSION_ENDED.name).commit()
            return false
        }
        prefs.edit()
            .putString(KEY_TOKENS, encrypted)
            .remove(KEY_SIGN_OUT_REASON)
            .commit()
        return true
    }

    /** A sign-in whose tokens couldn't be stored: forget them without a reason, as if it never happened. */
    @Synchronized
    fun discardUnsaved() {
        prefs.edit().remove(KEY_TOKENS).remove(KEY_SIGN_OUT_REASON).commit()
        tokens = null
    }

    @Synchronized
    fun clearTokens(reason: SignOutReason) {
        prefs.edit()
            .remove(KEY_TOKENS)
            .putString(KEY_SIGN_OUT_REASON, reason.name)
            .commit()
        tokens = null
    }

    /**
     * Stored tokens that can't be read back (a lost Keystore key, a restore from elsewhere) are a session that ended:
     * cleared with [SignOutReason.SESSION_ENDED], so the sync-blocked notification and the sign-in screen say so. Left
     * without a reason, the phone would read as never signed in and stop sending without a word.
     */
    private fun load(): Pair<String, String>? {
        val encrypted = prefs.getString(KEY_TOKENS, null) ?: return null
        val parts = cipher.decrypt(encrypted)?.split(SEPARATOR)
        if (parts != null && parts.size == 2) return parts[0] to parts[1]
        prefs.edit().remove(KEY_TOKENS).putString(KEY_SIGN_OUT_REASON, SignOutReason.SESSION_ENDED.name).commit()
        return null
    }

    /** Versions up to 1.0 kept both tokens in plain text in `auth_prefs`. */
    private fun migratePlaintextTokens(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val access = legacy.getString("auth_token", null)
        val refresh = legacy.getString("refresh_token", null)
        if (access == null && refresh == null) return

        if (access != null && refresh != null && !prefs.contains(KEY_TOKENS)) {
            prefs.edit().putString(KEY_TOKENS, cipher.encrypt(access + SEPARATOR + refresh)).commit()
        }
        legacy.edit().clear().commit()
        context.deleteSharedPreferences(LEGACY_PREFS)
    }

    companion object {
        private const val PREFS = "auth_v2"
        private const val LEGACY_PREFS = "auth_prefs"
        private const val KEY_ALIAS = "auth_tokens"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_SIGN_OUT_REASON = "sign_out_reason"

        // JWTs and the base64 refresh token never contain a newline.
        private const val SEPARATOR = "\n"

        @Volatile
        private var instance: AuthPreferences? = null

        fun get(context: Context): AuthPreferences =
            instance ?: synchronized(this) {
                instance ?: AuthPreferences(context.applicationContext).also { instance = it }
            }
    }
}
