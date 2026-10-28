package com.timeorganizer.usagetracker.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRulesTest {

    // ---- the field ---------------------------------------------------------

    @Test
    fun aTypedLine_isTrimmed_andBlankIsNothing() {
        assertEquals(CaptureDraft("Email the landlord", null), CaptureRules.typed("  Email the landlord \n"))
        assertNull(CaptureRules.typed("   "))
        assertNull(CaptureRules.typed(null))
    }

    @Test
    fun aTooLongText_isCutToTheServersLimit_neverThroughASurrogatePair() {
        val long = "a".repeat(CaptureRules.MAX_TEXT_LENGTH + 50)
        assertEquals(CaptureRules.MAX_TEXT_LENGTH, CaptureRules.typed(long)!!.text.length)

        // An emoji straddling the limit: its high half would be the last character kept.
        val straddling = "a".repeat(CaptureRules.MAX_TEXT_LENGTH - 1) + "😀" + "tail"
        val cut = CaptureRules.typed(straddling)!!.text
        assertEquals(CaptureRules.MAX_TEXT_LENGTH - 1, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    // ---- a share -----------------------------------------------------------

    /** Chrome's share: the link as the text, the page title as the subject. A to-do named by a URL says nothing. */
    @Test
    fun aBrowserShare_isTheTitle_withTheLink() {
        assertEquals(
            CaptureDraft("How to stop procrastinating", "https://example.com/a"),
            CaptureRules.shared("https://example.com/a", "How to stop procrastinating")
        )
    }

    @Test
    fun aBareLinkWithNoTitle_isTheLinkAlone() {
        assertEquals(CaptureDraft("https://example.com/a", null), CaptureRules.shared(" https://example.com/a ", null))
    }

    /** Text around a link is the thought; the link also travels as `url`, and the server keeps one copy in the note. */
    @Test
    fun textWithALink_keepsTheText_andSendsTheLink() {
        assertEquals(
            CaptureDraft("Read this later: https://example.com/a.", "https://example.com/a"),
            CaptureRules.shared("Read this later: https://example.com/a.", "ignored subject")
        )
    }

    @Test
    fun aShareOfPlainText_hasNoLink() {
        assertEquals(CaptureDraft("Buy oat milk", null), CaptureRules.shared("Buy oat milk", null))
    }

    @Test
    fun aShareWithOnlyASubject_isTheSubject_andAnEmptyShareIsNothing() {
        assertEquals(CaptureDraft("A title", null), CaptureRules.shared(null, "A title"))
        assertNull(CaptureRules.shared("  ", " "))
        assertNull(CaptureRules.shared(null, null))
    }

    @Test
    fun aLinkLongerThanTheServerTakes_staysInTheTextOnly() {
        val link = "https://example.com/" + "x".repeat(CaptureRules.MAX_URL_LENGTH)
        assertEquals(CaptureDraft("Look $link", null), CaptureRules.shared("Look $link", null))
    }

    // ---- the answers -------------------------------------------------------

    @Test
    fun onlyTheServerTakingIt_removesACapture() {
        assertEquals(CaptureSendOutcome.SENT, CaptureRules.classify(200))
        assertEquals(CaptureSendOutcome.AUTH_REQUIRED, CaptureRules.classify(401))
        for (status in listOf(400, 413, 422)) assertEquals(CaptureSendOutcome.REFUSED, CaptureRules.classify(status))
        // 409 is noPriority: true of every capture, so the pass waits rather than marking each one refused.
        for (status in listOf(403, 404, 408, 409, 429, 500, 503)) assertEquals(CaptureSendOutcome.WAIT, CaptureRules.classify(status))
        assertEquals(CaptureSendOutcome.WAIT, CaptureRules.classify(null))
    }

    @Test
    fun aRefusedCapture_doesNotHoldTheOthersBack_butWaitingStopsThePass() {
        assertTrue(CaptureRules.continues(CaptureSendOutcome.SENT))
        assertTrue(CaptureRules.continues(CaptureSendOutcome.REFUSED))
        assertFalse(CaptureRules.continues(CaptureSendOutcome.WAIT))
        assertFalse(CaptureRules.continues(CaptureSendOutcome.AUTH_REQUIRED))
    }
}
