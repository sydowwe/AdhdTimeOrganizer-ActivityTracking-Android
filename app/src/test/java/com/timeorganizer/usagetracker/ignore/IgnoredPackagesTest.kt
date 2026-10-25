package com.timeorganizer.usagetracker.ignore

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.ignore.IgnoreState.Companion.PENDING_IGNORE_MAX_AGE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoredPackagesTest {

    private val now = 1_800_000_000_000L

    private fun ignoreRule(pkg: String, label: String? = null) = AndroidTrackerRuleDto(pkg, label, AndroidRuleTarget.IGNORED, null)
    private fun activityRule(pkg: String, name: String) = AndroidTrackerRuleDto(pkg, null, AndroidRuleTarget.ACTIVITY, name)
    private fun ignore(pkg: String, at: Long = now, label: String? = null) = PendingIgnoreOp(pkg, IgnoreOpKind.IGNORE, at, label)
    private fun unignore(pkg: String, at: Long = now) = PendingIgnoreOp(pkg, IgnoreOpKind.UNIGNORE, at)

    @Test
    fun theDroppedSet_isServerIgnoresPlusPendingIgnoresMinusPendingStops() {
        val state = IgnoreState(
            rules = listOf(ignoreRule("insta"), ignoreRule("tiktok"), activityRule("docs", "Thesis")),
            pending = listOf(ignore("reddit"), unignore("tiktok"))
        )

        assertEquals(setOf("insta", "reddit"), state.ignored(now))
    }

    @Test
    fun aMappingRule_isNotIgnored() {
        assertEquals(emptySet<String>(), IgnoreState(rules = listOf(activityRule("docs", "Thesis"))).ignored(now))
    }

    @Test
    fun aRuleTargetFromANewerServer_isNotIgnored() {
        val state = IgnoreState(rules = listOf(AndroidTrackerRuleDto("x", null, "somethingNew", null)))

        assertEquals(emptySet<String>(), state.ignored(now))
    }

    @Test
    fun aNewOp_replacesThePendingOneForThatPackage() {
        val state = IgnoreState().withOp(ignore("reddit")).withOp(unignore("reddit", now + 1))

        assertEquals(listOf(unignore("reddit", now + 1)), state.pending)
        assertEquals(emptySet<String>(), state.ignored(now + 1))
    }

    // --- the call succeeded ---

    @Test
    fun aConfirmedIgnore_clears_andTheCachedRulesCarryItUntilTheNextFetch() {
        val op = ignore("reddit", label = "Reddit")
        val state = IgnoreState(rules = listOf(activityRule("reddit", "Thesis"))).withOp(op).confirmed(op)

        assertEquals(emptyList<PendingIgnoreOp>(), state.pending)
        assertEquals(listOf(AndroidTrackerRuleDto("reddit", "Reddit", AndroidRuleTarget.IGNORED, null)), state.rules)
        assertEquals(setOf("reddit"), state.ignored(now))
    }

    @Test
    fun aConfirmedStop_removesTheIgnoreRule() {
        val op = unignore("insta")
        val state = IgnoreState(rules = listOf(ignoreRule("insta"))).withOp(op).confirmed(op)

        assertEquals(emptyList<AndroidTrackerRuleDto>(), state.rules)
        assertEquals(emptyList<PendingIgnoreOp>(), state.pending)
        assertEquals(emptySet<String>(), state.ignored(now))
    }

    /** DELETE never touches a mapping, so neither does the cache. */
    @Test
    fun aConfirmedStop_keepsAMappingRule() {
        val op = unignore("docs")
        val state = IgnoreState(rules = listOf(activityRule("docs", "Thesis"))).withOp(op).confirmed(op)

        assertEquals(listOf(activityRule("docs", "Thesis")), state.rules)
    }

    /** The user changed their mind while the first call was out: the newer op still has to go. */
    @Test
    fun aConfirmation_ofAnOpReplacedMeanwhile_keepsTheNewerOne() {
        val first = ignore("reddit")
        val second = unignore("reddit", now + 1)
        val state = IgnoreState().withOp(first).withOp(second).confirmed(first)

        assertEquals(listOf(second), state.pending)
        assertEquals(emptySet<String>(), state.ignored(now + 1))
    }

    // --- the server refused ---

    @Test
    fun aRejectedIgnore_stopsCounting_andIsNotSentAgain() {
        val op = ignore("reddit")
        val state = IgnoreState().withOp(op).rejected(op)

        assertEquals(emptySet<String>(), state.ignored(now))
        assertEquals(emptyList<PendingIgnoreOp>(), state.sendable(now))
        assertEquals(AppIgnoreState.IGNORE_FAILED, IgnoredAppsList.stateOf("reddit", state, now))
    }

    /** A refused stop falls back to the server's word: still ignored. */
    @Test
    fun aRejectedStop_leavesTheAppIgnored() {
        val op = unignore("insta")
        val state = IgnoreState(rules = listOf(ignoreRule("insta"))).withOp(op).rejected(op)

        assertEquals(setOf("insta"), state.ignored(now))
        assertEquals(AppIgnoreState.STOP_FAILED, IgnoredAppsList.stateOf("insta", state, now))
    }

    // --- nothing learned: offline, 5xx ---

    @Test
    fun anUnansweredIgnore_keepsCounting_andIsSentByTheNextRun() {
        val state = IgnoreState().withOp(ignore("reddit"))

        assertEquals(setOf("reddit"), state.ignored(now + 60_000))
        assertEquals(listOf(ignore("reddit")), state.sendable(now + 60_000))
    }

    // --- the 7-day expiry ---

    @Test
    fun aPendingIgnore_aWeekOld_stopsCounting_butStaysForTheScreen() {
        val state = IgnoreState().withOp(ignore("reddit", at = now))
        val justBefore = now + PENDING_IGNORE_MAX_AGE_MS - 1
        val atTheWeek = now + PENDING_IGNORE_MAX_AGE_MS

        assertEquals(setOf("reddit"), state.ignored(justBefore))
        assertEquals(emptySet<String>(), state.ignored(atTheWeek))
        assertEquals(emptyList<PendingIgnoreOp>(), state.sendable(atTheWeek))
        assertEquals(1, state.pending.size)
        assertEquals(AppIgnoreState.IGNORE_FAILED, IgnoredAppsList.stateOf("reddit", state, atTheWeek))
    }

    /** Only an ignore expires: an unconfirmed stop never leaves the phone dropping an app — it records it already. */
    @Test
    fun aPendingStop_doesNotExpire() {
        val state = IgnoreState(rules = listOf(ignoreRule("insta"))).withOp(unignore("insta", at = now))
        val later = now + 3 * PENDING_IGNORE_MAX_AGE_MS

        assertEquals(emptySet<String>(), state.ignored(later))
        assertEquals(1, state.sendable(later).size)
    }

    @Test
    fun retryingAnExpiredIgnore_startsANewWeek() {
        val later = now + PENDING_IGNORE_MAX_AGE_MS
        val state = IgnoreState().withOp(ignore("reddit", at = now)).withOp(ignore("reddit", at = later))

        assertEquals(setOf("reddit"), state.ignored(later))
        assertEquals(AppIgnoreState.IGNORING, IgnoredAppsList.stateOf("reddit", state, later))
    }

    // --- the server's list arrives ---

    @Test
    fun aFetchThatAgrees_clearsThePendingOps() {
        val state = IgnoreState(rules = listOf(ignoreRule("insta")))
            .withOp(ignore("reddit"))
            .withOp(unignore("insta"))
            .fetched(listOf(ignoreRule("reddit")))

        assertEquals(emptyList<PendingIgnoreOp>(), state.pending)
        assertEquals(setOf("reddit"), state.ignored(now))
    }

    /** Fetched before the call went out (offline earlier, or the run's order): the op stands and is sent. */
    @Test
    fun aFetchThatDisagrees_keepsThePendingOps() {
        val state = IgnoreState(rules = listOf(ignoreRule("insta")))
            .withOp(ignore("reddit"))
            .withOp(unignore("insta"))
            .fetched(listOf(ignoreRule("insta")))

        assertEquals(listOf(ignore("reddit"), unignore("insta")), state.pending)
        assertEquals(setOf("reddit"), state.ignored(now))
    }

    /** The server ignores it now (the web, or a call whose answer was lost): the "couldn't save" is moot. */
    @Test
    fun aFetchThatAgrees_clearsAGivenUpIgnoreToo() {
        val op = ignore("reddit")
        val state = IgnoreState().withOp(op).rejected(op).fetched(listOf(ignoreRule("reddit")))

        assertEquals(emptyList<PendingIgnoreOp>(), state.pending)
        assertEquals(AppIgnoreState.IGNORED, IgnoredAppsList.stateOf("reddit", state, now))
    }

    /** Un-ignored on the web: the phone records the app again on the next run. */
    @Test
    fun aFetch_replacesTheServerSet() {
        val state = IgnoreState(rules = listOf(ignoreRule("insta"))).fetched(emptyList())

        assertEquals(emptySet<String>(), state.ignored(now))
    }

    @Test
    fun anOpThatNeverReachedTheServer_andWasUndone_isClearedByTheFetch() {
        val state = IgnoreState().withOp(ignore("reddit")).withOp(unignore("reddit")).fetched(emptyList())

        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun rejectingAnOpThatIsNoLongerPending_changesNothing() {
        val state = IgnoreState().withOp(ignore("reddit")).withOp(unignore("reddit"))

        assertEquals(state, state.rejected(ignore("reddit")))
    }
}
