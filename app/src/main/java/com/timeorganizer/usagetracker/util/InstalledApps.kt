package com.timeorganizer.usagetracker.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

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
}
