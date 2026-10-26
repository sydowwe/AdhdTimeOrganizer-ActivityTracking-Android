package com.timeorganizer.usagetracker.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.timeorganizer.usagetracker.ignore.AppUsage
import com.timeorganizer.usagetracker.mapping.AppCategory

/**
 * What `PackageManager` says about other apps. The app sees only the packages its manifest's `<queries>` name — every
 * app with a launcher entry, and the home apps — so for anything else [label] and [icon] are `null` and
 * [hasLauncherEntry] is `false`.
 */
class InstalledApps(context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val labelCache = mutableMapOf<String, String?>()

    /**
     * The home screen and Recents are the launcher's activities, so the usage log counts them as an app in front. They
     * are the gaps between apps, not something the person used.
     */
    val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    /** The label as the launcher shows it; `null` when the package is gone or not visible to this app. */
    fun label(packageName: String): String? = labelCache.getOrPut(packageName) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun icon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun hasLauncherEntry(packageName: String): Boolean = packageManager.getLaunchIntentForPackage(packageName) != null

    /**
     * The category the app declares for itself (`ApplicationInfo.category`), by the server's name (`mapping/AppCategory`);
     * `null` when it declares none, is gone, or is not visible. The activity picker filters by it.
     */
    fun appCategory(packageName: String): String? = try {
        AppCategory.of(packageManager.getApplicationInfo(packageName, 0).category)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The apps with ≥ [minForegroundMs] in front in the [windowMs] before [nowMs] and a launcher entry, as the OS aggregates
     * them — the "recently used" list the "Ignored apps" screen and "Mapped apps"' "Add app" both pick from. Needs usage access.
     */
    fun recentlyUsed(context: Context, nowMs: Long, windowMs: Long, minForegroundMs: Long): List<AppUsage> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        return manager.queryAndAggregateUsageStats(nowMs - windowMs, nowMs).values
            .filter { it.totalTimeInForeground >= minForegroundMs }
            .filter { hasLauncherEntry(it.packageName) }
            .map { AppUsage(it.packageName, label(it.packageName) ?: it.packageName, it.totalTimeInForeground) }
    }
}
