package com.timeorganizer.usagetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.timeorganizer.usagetracker.auth.SignOutReason
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.LogoutRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.sync.SyncReport
import com.timeorganizer.usagetracker.sync.SyncState
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SyncPreferences
    private lateinit var authPrefs: AuthPreferences

    // Permission card
    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnGrantPermission: Button

    // Battery card
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnExcludeBattery: Button

    // Sync status card
    private lateinit var tvLastSync: TextView
    private lateinit var tvSyncResult: TextView

    // Backend card
    private lateinit var tvBackendStatus: TextView
    private lateinit var tvApiUrl: TextView

    // Actions
    private lateinit var btnSyncNow: Button
    private lateinit var btnLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authPrefs = AuthPreferences.get(this)
        if (!authPrefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        prefs = SyncPreferences(this)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnExcludeBattery = findViewById(R.id.btnExcludeBattery)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvSyncResult = findViewById(R.id.tvSyncResult)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        tvApiUrl = findViewById(R.id.tvApiUrl)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        btnLogout = findViewById(R.id.btnLogout)

        btnGrantPermission.setOnClickListener {
            PermissionHelper.openUsageAccessSettings(this)
        }

        btnExcludeBattery.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        btnSyncNow.setOnClickListener {
            TimeOrganizerApp.enqueueSyncNow(this)
            Toast.makeText(this, getString(R.string.sync_enqueued), Toast.LENGTH_SHORT).show()
        }

        btnLogout.setOnClickListener {
            performLogout()
        }

        tvApiUrl.text = getString(R.string.backend_url_label, Constants.SERVER_ROOT)

        // Refresh the card when a "Sync Now" run finishes, not only on the next resume.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(Constants.SYNC_NOW_WORK_NAME)
            .observe(this) { updateSyncStatusCard() }
    }

    override fun onResume() {
        super.onResume()
        if (!authPrefs.isLoggedIn) {
            // A refresh refused while the app was in the background.
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // A force-stop cancels WorkManager's schedule; opening the app is what restores it.
        TimeOrganizerApp.enqueuePeriodicSync(this)
        updatePermissionCard()
        updateBatteryCard()
        updateSyncStatusCard()
        checkBackendHealth()
    }

    private fun updatePermissionCard() {
        val granted = PermissionHelper.hasUsageStatsPermission(this)
        tvPermissionStatus.text = if (granted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_denied)
        }
        btnGrantPermission.isEnabled = !granted
    }

    private fun updateBatteryCard() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        tvBatteryStatus.text = if (isIgnoring) {
            getString(R.string.battery_not_optimized)
        } else {
            getString(R.string.battery_optimized)
        }
        btnExcludeBattery.isEnabled = !isIgnoring
    }

    private fun updateSyncStatusCard() {
        val report = prefs.lastReport
        tvLastSync.text = if (report.lastSentAtMs == 0L) {
            getString(R.string.sync_never)
        } else {
            getString(R.string.last_sync_label, relativeTime(report.lastSentAtMs))
        }
        tvSyncResult.text = describe(report)
    }

    private fun describe(report: SyncReport): String {
        val lines = mutableListOf(
            when (report.state) {
                SyncState.NEVER -> getString(R.string.sync_state_never)
                SyncState.SENT -> getString(R.string.sync_state_sent, report.sent.accepted, report.sent.duplicates)
                SyncState.UP_TO_DATE -> getString(R.string.sync_state_up_to_date)
                SyncState.WAITING -> getString(R.string.sync_state_waiting)
                SyncState.SIGNED_OUT -> getString(R.string.sync_state_signed_out)
                SyncState.NO_USAGE_ACCESS -> getString(R.string.sync_state_no_usage_access)
            }
        )
        if (report.pending > 0) lines += getString(R.string.sync_pending, report.pending)
        if (report.sent.dropped > 0) lines += getString(R.string.sync_dropped, report.sent.dropped)
        if (report.rejected > 0) lines += getString(R.string.sync_rejected, report.rejected)
        if (report.clockAheadMs > Constants.CLOCK_AHEAD_WARNING_MS) {
            lines += getString(R.string.sync_clock_ahead, TimeUnit.MILLISECONDS.toMinutes(report.clockAheadMs))
        }
        return lines.joinToString("\n")
    }

    private fun checkBackendHealth() {
        tvBackendStatus.text = getString(R.string.backend_checking)
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.authApiService.health(Constants.HEALTH_URL).isSuccessful
                } catch (_: Exception) {
                    false
                }
            }
            tvBackendStatus.text = if (reachable) {
                getString(R.string.backend_connected)
            } else {
                getString(R.string.backend_unreachable)
            }
        }
    }

    @Suppress("OPT_IN_USAGE")
    private fun performLogout() {
        btnLogout.isEnabled = false

        // Capture credentials before clearing — needed for the server-side call
        val savedToken = authPrefs.token
        val savedRefreshToken = authPrefs.refreshToken

        authPrefs.clearTokens(SignOutReason.USER)

        // Fire-and-forget on GlobalScope so it survives this Activity finishing
        if (savedToken != null && savedRefreshToken != null) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    RetrofitClient.authApiService.logout(
                        authorization = "Bearer $savedToken",
                        request = LogoutRequest(savedRefreshToken)
                    )
                } catch (_: Exception) {
                    // Best effort — tokens are already cleared locally
                }
            }
        }

        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun relativeTime(timestampMs: Long): String {
        val diffMs = System.currentTimeMillis() - timestampMs
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        return when {
            minutes < 1 -> getString(R.string.time_just_now)
            minutes < 60 -> getString(R.string.time_minutes_ago, minutes)
            hours < 24 -> getString(R.string.time_hours_ago, hours)
            else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}
