package com.timeorganizer.usagetracker.chime

import com.timeorganizer.usagetracker.ignore.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeAppsTest {

    private val reddit = "com.reddit.frontpage"
    private val instagram = "com.instagram.android"
    private val chrome = "com.android.chrome"
    private val maps = "com.google.android.apps.maps"

    /** The server's distraction packages. */
    private val distractions = setOf(reddit, instagram)

    // ---- what is chosen ------------------------------------------------------

    @Test
    fun followingTheDistractionFlags_choosesThemAll_untilOneIsUnticked() {
        val choices = ChimeChoices()
        assertEquals(distractions, choices.chosen(distractions))

        val withoutReddit = choices.set(reddit, false, distractions)
        assertEquals(setOf(instagram), withoutReddit.chosen(distractions))
        assertEquals(setOf(reddit), withoutReddit.removed)
    }

    @Test
    fun anUntickedDistractionApp_staysOut_whenTheServerListsItAgain() {
        val choices = ChimeChoices().set(reddit, false, distractions)

        assertFalse(reddit in choices.chosen(distractions + chrome))
    }

    @Test
    fun aNewlyFlaggedApp_isChimedWithoutBeingTicked() {
        assertTrue(chrome in ChimeChoices().chosen(distractions + chrome))
    }

    @Test
    fun anAppTickedByHand_isChimedWhateverTheServerSays() {
        val choices = ChimeChoices().set(chrome, true, distractions)

        assertEquals(setOf(chrome), choices.added)
        assertTrue(chrome in choices.chosen(emptySet()))
    }

    @Test
    fun tickingAFollowedDistractionApp_againAfterUnticking_justForgetsTheUntick() {
        val choices = ChimeChoices().set(reddit, false, distractions).set(reddit, true, distractions)

        assertEquals(ChimeChoices(), choices)
    }

    @Test
    fun withTheSwitchOff_onlyTheTickedAppsAreChimed() {
        val choices = ChimeChoices(followDistractions = false).set(reddit, true, distractions)

        assertEquals(setOf(reddit), choices.chosen(distractions))
        // Unticked with the switch off: forgotten, not remembered as left out.
        assertEquals(ChimeChoices(followDistractions = false), choices.set(reddit, false, distractions))
    }

    // ---- the screen's rows ---------------------------------------------------

    @Test
    fun rows_distractionAppsFirst_thenBusiestFirst() {
        val usage = listOf(
            AppUsage(chrome, "Chrome", 90 * 60_000L),
            AppUsage(instagram, "Instagram", 20 * 60_000L),
            AppUsage(reddit, "Reddit", 40 * 60_000L)
        )

        val rows = ChimeAppsList.build(usage, emptySet(), ChimeChoices(), distractions) { null }.recent

        assertEquals(listOf(reddit, instagram, chrome), rows.map { it.packageName })
        assertEquals(listOf(ChimeReason.DISTRACTION, ChimeReason.DISTRACTION, ChimeReason.NONE), rows.map { it.reason })
    }

    @Test
    fun rows_sayWhyEachIsOrIsNotChimed() {
        val choices = ChimeChoices().set(reddit, false, distractions).set(chrome, true, distractions)
        val usage = listOf(AppUsage(reddit, "Reddit", 1L), AppUsage(chrome, "Chrome", 1L), AppUsage(maps, "Maps", 1L))

        val byPackage = ChimeAppsList.build(usage, emptySet(), choices, distractions) { null }.recent.associateBy { it.packageName }

        assertEquals(ChimeReason.UNTICKED, byPackage.getValue(reddit).reason)
        assertFalse(byPackage.getValue(reddit).chimed)
        assertEquals(ChimeReason.CHOSEN, byPackage.getValue(chrome).reason)
        assertTrue(byPackage.getValue(chrome).chimed)
        assertEquals(ChimeReason.NONE, byPackage.getValue(maps).reason)
    }

    @Test
    fun others_areChosenAppsOnThisPhone_notUsedThisWeek() {
        val usage = listOf(AppUsage(reddit, "Reddit", 1L))
        val choices = ChimeChoices().set(chrome, true, distractions)
        val installed = mapOf(instagram to "Instagram", chrome to "Chrome")

        val others = ChimeAppsList.build(usage, emptySet(), choices, distractions) { installed[it] }.others

        assertEquals(listOf(chrome, instagram), others.map { it.packageName })
    }

    @Test
    fun others_leaveOutDistractionAppsNotOnThisPhone() {
        // Reddit is flagged from another of the user's devices; this phone doesn't have it.
        val others = ChimeAppsList.build(emptyList(), emptySet(), ChimeChoices(), distractions) { null }.others

        assertTrue(others.isEmpty())
    }

    @Test
    fun others_keepAnAppTickedByHand_evenUninstalled_soItCanBeUnticked() {
        val choices = ChimeChoices(followDistractions = false).set(chrome, true, distractions)

        val others = ChimeAppsList.build(emptyList(), emptySet(), choices, distractions) { null }.others

        assertEquals(listOf(chrome), others.map { it.label })
    }

    @Test
    fun theHomeAppAndThisApp_areNeverOffered() {
        val usage = listOf(AppUsage(maps, "Maps", 1L), AppUsage(chrome, "Chrome", 1L))

        val rows = ChimeAppsList.build(usage, setOf(maps), ChimeChoices(), emptySet()) { null }.recent

        assertEquals(listOf(chrome), rows.map { it.packageName })
    }

    @Test
    fun chimedCount_countsOnlyAppsOnThisPhone_andTheHandTickedOnes() {
        val choices = ChimeChoices().set(chrome, true, distractions)

        assertEquals(2, ChimeAppsList.chimedCount(choices, distractions) { it == instagram })
    }
}
