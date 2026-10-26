package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerCategoryDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerRoleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingRulesTest {

    private val options = TrackerActivityOptionsResponse(
        activities = listOf(
            TrackerActivityDto(1, "Reddit time", roleId = 10, categoryId = 20),
            TrackerActivityDto(2, "Reading", roleId = 11, categoryId = null),
            TrackerActivityDto(3, "Scrolling", roleId = 10, categoryId = null)
        ),
        roles = listOf(TrackerRoleDto(10, "Leisure"), TrackerRoleDto(11, "Hobby"), TrackerRoleDto(12, "Phone app")),
        categories = listOf(TrackerCategoryDto(20, "Social", isDistraction = true)),
        phoneAppRoleId = 12,
        appCategoryLinks = listOf(AppCategoryLinkDto("Social", 10, 20), AppCategoryLinkDto("Game", 99, null))
    )

    @Test
    fun appCategory_isAndroidsConstantByName_andUndefinedIsNone() {
        assertEquals("Game", AppCategory.of(0))
        assertEquals("Social", AppCategory.of(4))
        assertEquals("Accessibility", AppCategory.of(8))
        assertNull(AppCategory.of(-1))
        assertNull(AppCategory.of(42))
    }

    @Test
    fun distractionChoice_isTheWiresTriState_bothWays() {
        assertNull(DistractionChoice.FOLLOW.wire)
        assertEquals(true, DistractionChoice.YES.wire)
        assertEquals(false, DistractionChoice.NO.wire)
        DistractionChoice.entries.forEach { assertEquals(it, DistractionChoice.of(it.wire)) }
    }

    @Test
    fun followMeaning_saysWhatFollowingResolvesTo() {
        assertEquals(FollowMeaning.NoCategory, FollowMeaning.of(null))
        assertEquals(FollowMeaning.Category("Social", true), FollowMeaning.of(TrackerCategoryDto(20, "Social", true)))
    }

    @Test
    fun theName_isTrimmedRequiredAndBounded() {
        assertEquals(MappingRules.NameCheck.Ok("Reddit"), MappingRules.checkName("  Reddit \n"))
        assertEquals(MappingRules.NameCheck.Blank, MappingRules.checkName("   "))
        assertEquals(MappingRules.NameCheck.Blank, MappingRules.checkName(null))
        assertEquals(MappingRules.NameCheck.Ok("a".repeat(100)), MappingRules.checkName("a".repeat(100)))
        assertEquals(MappingRules.NameCheck.TooLong, MappingRules.checkName("a".repeat(101)))
    }

    @Test
    fun defaults_takeTheLabel_andTheAppCategorysLink() {
        val social = MappingRules.linkFor("Social", options)

        assertEquals(MappingRules.Defaults("Reddit", 10, 20), MappingRules.defaults(" Reddit ", social, options))
    }

    @Test
    fun defaults_withoutALink_areThePhoneAppRole_andNoCategory() {
        assertEquals(MappingRules.Defaults("Maps", 12, null), MappingRules.defaults("Maps", null, options))
        assertNull(MappingRules.linkFor(null, options))
        assertNull(MappingRules.linkFor("Video", options))
    }

    /** A link to a role deleted since is not offered: the default falls back to "Phone app", never to an id the server 404s. */
    @Test
    fun defaults_skipALinkedRoleThatIsGone() {
        assertEquals(MappingRules.Defaults("Game", 12, null), MappingRules.defaults("Game", MappingRules.linkFor("Game", options), options))
    }

    /** Before the first quick-create of an older account there is no "Phone app" role yet: null lets the server make it. */
    @Test
    fun defaults_withNoPhoneAppRoleYet_leaveTheRoleToTheServer() {
        assertNull(MappingRules.defaults("Maps", null, options.copy(phoneAppRoleId = null)).roleId)
    }

    @Test
    fun defaults_cutAnOverlongLabelToTheColumn() {
        assertEquals(100, MappingRules.defaults("x".repeat(150), null, options).name.length)
    }

    @Test
    fun thePicker_filtersByTheLinksRoleAndCategory() {
        val activities = options.activities!!

        assertEquals(listOf(1L), MappingRules.filtered(activities, AppCategoryLinkDto("Social", 10, 20)).map { it.id })
        assertEquals(listOf(1L, 3L), MappingRules.filtered(activities, AppCategoryLinkDto("Social", 10, null)).map { it.id })
        assertEquals(listOf(1L), MappingRules.filtered(activities, AppCategoryLinkDto("Social", null, 20)).map { it.id })
        assertEquals(activities, MappingRules.filtered(activities, null))
    }
}
