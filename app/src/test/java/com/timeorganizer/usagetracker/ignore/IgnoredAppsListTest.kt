package com.timeorganizer.usagetracker.ignore

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgnoredAppsListTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun usage(pkg: String, minutes: Long, label: String = pkg) = AppUsage(pkg, label, minutes * minute)

    @Test
    fun recentApps_areBusiestFirst_andNeedAMinute() {
        val list = IgnoredAppsList.build(
            listOf(usage("a", 5), usage("b", 90), usage("glance", 0), usage("c", 1)),
            excluded = emptySet(), state = IgnoreState(), nowMs = now
        )

        assertEquals(listOf("b", "a", "c"), list.recent.map { it.packageName })
        assertEquals(90 * minute, list.recent.first().foregroundMs)
    }

    @Test
    fun theHomeAppAndThisApp_areNeverOffered() {
        val list = IgnoredAppsList.build(
            listOf(usage("launcher", 300), usage("tracker", 10), usage("chat", 20)),
            excluded = setOf("launcher", "tracker"), state = IgnoreState(), nowMs = now
        )

        assertEquals(listOf("chat"), list.recent.map { it.packageName })
    }

    @Test
    fun eachRow_saysWhatTheRulesMakeOfIt() {
        val state = IgnoreState(
            rules = listOf(
                AndroidTrackerRuleDto("docs", "Docs", AndroidRuleTarget.ACTIVITY, "Thesis"),
                AndroidTrackerRuleDto("reddit", "Reddit", AndroidRuleTarget.ACTIVITY, "Scrolling"),
                AndroidTrackerRuleDto("insta", "Instagram", AndroidRuleTarget.IGNORED, null)
            )
        )

        val rows = IgnoredAppsList.build(
            listOf(usage("docs", 30), usage("reddit", 20), usage("insta", 10), usage("chat", 5)), emptySet(), state, now
        ).recent.associateBy { it.packageName }

        assertEquals("Thesis", rows.getValue("docs").mappedTo)
        assertEquals("Scrolling", rows.getValue("reddit").mappedTo)
        assertEquals(AppIgnoreState.IGNORED, rows.getValue("insta").state)
        assertNull(rows.getValue("insta").mappedTo)
        assertEquals(AppIgnoreState.NONE, rows.getValue("chat").state)
        assertNull(rows.getValue("chat").mappedTo)
    }

    /** Unused lately or uninstalled: listed below, with the server's or the op's label, so the ignore can be undone. */
    @Test
    fun ignoredAppsNotUsedThisWeek_areListedBelow() {
        val state = IgnoreState(
            rules = listOf(
                AndroidTrackerRuleDto("old.game", "Old Game", AndroidRuleTarget.IGNORED, null),
                AndroidTrackerRuleDto("no.label", null, AndroidRuleTarget.IGNORED, null),
                AndroidTrackerRuleDto("insta", "Instagram", AndroidRuleTarget.IGNORED, null)
            ),
            pending = listOf(PendingIgnoreOp("news", IgnoreOpKind.IGNORE, now, "Breaking News"))
        )

        val list = IgnoredAppsList.build(listOf(usage("insta", 10)), emptySet(), state, now)

        assertEquals(listOf("insta"), list.recent.map { it.packageName })
        assertEquals(listOf("Breaking News", "no.label", "Old Game"), list.others.map { it.label })
        assertEquals(listOf(AppIgnoreState.IGNORING, AppIgnoreState.IGNORED, AppIgnoreState.IGNORED), list.others.map { it.state })
        assertEquals(listOf(null, null, null), list.others.map { it.foregroundMs })
    }

    @Test
    fun aPendingStop_isShownAsStopping_andNotAsIgnored() {
        val state = IgnoreState(
            rules = listOf(AndroidTrackerRuleDto("insta", "Instagram", AndroidRuleTarget.IGNORED, null)),
            pending = listOf(PendingIgnoreOp("insta", IgnoreOpKind.UNIGNORE, now))
        )

        val row = IgnoredAppsList.build(listOf(usage("insta", 10)), emptySet(), state, now).recent.single()

        assertEquals(AppIgnoreState.STOPPING, row.state)
        assertEquals(false, row.state.isIgnored)
    }

    @Test
    fun theButton_followsWhetherThePhoneDropsTheApp() {
        assertEquals(
            setOf(AppIgnoreState.IGNORED, AppIgnoreState.IGNORING, AppIgnoreState.STOP_FAILED),
            AppIgnoreState.entries.filter { it.isIgnored }.toSet()
        )
    }
}
