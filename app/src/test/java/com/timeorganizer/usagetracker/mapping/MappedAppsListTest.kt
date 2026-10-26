package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.ignore.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappedAppsListTest {

    private fun mapped(pkg: String, ruleId: Long, activityId: Long, label: String? = null) =
        AndroidTrackerRuleDto(pkg, label, AndroidRuleTarget.ACTIVITY, "Activity $activityId", ruleId = ruleId, activityId = activityId)

    private val rules = listOf(
        mapped("com.reddit", 1, activityId = 30, label = "Reddit"),
        mapped("com.insta", 2, activityId = 30, label = "Instagram"),
        mapped("com.tiktok", 3, activityId = 30, label = "TikTok"),
        mapped("com.x", 4, activityId = 30, label = "X"),
        mapped("com.kindle", 5, activityId = 40, label = "Kindle"),
        AndroidTrackerRuleDto("com.muted", "Muted", AndroidRuleTarget.IGNORED, null, ruleId = 6),
        // A cache from before prompt 27: no id to change it by.
        AndroidTrackerRuleDto("com.old", "Old", AndroidRuleTarget.ACTIVITY, "Thesis")
    )

    @Test
    fun theRows_areTheActivityRules_byLabel() {
        val rows = MappedAppsList.build(rules) { null }

        assertEquals(listOf("Instagram", "Kindle", "Reddit", "TikTok", "X"), rows.map { it.label })
    }

    /** The installed app's own name wins; an uninstalled one keeps the server's; with neither, the package. */
    @Test
    fun theLabel_isPackageManagersThenTheServersThenThePackage() {
        val rows = MappedAppsList.build(
            listOf(mapped("com.reddit", 1, 30, label = "Reddit (old)"), mapped("com.gone", 2, 31, label = "Gone app"), mapped("com.bare", 3, 32))
        ) { if (it == "com.reddit") "Reddit" else null }

        assertEquals(setOf("Reddit", "Gone app", "com.bare"), rows.map { it.label }.toSet())
    }

    @Test
    fun eachRow_namesTheOtherAppsOnItsActivity() {
        val rows = MappedAppsList.build(rules) { null }.associateBy { it.packageName }

        assertEquals(listOf("Instagram", "TikTok", "X"), rows.getValue("com.reddit").sharedWith)
        assertEquals(emptyList<String>(), rows.getValue("com.kindle").sharedWith)
    }

    @Test
    fun theFlagWarning_namesTwo_andCountsTheRest() {
        val rows = MappedAppsList.build(rules) { null }.associateBy { it.packageName }

        assertEquals(MappedAppsList.Sharers(listOf("Instagram", "TikTok"), more = 1), MappedAppsList.sharers(rows.getValue("com.reddit")))
        assertNull(MappedAppsList.sharers(rows.getValue("com.kindle")))
    }

    @Test
    fun addApp_offersNeitherMappedNorIgnoredApps() {
        assertEquals(
            setOf("com.reddit", "com.insta", "com.tiktok", "com.x", "com.kindle", "com.muted", "com.old"),
            MappedAppsList.decided(rules)
        )
    }

    @Test
    fun addApp_offersTheUndecided_busiestFirst() {
        val usage = listOf(
            AppUsage("com.reddit", "Reddit", 3_600_000),
            AppUsage("com.maps", "Maps", 120_000),
            AppUsage("com.launcher", "Launcher", 9_000_000),
            AppUsage("com.chess", "Chess", 900_000)
        )

        val offered = MappedAppsList.addable(usage, MappedAppsList.decided(rules), excluded = setOf("com.launcher"))

        assertEquals(listOf("com.chess", "com.maps"), offered.map { it.packageName })
    }

    private fun row(own: Boolean?, category: String?, categoryFlag: Boolean?, effective: Boolean) = MappedAppRow(
        ruleId = 1, packageName = "com.reddit", label = "Reddit", activityId = 30, activityName = "Scrolling",
        activityArchived = false, categoryName = category, ownDistraction = own, categoryDistraction = categoryFlag,
        isDistraction = effective, sharedWith = emptyList()
    )

    @Test
    fun theFlagText_isTheOwnFlag_elseWhatFollowingComesTo() {
        assertEquals(MappedAppsList.FlagText.OWN_YES, MappedAppsList.flagText(row(true, "Rest", false, true)))
        assertEquals(MappedAppsList.FlagText.OWN_NO, MappedAppsList.flagText(row(false, "Social", true, false)))
        assertEquals(MappedAppsList.FlagText.FOLLOW_YES, MappedAppsList.flagText(row(null, "Social", true, true)))
        assertEquals(MappedAppsList.FlagText.FOLLOW_NO, MappedAppsList.flagText(row(null, "Rest", false, false)))
        assertEquals(MappedAppsList.FlagText.FOLLOW_NONE, MappedAppsList.flagText(row(null, null, null, false)))
    }

    @Test
    fun followMeaning_isTheCategorysFlag_orNoCategory() {
        assertEquals(FollowMeaning.Category("Social", true), MappedAppsList.followMeaning(row(false, "Social", true, false)))
        assertEquals(FollowMeaning.NoCategory, MappedAppsList.followMeaning(row(true, null, null, true)))
    }
}
