package com.timeorganizer.usagetracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val root = "https://backend.adhdtimeorganizer.com/"
    private val day = UpdatePolicy.CHECK_INTERVAL_MS
    private val now = 1_800_000_000_000L

    private fun latest(code: Int?, name: String? = "1.7.0", path: String? = "/api/android-release/$code/apk") =
        LatestRelease(code, name, path)

    @Test
    fun `a check is due never-checked, a day on, or when the clock moved back`() {
        assertTrue(UpdatePolicy.isDue(UpdateState(), now))
        assertFalse(UpdatePolicy.isDue(UpdateState(checkedAtMs = now - day + 1), now))
        assertTrue(UpdatePolicy.isDue(UpdateState(checkedAtMs = now - day), now))
        assertTrue(UpdatePolicy.isDue(UpdateState(checkedAtMs = now + 60_000), now))
    }

    @Test
    fun `a newer build is kept with its download on the same server`() {
        val state = UpdatePolicy.afterCheck(UpdateState(), latest(10), installedCode = 9, serverRoot = root, nowMs = now)

        assertEquals(now, state.checkedAtMs)
        assertEquals(AvailableUpdate(10, "1.7.0", "https://backend.adhdtimeorganizer.com/api/android-release/10/apk"), state.available)
    }

    @Test
    fun `the same or an older build, nothing published, or a malformed answer is no update`() {
        val before = UpdateState(available = AvailableUpdate(10, "1.7.0", "x"))

        for (answer in listOf(latest(9), latest(8), null, latest(null), latest(10, name = " "), latest(10, path = null))) {
            val state = UpdatePolicy.afterCheck(before, answer, installedCode = 9, serverRoot = root, nowMs = now)
            assertNull("$answer", state.available)
            assertEquals("an answer is an answer: the next check is a day on", now, state.checkedAtMs)
        }
    }

    @Test
    fun `a download on another host is refused`() {
        assertNull(UpdatePolicy.downloadUrl(root, "https://evil.example/app.apk"))
        assertNull(UpdatePolicy.downloadUrl(root, "//evil.example/app.apk"))
        assertNull(UpdatePolicy.downloadUrl(root, "http://backend.adhdtimeorganizer.com/api/android-release/10/apk"))
        assertNull(UpdatePolicy.downloadUrl(root, "https://backend.adhdtimeorganizer.com:8443/x.apk"))
        assertEquals("https://backend.adhdtimeorganizer.com/api/android-release/10/apk",
            UpdatePolicy.downloadUrl(root, "https://backend.adhdtimeorganizer.com/api/android-release/10/apk"))

        val state = UpdatePolicy.afterCheck(UpdateState(), latest(10, path = "https://evil.example/a.apk"), 9, root, now)
        assertNull(state.available)
    }

    @Test
    fun `one notification per new version`() {
        var state = UpdatePolicy.afterCheck(UpdateState(), latest(10), installedCode = 9, serverRoot = root, nowMs = now)
        assertTrue(UpdatePolicy.shouldNotify(state, installedCode = 9))

        state = UpdatePolicy.notified(state, state.available!!)
        assertFalse(UpdatePolicy.shouldNotify(state, installedCode = 9))

        state = UpdatePolicy.afterCheck(state, latest(10), installedCode = 9, serverRoot = root, nowMs = now + day)
        assertFalse("the same version a day later stays quiet", UpdatePolicy.shouldNotify(state, installedCode = 9))

        state = UpdatePolicy.afterCheck(state, latest(11, name = "1.8.0"), installedCode = 9, serverRoot = root, nowMs = now + 2 * day)
        assertTrue("a newer one is news again", UpdatePolicy.shouldNotify(state, installedCode = 9))
    }

    @Test
    fun `an update installed since the check is no longer shown`() {
        val state = UpdateState(checkedAtMs = now, available = AvailableUpdate(10, "1.7.0", "u"))

        assertEquals(10, UpdatePolicy.current(state, installedCode = 9)?.versionCode)
        assertNull(UpdatePolicy.current(state, installedCode = 10))
        assertFalse(UpdatePolicy.shouldNotify(state, installedCode = 10))
    }
}
