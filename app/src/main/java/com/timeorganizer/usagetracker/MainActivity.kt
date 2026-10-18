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
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.LogoutRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import kotlinx.coroutines.GlobalScope
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.worker.UsageSyncWorker
import kotlinx.coroutines.Dispatchers
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

        if (!AuthPreferences(this).isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        prefs = SyncPreferences(this)
        authPrefs = AuthPreferences(this)

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
            enqueueSyncNow()
        }

        btnLogout.setOnClickListener {
            performLogout()
        }

        tvApiUrl.text = getString(
            R.string.backend_url_label,
            Constants.API_BASE_URL.take(30) + "…"
        )
    }

    override fun onResume() {
        super.onResume()
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
        val lastSyncMs = prefs.lastSyncTimeMillis
        tvLastSync.text = if (lastSyncMs == 0L) {
            getString(R.string.sync_never)
        } else {
            getString(R.string.last_sync_label, relativeTime(lastSyncMs))
        }

        val resultText = when (prefs.lastSyncResult) {
            SyncPreferences.RESULT_SUCCESS -> getString(R.string.sync_result_success)
            SyncPreferences.RESULT_FAILED -> getString(R.string.sync_result_failed)
            else -> getString(R.string.sync_result_pending)
        }
        tvSyncResult.text = getString(R.string.last_sync_result_label, resultText)
    }

    private fun checkBackendHealth() {
        tvBackendStatus.text = getString(R.string.backend_checking)
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    val response = RetrofitClient.apiService.healthCheck()
                    response.isSuccessful || response.code() in 200..499
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

    private fun enqueueSyncNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(request)
        Toast.makeText(this, getString(R.string.sync_enqueued), Toast.LENGTH_SHORT).show()
    }

    @Suppress("OPT_IN_USAGE")
    private fun performLogout() {
        btnLogout.isEnabled = false

        // Capture credentials before clearing — needed for the server-side call
        val savedToken = authPrefs.token
        val savedRefreshToken = authPrefs.refreshToken

        authPrefs.clearTokens()

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
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            hours < 24 -> "$hours hours ago"
            else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}
