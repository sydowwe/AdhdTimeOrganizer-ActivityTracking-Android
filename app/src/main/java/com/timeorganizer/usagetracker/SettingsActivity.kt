package com.timeorganizer.usagetracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets

/**
 * The things set once: usage access, the battery-optimization exemption and the notification permission. Opened by
 * the gear on the status screen, and by itself the first time the status screen opens after install. The status
 * screen shows a red banner while usage access is off and an amber one while either of the others is missing.
 *
 * A granted card shows a check and "Manage", which opens the system's page for it: an app cannot give any of these
 * up itself, so there is no "revoke" here.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var usageAccess: PermissionCard
    private lateinit var battery: PermissionCard
    private lateinit var notifications: PermissionCard

    // Once the system has stopped showing its dialog, a denial answers at once; settings are the only way left.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Notifications.openSettings(this)
        }
        updateCards()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(R.id.root))

        usageAccess = PermissionCard(
            findViewById(R.id.ivUsageAccessCheck), findViewById(R.id.tvUsageAccessStatus),
            findViewById(R.id.btnGrantUsageAccess), findViewById(R.id.btnManageUsageAccess)
        )
        battery = PermissionCard(
            findViewById(R.id.ivBatteryCheck), findViewById(R.id.tvBatteryStatus),
            findViewById(R.id.btnExcludeBattery), findViewById(R.id.btnManageBattery)
        )
        notifications = PermissionCard(
            findViewById(R.id.ivNotificationCheck), findViewById(R.id.tvNotificationStatus),
            findViewById(R.id.btnAllowNotifications), findViewById(R.id.btnManageNotifications)
        )

        usageAccess.grant.setOnClickListener { PermissionHelper.openUsageAccessSettings(this) }
        usageAccess.manage.setOnClickListener { PermissionHelper.openUsageAccessSettings(this) }

        battery.grant.setOnClickListener { PermissionHelper.openBatteryOptimizationSettings(this) }
        battery.manage.setOnClickListener { PermissionHelper.openBatteryOptimizationList(this) }

        notifications.grant.setOnClickListener {
            if (Notifications.hasPermission(this)) {
                // Granted, but the app's notifications were turned off in settings.
                Notifications.openSettings(this)
            } else {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        notifications.manage.setOnClickListener { Notifications.openSettings(this) }

        findViewById<View>(R.id.btnSettingsDone).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        updateCards()
    }

    private fun updateCards() {
        usageAccess.show(
            PermissionHelper.hasUsageStatsPermission(this),
            getString(R.string.usage_access_on), getString(R.string.usage_access_off)
        )
        battery.show(
            PermissionHelper.isIgnoringBatteryOptimizations(this),
            getString(R.string.battery_not_optimized), getString(R.string.battery_optimized)
        )
        notifications.show(
            Notifications.canPost(this, Notifications.CHANNEL_SYNC_PROBLEMS),
            getString(R.string.notifications_on), getString(R.string.notifications_off)
        )
    }

    private class PermissionCard(val check: View, val status: TextView, val grant: View, val manage: View) {
        fun show(granted: Boolean, grantedText: String, missingText: String) {
            check.visibility = if (granted) View.VISIBLE else View.GONE
            status.text = if (granted) grantedText else missingText
            grant.visibility = if (granted) View.GONE else View.VISIBLE
            manage.visibility = if (granted) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val PREFS = "settings_prefs"
        private const val KEY_SHOWN = "first_run_shown"

        /** True once per install: the first time it is asked, which the status screen does on its first open. */
        fun takeFirstRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SHOWN, false)) return false
            prefs.edit().putBoolean(KEY_SHOWN, true).apply()
            return true
        }

        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}
