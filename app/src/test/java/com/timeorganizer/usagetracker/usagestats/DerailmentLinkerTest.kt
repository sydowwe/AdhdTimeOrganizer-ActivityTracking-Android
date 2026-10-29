package com.timeorganizer.usagetracker.usagestats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DerailmentLinkerTest {

    private fun screenOn(at: Long) = UsageEventRecord(EventKind.SCREEN_ON, "android", "", at)
    private fun unlocked(at: Long) = UsageEventRecord(EventKind.UNLOCKED, "android", "", at)
    private fun closeAll(at: Long) = UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at)
    private fun resumed(pkg: String, at: Long) = UsageEventRecord(EventKind.RESUMED, pkg, "Main", at)
    private fun paused(pkg: String, at: Long) = UsageEventRecord(EventKind.PAUSED, pkg, "Main", at)
    private fun notified(pkg: String, at: Long) = UsageEventRecord(EventKind.NOTIFIED, pkg, "", at)

    /** One app handing the front to another, as the log writes it. */
    private fun switch(from: String, to: String, at: Long) = listOf(paused(from, at), resumed(to, at))

    private val excluded = setOf("launcher", "this.app")
    private val dropped = setOf("this.app", "ignored.app")

    private fun s(seconds: Long) = seconds * 1_000

    /** What `queryEvents(from, to)` returns: begin inclusive, end exclusive. */
    private fun List<UsageEventRecord>.read(fromMs: Long, toMs: Long) = filter { it.timestampMs in fromMs until toMs }

    private fun link(log: List<UsageEventRecord>, fromMs: Long, toMs: Long) =
        DerailmentLinker.link(log.read(fromMs - DerailmentLinker.LOOKBACK_MS, toMs), fromMs, toMs, excluded, dropped)

    /** The sync's loop over the outbox's unique keys: each read starts where the last one said, a repeat is skipped. */
    private fun syncs(log: List<UsageEventRecord>, startMs: Long, vararg readEnds: Long): Pair<List<Interruption>, List<Derailment>> {
        var fromMs = startMs
        val interruptions = linkedSetOf<Interruption>()
        val derailments = linkedMapOf<Long, Derailment>()
        for (toMs in readEnds) {
            val read = link(log, fromMs, toMs)
            interruptions += read.interruptions
            read.derailments.forEach { derailments.putIfAbsent(it.startMs, it) }
            fromMs = maxOf(read.resumeFromMs, fromMs)
        }
        return interruptions.toList() to derailments.values.toList()
    }

    // Writing in Docs: WhatsApp pulls the user away and Slack interrupts inside it; Instagram is never opened; back to
    // Docs at 600 s; a later WhatsApp message is left unread.
    private val afternoon = listOf(screenOn(0), unlocked(s(1)), resumed("docs", s(2))) +
        notified("whatsapp", s(100)) + switch("docs", "whatsapp", s(110)) +
        notified("instagram", s(580)) +
        notified("slack", s(590)) + switch("whatsapp", "slack", s(595)) +
        switch("slack", "docs", s(600)) +
        notified("whatsapp", s(1_000)) +
        listOf(paused("docs", s(2_000)), closeAll(s(2_000)))

    @Test
    fun aNotificationWhoseAppIsOpenedWithinAMinute_startsADerailment_endedByGoingBack() {
        val read = link(afternoon, 0, s(3_000))

        assertEquals(
            listOf(Derailment("whatsapp", s(100), s(110), s(600), DerailmentEnd.RETURNED)),
            read.derailments
        )
        assertEquals(s(3_000), read.resumeFromMs)
    }

    @Test
    fun everyInterruptionIsKept_linkedOrNot() {
        val read = link(afternoon, 0, s(3_000))

        assertEquals(
            listOf(
                Interruption("whatsapp", s(100)),
                Interruption("instagram", s(580)),
                Interruption("slack", s(590)),
                Interruption("whatsapp", s(1_000))
            ),
            read.interruptions
        )
    }

    @Test
    fun aNotificationOpenedInsideADerailment_startsNoneOfItsOwn() {
        // Slack opened at 595 s, while WhatsApp's derailment holds (until 600 s): one stretch, WhatsApp's.
        val read = link(afternoon, 0, s(3_000))

        assertTrue(read.derailments.none { it.packageName == "slack" })
    }

    @Test
    fun aBurstOfMessages_isOneDerailment_ownedByTheFirst() {
        val log = listOf(resumed("docs", 0)) +
            notified("whatsapp", s(100)) + notified("whatsapp", s(130)) +
            switch("docs", "whatsapp", s(150)) + switch("whatsapp", "docs", s(400))

        val read = link(log, 0, s(1_000))

        assertEquals(listOf(Derailment("whatsapp", s(100), s(150), s(400), DerailmentEnd.RETURNED)), read.derailments)
        assertEquals(2, read.interruptions.size)
    }

    @Test
    fun anAppOpenedAfterTheMinute_orNever_isAnInterruptionOnly() {
        val log = listOf(resumed("docs", 0)) +
            notified("whatsapp", s(100)) + switch("docs", "whatsapp", s(161)) + switch("whatsapp", "docs", s(300))

        val read = link(log, 0, s(1_000))

        assertTrue(read.derailments.isEmpty())
        assertEquals(listOf(Interruption("whatsapp", s(100))), read.interruptions)
    }

    @Test
    fun aMessageArrivingInTheOpenApp_derailsNothing() {
        val log = listOf(resumed("whatsapp", 0), notified("whatsapp", s(30)), paused("whatsapp", s(90)), closeAll(s(90)))

        assertTrue(link(log, 0, s(1_000)).derailments.isEmpty())
    }

    @Test
    fun aPhoneInThePocket_opensFromTheLockScreen_andTheDerailmentEndsWithTheScreen() {
        // Nothing was in front: no "going back", so it runs on through Instagram until the screen goes off.
        val log = listOf(notified("whatsapp", s(10)), screenOn(s(20)), unlocked(s(21)), resumed("whatsapp", s(22))) +
            switch("whatsapp", "instagram", s(60)) +
            listOf(paused("instagram", s(1_800)), closeAll(s(1_800)))

        val read = link(log, 0, s(3_000))

        assertEquals(listOf(Derailment("whatsapp", s(10), s(22), s(1_800), DerailmentEnd.SCREEN_OFF)), read.derailments)
    }

    @Test
    fun theHomeScreen_isNothingToGoBackTo() {
        val log = listOf(resumed("launcher", 0), notified("whatsapp", s(10))) +
            switch("launcher", "whatsapp", s(15)) + switch("whatsapp", "launcher", s(40)) +
            listOf(paused("launcher", s(100)), closeAll(s(100)))

        val read = link(log, 0, s(1_000))

        assertEquals(listOf(Derailment("whatsapp", s(10), s(15), s(100), DerailmentEnd.SCREEN_OFF)), read.derailments)
    }

    @Test
    fun twoHoursOn_theDerailmentIsCapped() {
        val log = listOf(resumed("docs", 0), notified("youtube", s(10))) + switch("docs", "youtube", s(20)) +
            listOf(paused("youtube", s(4 * 3_600)), closeAll(s(4 * 3_600)))

        val read = link(log, 0, s(5 * 3_600))

        assertEquals(
            listOf(Derailment("youtube", s(10), s(20), s(20) + DerailmentLinker.CAP_MS, DerailmentEnd.CAPPED)),
            read.derailments
        )
    }

    @Test
    fun aDerailmentStillGoing_isHeld_andTheNextReadStartsAtItsNotification() {
        val log = listOf(resumed("docs", 0), notified("whatsapp", s(100))) + switch("docs", "whatsapp", s(110))

        val read = link(log, 0, s(900))

        assertTrue(read.derailments.isEmpty())
        assertEquals(listOf(Interruption("whatsapp", s(100))), read.interruptions)
        assertEquals(s(100), read.resumeFromMs)
    }

    @Test
    fun aNotificationWhoseMinuteIsntUp_isHeld() {
        val log = listOf(resumed("docs", 0), notified("whatsapp", s(100)))

        assertEquals(s(100), link(log, 0, s(150)).resumeFromMs)
        assertEquals(s(200), link(log, 0, s(200)).resumeFromMs)
    }

    @Test
    fun theNextReadNeverStartsInsideASettledDerailment_aLaterNotificationDependsOn() {
        // Read to 620 s: Instagram's minute isn't up, but resuming at it would re-read Slack (590 s) without WhatsApp's
        // derailment around it, and Slack would start one of its own. So the read resumes at WhatsApp's notification.
        assertEquals(s(100), link(afternoon, 0, s(620)).resumeFromMs)
    }

    @Test
    fun readsInPieces_findWhatOneLongReadFinds() {
        val whole = link(afternoon, 0, s(3_000))

        // Ends inside WhatsApp's derailment, just after it with Instagram undecided, and after everything.
        val (interruptions, derailments) = syncs(afternoon, 0, s(300), s(620), s(700), s(3_000))

        assertEquals(whole.interruptions, interruptions)
        assertEquals(whole.derailments, derailments)
    }

    @Test
    fun readingTheSameWindowAgain_findsTheSame() {
        assertEquals(link(afternoon, 0, s(3_000)), link(afternoon, 0, s(3_000)))
    }

    @Test
    fun thisAppsOwnNudges_andIgnoredApps_areNeverRead() {
        val log = listOf(resumed("docs", 0), notified("this.app", s(10)), notified("ignored.app", s(20))) +
            switch("docs", "ignored.app", s(25))

        val read = link(log, 0, s(1_000))

        assertTrue(read.interruptions.isEmpty())
        assertTrue(read.derailments.isEmpty())
    }
}
