package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerCategoryDto

/**
 * **Pure.** The app category an app declares for itself (`ApplicationInfo.category`), by the server's member name.
 * Digital Wellbeing's own categories can't be read by other apps; this is the public signal closest to them. Many apps
 * declare nothing (`CATEGORY_UNDEFINED`, -1): no category, and the picker opens unfiltered.
 */
object AppCategory {
    /** In `ApplicationInfo.CATEGORY_*` order: the constant is the index. Never reorder — the server stores the names. */
    val NAMES = listOf("Game", "Audio", "Video", "Image", "Social", "News", "Maps", "Productivity", "Accessibility")

    fun of(androidCategory: Int): String? = NAMES.getOrNull(androidCategory)
}

/** The distraction flag as the dialog offers it: follow the category, or the activity's own yes / no. */
enum class DistractionChoice {
    FOLLOW, YES, NO;

    /** The wire's tri-state: `null` follows the category. */
    val wire: Boolean?
        get() = when (this) {
            FOLLOW -> null
            YES -> true
            NO -> false
        }

    companion object {
        fun of(wire: Boolean?): DistractionChoice = when (wire) {
            null -> FOLLOW
            true -> YES
            false -> NO
        }
    }
}

/** What "follow category" resolves to, so the dialog can say it ("Follow Social — a distraction"). */
sealed interface FollowMeaning {
    /** No category: following it means "not a distraction". */
    data object NoCategory : FollowMeaning

    data class Category(val name: String, val isDistraction: Boolean) : FollowMeaning

    companion object {
        fun of(category: TrackerCategoryDto?): FollowMeaning =
            if (category == null) NoCategory else Category(category.name, category.isDistraction)
    }
}

/** **Pure.** The "+ New activity" dialog's rules, and the picker's filter. */
object MappingRules {

    /** The server's `EntityFieldLengths.Name`. */
    const val MAX_NAME_LENGTH = 100

    /** "Also add this week's time": today and the six days before it — the server's `AndroidRuleBackfill.MaxDays`. */
    const val WEEK_BACKFILL_DAYS = 6

    sealed interface NameCheck {
        data class Ok(val name: String) : NameCheck
        data object Blank : NameCheck
        data object TooLong : NameCheck
    }

    /** Trimmed, required and within the column — the server trims the same way and refuses the rest with a 400. */
    fun checkName(raw: String?): NameCheck {
        val name = raw?.trim().orEmpty()
        return when {
            name.isEmpty() -> NameCheck.Blank
            name.length > MAX_NAME_LENGTH -> NameCheck.TooLong
            else -> NameCheck.Ok(name)
        }
    }

    /** What the dialog opens with. [roleId] `null` = the server's "Phone app" role, made on the first create. */
    data class Defaults(val name: String, val roleId: Long?, val categoryId: Long?)

    /**
     * The name is the app's label; the role and category are the app category's link where it has one (a link to a role
     * or category that no longer exists is not offered), else the "Phone app" role and no category.
     */
    fun defaults(appLabel: String?, link: AppCategoryLinkDto?, options: TrackerActivityOptionsResponse): Defaults {
        val roles = options.roles.orEmpty().mapTo(mutableSetOf()) { it.id }
        val categories = options.categories.orEmpty().mapTo(mutableSetOf()) { it.id }
        val linkedRole = link?.roleId?.takeIf { it in roles }
        val linkedCategory = link?.categoryId?.takeIf { it in categories }
        return Defaults(
            name = appLabel?.trim().orEmpty().take(MAX_NAME_LENGTH),
            roleId = linkedRole ?: options.phoneAppRoleId,
            categoryId = linkedCategory
        )
    }

    /** The link for an app of [appCategory]; `null` when the app declares none or the category is unlinked. */
    fun linkFor(appCategory: String?, options: TrackerActivityOptionsResponse): AppCategoryLinkDto? =
        appCategory?.let { category -> options.appCategoryLinks.orEmpty().firstOrNull { it.appCategory == category } }

    /**
     * The picker's list for an app: the activities its category's link points at (role and category both, where the link
     * sets both), by name. Empty when nothing fits — the picker then shows everything rather than an empty list.
     */
    fun filtered(activities: List<TrackerActivityDto>, link: AppCategoryLinkDto?): List<TrackerActivityDto> {
        if (link == null) return activities
        return activities.filter { activity ->
            (link.roleId == null || activity.roleId == link.roleId) &&
                (link.categoryId == null || activity.categoryId == link.categoryId)
        }
    }
}
