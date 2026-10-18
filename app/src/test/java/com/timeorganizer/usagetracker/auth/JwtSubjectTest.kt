package com.timeorganizer.usagetracker.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtSubjectTest {

    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"ES256"}""", payload)
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }

    @Test
    fun readsAStringSubject() {
        assertEquals("42", JwtSubject.of(token("""{"sub":"42","exp":1}""")))
    }

    @Test
    fun readsANumericSubject() {
        assertEquals("42", JwtSubject.of(token("""{"exp":1, "sub": 42}""")))
    }

    @Test
    fun anythingElseIsNull() {
        assertNull(JwtSubject.of("not-a-jwt"))
        assertNull(JwtSubject.of(token("""{"exp":1}""")))
        assertNull(JwtSubject.of("a.!!!.c"))
    }
}
