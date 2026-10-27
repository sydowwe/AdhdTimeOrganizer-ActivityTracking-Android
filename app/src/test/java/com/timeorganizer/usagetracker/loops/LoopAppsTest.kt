package com.timeorganizer.usagetracker.loops

import com.timeorganizer.usagetracker.ignore.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopAppsTest {

    private val usage = listOf(
        AppUsage("com.reddit.frontpage", "Reddit", 5 * 3_600_000L),
        AppUsage("com.whatsapp", "WhatsApp", 2 * 3_600_000L),
        AppUsage("org.thoughtcrime.securesms", "Signal", 600_000L),
        AppUsage("com.sec.android.app.launcher", "One UI Home", 9 * 3_600_000L)
    )

    private fun build(notCounted: Set<String>, installed: Map<String, String> = emptyMap()) =
        LoopAppsList.build(usage, setOf("com.sec.android.app.launcher"), notCounted) { installed[it] }

    @Test
    fun theTickedAppsComeFirst_thenTheBusiest_andTheHomeScreenIsNotOffered() {
        val list = build(setOf("org.thoughtcrime.securesms"))

        assertEquals(listOf("Signal", "Reddit", "WhatsApp"), list.recent.map { it.label })
        assertEquals(listOf(true, false, false), list.recent.map { it.notCounted })
    }

    @Test
    fun aTickedAppNotUsedThisWeek_isListedBelow_soItCanBeUnticked() {
        val list = build(setOf("com.facebook.orca", "gone.app"), installed = mapOf("com.facebook.orca" to "Messenger"))

        assertEquals(listOf("gone.app", "Messenger"), list.others.map { it.label })
        assertEquals(listOf(null, null), list.others.map { it.foregroundMs })
    }
}
