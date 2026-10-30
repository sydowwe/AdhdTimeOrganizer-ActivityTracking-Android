package com.timeorganizer.usagetracker.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientVersionTest {

    @Test
    fun theHeader_namesTheTrackerTheVersionAndTheCode() {
        assertEquals("android/1.4.0 (7)", ClientVersion.header("1.4.0", 7))
    }

    @Test
    fun theHeader_readsBackAsTheVersionTheWayTheServerParsesIt() {
        // TrackerClientVersion.Parse: after the slash, up to the first space.
        val header = ClientVersion.header("12.30.105", 2147483647)
        assertEquals("12.30.105", header.substringAfter('/').substringBefore(' '))
        assertTrue("the server keeps at most 64 characters", header.length <= 64)
    }

    @Test
    fun theHost_isTheServersNameWithoutSchemeOrPath() {
        assertEquals("test.backend.adhdtimeorganizer.com", ClientVersion.host("https://test.backend.adhdtimeorganizer.com/"))
        assertEquals("backend.adhdtimeorganizer.com", ClientVersion.host("https://backend.adhdtimeorganizer.com/"))
    }

    @Test
    fun theHost_keepsAPortTheRootNames() {
        assertEquals("10.0.2.2:8081", ClientVersion.host("http://10.0.2.2:8081/"))
    }

    @Test
    fun aRootThatIsNotAUrl_isShownAsGiven() {
        assertEquals("not a url", ClientVersion.host("not a url"))
    }
}
