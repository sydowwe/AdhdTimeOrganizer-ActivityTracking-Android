package com.timeorganizer.usagetracker

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.ignore.IgnoredAppsActivity
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.review.ReviewActivity
import com.timeorganizer.usagetracker.review.ReviewPreferences
import com.timeorganizer.usagetracker.sync.SyncEngine
import com.timeorganizer.usagetracker.sync.SyncReport
import com.timeorganizer.usagetracker.sync.SyncState
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import com.timeorganizer.usagetracker.data.local.outbox.OutboxDatabase
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

    // Usage access banner
    private lateinit var bannerUsageAccess: View
    private lateinit var btnGrantPermission: Button

    // Setup banner: battery exemption and notifications, both set on the settings screen
    private lateinit var bannerSetup: View
    private lateinit var tvSetupBanner: TextView

    // Evening review card
    private lateinit var swEveningReview: MaterialSwitch
    private lateinit var btnReviewNow: Button

    // Ignored apps card
    private lateinit var tvIgnoredCount: TextView

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
        applySystemBarInsets(findViewById(R.id.root))

        prefs = SyncPreferences(this)

        bannerUsageAccess = findViewById(R.id.bannerUsageAccess)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        bannerSetup = findViewById(R.id.bannerSetup)
        tvSetupBanner = findViewById(R.id.tvSetupBanner)
        swEveningReview = findViewById(R.id.swEveningReview)
        btnReviewNow = findViewById(R.id.btnReviewNow)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvSyncResult = findViewById(R.id.tvSyncResult)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        tvApiUrl = findViewById(R.id.tvApiUrl)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        btnLogout = findViewById(R.id.btnLogout)

        btnGrantPermission.setOnClickListener {
            PermissionHelper.openUsageAccessSettings(this)
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(SettingsActivity.intent(this)) }
        bannerSetup.setOnClickListener { startActivity(SettingsActivity.intent(this)) }
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener { startActivity(SettingsActivity.intent(this)) }

        val reviewPrefs = ReviewPreferences(this)
        swEveningReview.isChecked = reviewPrefs.enabled
        swEveningReview.setOnCheckedChangeListener { _, checked -> reviewPrefs.enabled = checked }
        btnReviewNow.setOnClickListener { startActivity(ReviewActivity.intent(this)) }

        tvIgnoredCount = findViewById(R.id.tvIgnoredCount)
        findViewById<View>(R.id.btnIgnoredApps).setOnClickListener { startActivity(IgnoredAppsActivity.intent(this)) }

        findViewById<Button>(R.id.btnRefreshStatus).setOnClickListener {
            tvLastSync.text = ""
            tvSyncResult.text = ""
            // Sessions reach the outbox only when the usage log is read, so read it first.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SyncEngine(applicationContext).recordPending() }
                updateSyncStatusCard()
            }
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

        // The first open after install goes straight to the settings that are set once.
        if (savedInstanceState == null && SettingsActivity.takeFirstRun(this)) {
            startActivity(SettingsActivity.intent(this))
        }
    }

    // A periodic run can finish while the screen is open; its report would otherwise wait for the next resume.
    private var reportListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onStart() {
        super.onStart()
        if (::prefs.isInitialized) reportListener = prefs.observeReport { updateSyncStatusCard() }
    }

    override fun onStop() {
        reportListener?.let(prefs::stopObserving)
        reportListener = null
        super.onStop()
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
        updateUsageAccessBanner()
        updateSetupBanner()
        updateSyncStatusCard()
        updateIgnoredCard()
        checkBackendHealth()
    }

    private fun updateIgnoredCard() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                IgnoreStore.get(applicationContext).state().ignored(System.currentTimeMillis()).size
            }
            tvIgnoredCount.text = if (count == 0) {
                getString(R.string.ignored_count_none)
            } else {
                resources.getQuantityString(R.plurals.ignored_count, count, count)
            }
        }
    }

    // Without usage access nothing is recorded, so the banner stays until it is granted.
    private fun updateUsageAccessBanner() {
        val granted = PermissionHelper.hasUsageStatsPermission(this)
        bannerUsageAccess.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun updateSetupBanner() {
        val missing = buildList {
            if (!PermissionHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
                add(getString(R.string.setup_banner_battery))
            }
            if (!Notifications.canPost(this@MainActivity, Notifications.CHANNEL_SYNC_PROBLEMS)) {
                add(getString(R.string.setup_banner_notifications))
            }
        }
        tvSetupBanner.text = missing.joinToString("\n")
        bannerSetup.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSyncStatusCard() {
        val report = prefs.lastReport
        tvLastSync.text = if (report.lastSentAtMs == 0L) {
            getString(R.string.sync_never)
        } else {
            getString(R.string.last_sync_label, relativeTime(report.lastSentAtMs))
        }
        tvSyncResult.text = describe(report, report.pending)
        // The report's count is from the end of the last run; the outbox is what a sync would send now.
        lifecycleScope.launch {
            val unsent = withContext(Dispatchers.IO) { OutboxDatabase.get(applicationContext).outboxDao().unsentCount() }
            tvSyncResult.text = describe(report, unsent)
        }
    }

    private fun describe(report: SyncReport, pending: Int): String {
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
        lines += getString(R.string.sync_pending, pending)
        if (report.sent.dropped > 0) lines += getString(R.string.sync_dropped, report.sent.dropped)
        if (report.sent.ignored > 0) lines += getString(R.string.sync_ignored, report.sent.ignored)
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
