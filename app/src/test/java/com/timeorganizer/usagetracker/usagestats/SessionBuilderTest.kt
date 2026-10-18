package com.timeorganizer.usagetracker.usagestats

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBuilderTest {

    private fun resumed(pkg: String, cls: String, at: Long) = UsageEventRecord(EventKind.RESUMED, pkg, cls, at)
    private fun paused(pkg: String, cls: String, at: Long) = UsageEventRecord(EventKind.PAUSED, pkg, cls, at)
    private fun other(at: Long) = UsageEventRecord(EventKind.OTHER, "android", "", at)
    private fun closeAll(at: Long) = UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at)
    private fun startup(at: Long) = UsageEventRecord(EventKind.DEVICE_STARTUP, "android", "", at)

    private fun build(queryEndMs: Long, vararg events: UsageEventRecord): BuildResult {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        return builder.build(queryEndMs)
    }

    @Test
    fun anActivityHandOffInsideAnApp_isOneSession() {
        val result = build(
            60_000,
            resumed("chat", "List", 0), paused("chat", "List", 10_000),
            resumed("chat", "Conversation", 10_050), paused("chat", "Conversation", 40_000)
        )

        assertEquals(listOf(RawSession("chat", 0, 40_000)), result.sessions)
    }

    @Test
    fun theOldActivitysLatePause_doesNotCloseTheNewOne() {
        // Multi-resume (Android 10+): the new activity resumes before the old one pauses.
        val result = build(
            60_000,
            resumed("chat", "List", 0), resumed("chat", "Conversation", 5_000),
            paused("chat", "List", 5_100), paused("chat", "Conversation", 30_000)
        )

        assertEquals(listOf(RawSession("chat", 0, 30_000)), result.sessions)
    }

    @Test
    fun twoInstancesOfOneActivity_needBothPauses() {
        val result = build(
            60_000,
            resumed("app", "Doc", 0), resumed("app", "Doc", 1_000),
            paused("app", "Doc", 2_000), paused("app", "Doc", 20_000)
        )

        assertEquals(listOf(RawSession("app", 0, 20_000)), result.sessions)
    }

    /**
     * The bug this builder replaced: the cursor moved to the latest event of any kind, past the open session's
     * resume, so the next window saw only its pause — an orphan — and the whole session was lost.
     */
    @Test
    fun aSessionOpenAtTheEndOfAWindow_isBuiltWholeByTheNextWindow() {
        val first = build(100_000, resumed("maps", "Nav", 10_000), other(50_000), other(90_000))

        assertEquals(emptyList<RawSession>(), first.sessions)
        assertEquals("the next read starts at the open session's resume", 10_000L, first.resumeFromMs)

        // The usage log's begin is inclusive, so the resume at the cursor is read again.
        val second = build(200_000, resumed("maps", "Nav", 10_000), other(50_000), other(90_000), paused("maps", "Nav", 150_000))

        assertEquals(listOf(RawSession("maps", 10_000, 150_000)), second.sessions)
        assertEquals(200_000L, second.resumeFromMs)
    }

    @Test
    fun withNothingOpen_theNextReadStartsAtTheEndOfThisOne() {
        val result = build(100_000, resumed("a", "A", 0), paused("a", "A", 10_000), other(20_000))

        assertEquals(100_000L, result.resumeFromMs)
    }

    @Test
    fun theEarliestOpenSession_decidesTheCursor() {
        val result = build(100_000, resumed("a", "A", 5_000), resumed("b", "B", 7_000))

        assertEquals(5_000L, result.resumeFromMs)
    }

    @Test
    fun aPauseWhoseResumeWasBeforeTheWindow_isIgnored() {
        val result = build(60_000, paused("old", "Old", 500), resumed("b", "B", 600), paused("b", "B", 9_000))

        assertEquals(listOf(RawSession("b", 600, 9_000)), result.sessions)
    }

    @Test
    fun screenOff_closesEverySession_andSplitScreenCountsBothApps() {
        val result = build(
            60_000,
            resumed("video", "Player", 0), resumed("notes", "Editor", 1_000),
            closeAll(30_000)
        )

        assertEquals(listOf(RawSession("video", 0, 30_000), RawSession("notes", 1_000, 30_000)), result.sessions)
        assertEquals(60_000L, result.resumeFromMs)
    }

    @Test
    fun afterAReboot_anOpenSessionEndsAtTheLastEventBeforeIt() {
        val result = build(500_000, resumed("game", "Main", 0), other(40_000), startup(400_000), resumed("b", "B", 410_000))

        assertEquals(listOf(RawSession("game", 0, 40_000)), result.sessions)
        assertEquals(410_000L, result.resumeFromMs)
    }

    @Test
    fun aQuickReturnToTheSameApp_isOneSitting() {
        val result = build(
            60_000,
            resumed("app", "A", 0), paused("app", "A", 10_000),
            resumed("app", "A", 11_500), paused("app", "A", 30_000)
        )

        assertEquals(listOf(RawSession("app", 0, 30_000)), result.sessions)
    }

    @Test
    fun aReturnAfterAnotherApp_isTwoSessions() {
        val result = build(
            60_000,
            resumed("app", "A", 0), paused("app", "A", 10_000),
            resumed("other", "O", 10_200), paused("other", "O", 11_000),
            resumed("app", "A", 11_500), paused("app", "A", 30_000)
        )

        assertEquals(
            listOf(RawSession("app", 0, 10_000), RawSession("other", 10_200, 11_000), RawSession("app", 11_500, 30_000)),
            result.sessions
        )
    }

    @Test
    fun aReturnAfterTheMergeGap_isTwoSessions() {
        val result = build(
            60_000,
            resumed("app", "A", 0), paused("app", "A", 10_000),
            resumed("app", "A", 12_001), paused("app", "A", 30_000)
        )

        assertEquals(listOf(RawSession("app", 0, 10_000), RawSession("app", 12_001, 30_000)), result.sessions)
    }

    @Test
    fun noEvents_keepNothingAndMoveTheCursorToTheEnd() {
        val result = build(100_000)

        assertEquals(emptyList<RawSession>(), result.sessions)
        assertEquals(100_000L, result.resumeFromMs)
    }
}
