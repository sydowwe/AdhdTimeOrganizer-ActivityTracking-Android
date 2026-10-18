package com.timeorganizer.usagetracker.auth

import java.util.Base64

/**
 * Reads the `sub` claim of an access token without verifying it — only to notice that a different account
 * signed in, so the previous account's queued sessions are not sent to the new one. Never used for trust.
 */
object JwtSubject {

    // A string or a bare number.
    private val SUB = Regex(""""sub"\s*:\s*"?([^",}\s]+)""")

    fun of(accessToken: String): String? {
        val payload = accessToken.split('.').getOrNull(1) ?: return null
        val json = try {
            String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SUB.find(json)?.groupValues?.get(1)
    }
}
