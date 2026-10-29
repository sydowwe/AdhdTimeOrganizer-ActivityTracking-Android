package com.timeorganizer.usagetracker

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.timeorganizer.usagetracker.breaks.BreakMonitor
import com.timeorganizer.usagetracker.breaks.BreakPolicy
import com.timeorganizer.usagetracker.breaks.BreakPreferences
import com.timeorganizer.usagetracker.budget.BudgetMonitor
import com.timeorganizer.usagetracker.budget.BudgetPreferences
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.capture.CaptureActivity
import com.timeorganizer.usagetracker.capture.CaptureDatabase
import com.timeorganizer.usagetracker.capture.CaptureTileService
import com.timeorganizer.usagetracker.chime.ChimeAppsActivity
import com.timeorganizer.usagetracker.chime.ChimeAppsList
import com.timeorganizer.usagetracker.chime.ChimeMonitor
import com.timeorganizer.usagetracker.chime.ChimePreferences
import com.timeorganizer.usagetracker.chime.ChimeSchedule
import com.timeorganizer.usagetracker.drift.DriftMonitor
import com.timeorganizer.usagetracker.drift.DriftPreferences
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.ignore.IgnoredAppsActivity
import com.timeorganizer.usagetracker.loops.LoopAppsActivity
import com.timeorganizer.usagetracker.loops.LoopMonitor
import com.timeorganizer.usagetracker.loops.LoopPolicy
import com.timeorganizer.usagetracker.loops.LoopPreferences
import com.timeorganizer.usagetracker.mapping.MappedAppsActivity
import com.timeorganizer.usagetracker.now.KeptDistractions
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.pause.PauseLength
import com.timeorganizer.usagetracker.pause.PauseNotifier
import com.timeorganizer.usagetracker.pause.Pauses
import com.timeorganizer.usagetracker.ramp.RampBlocks
import com.timeorganizer.usagetracker.ramp.StartRampMonitor
import com.timeorganizer.usagetracker.ramp.StartRampPreferences
import com.timeorganizer.usagetracker.review.ReviewActivity
import com.timeorganizer.usagetracker.review.ReviewPreferences
import com.timeorganizer.usagetracker.sync.SyncEngine
import com.timeorganizer.usagetracker.sync.SyncReport
import com.timeorganizer.usagetracker.sync.SyncState
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.InstalledApps
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
        findViewById<View>(R.id.btnMappedApps).setOnClickListener { startActivity(MappedAppsActivity.intent(this)) }

        setUpPauseCard()
        setUpCaptureCard()
        setUpBreakCard()
        setUpRampCard()
        setUpDriftCard()
        setUpBudgetCard()
        setUpChimeCard()
        setUpLoopCard()

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
        updatePauseCard()
        updateCaptureCard()
        updateIgnoredCard()
        updateBudgetCard()
        updateChimeCard()
        updateLoopCard()
        checkBackendHealth()
    }

    /** The break nudge's switch and length; each change is decided on at once, so switching it off clears a nudge. */
    private fun setUpBreakCard() {
        val breakPrefs = BreakPreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swBreakNudge)
        switch.isChecked = breakPrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            breakPrefs.enabled = checked
            BreakMonitor.kick(this)
        }

        val chips = findViewById<ChipGroup>(R.id.chipsBreakLength)
        for (minutes in BreakPolicy.BREAK_MINUTES) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(R.string.break_card_minutes, minutes)
                isCheckable = true
                isChecked = minutes == breakPrefs.breakMinutes
            })
        }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            val minutes = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as Int } ?: return@setOnCheckedStateChangeListener
            breakPrefs.breakMinutes = minutes
        }
    }

    /** The start ramp's switch and which blocks it asks about; each change is decided on at once. */
    private fun setUpRampCard() {
        val rampPrefs = StartRampPreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swStartRamp)
        switch.isChecked = rampPrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            rampPrefs.enabled = checked
            StartRampMonitor.kick(this)
        }

        val chips = findViewById<ChipGroup>(R.id.chipsRampBlocks)
        val labels = mapOf(
            RampBlocks.ALL to R.string.ramp_card_blocks_all,
            RampBlocks.NOT_OPTIONAL to R.string.ramp_card_blocks_not_optional,
            RampBlocks.CRITICAL to R.string.ramp_card_blocks_critical
        )
        for (blocks in RampBlocks.entries) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = blocks
                text = getString(labels.getValue(blocks))
                isCheckable = true
                isChecked = blocks == rampPrefs.blocks
            })
        }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            val blocks = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as RampBlocks } ?: return@setOnCheckedStateChangeListener
            rampPrefs.blocks = blocks
            StartRampMonitor.kick(this)
        }
    }

    /** The drift nudge's switch, decided on at once: turning it off withdraws a nudge and lets the watcher go. */
    private fun setUpDriftCard() {
        val driftPrefs = DriftPreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swDriftNudge)
        switch.isChecked = driftPrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            driftPrefs.enabled = checked
            DriftMonitor.kick(this)
        }
    }

    /** Daily limits' switch, decided on at once: turning it off lets the watcher go. */
    private fun setUpBudgetCard() {
        val budgetPrefs = BudgetPreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swDailyBudgets)
        switch.isChecked = budgetPrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            budgetPrefs.enabled = checked
            BudgetMonitor.kick(this)
        }
    }

    /** Each budget a phone app counts toward, with the phone's count of today: from the last figures and the usage log. */
    private fun updateBudgetCard() {
        val view = findViewById<TextView>(R.id.tvBudgetLines)
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { BudgetMonitor.lines(applicationContext) }
            view.text = if (lines.isEmpty()) {
                getString(R.string.budget_card_none)
            } else {
                lines.joinToString("\n") {
                    getString(R.string.budget_card_line, it.targetName, ReviewText.duration(it.totalMs / 1000), ReviewText.duration(it.limitMs / 1000))
                }
            }
        }
    }

    /** The time chime's switch and interval; the apps are on their own screen, the vibration on the channel's system page. */
    private fun setUpChimeCard() {
        val chimePrefs = ChimePreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swTimeChime)
        switch.isChecked = chimePrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            chimePrefs.enabled = checked
            ChimeMonitor.kick(this)
            updateChimeCard()
        }

        val chips = findViewById<ChipGroup>(R.id.chipsChimeInterval)
        for (minutes in ChimeSchedule.INTERVAL_MINUTES) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(R.string.break_card_minutes, minutes)
                isCheckable = true
                isChecked = minutes == chimePrefs.intervalMinutes
            })
        }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            val minutes = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as Int } ?: return@setOnCheckedStateChangeListener
            chimePrefs.intervalMinutes = minutes
            ChimeMonitor.kick(this)
        }

        findViewById<View>(R.id.btnChimeApps).setOnClickListener { startActivity(ChimeAppsActivity.intent(this)) }
        findViewById<View>(R.id.btnChimeVibration).setOnClickListener {
            Notifications.openChannelSettings(this, Notifications.CHANNEL_TIME_CHIME)
        }
        findViewById<View>(R.id.btnChimeHideWatch).setOnClickListener { openWatchChannel() }
    }

    private fun updateChimeCard() {
        val chimePrefs = ChimePreferences(this)
        val tvApps = findViewById<TextView>(R.id.tvChimeApps)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val installed = InstalledApps(applicationContext)
                ChimeAppsList.chimedCount(chimePrefs.choices, KeptDistractions.get(applicationContext)) { installed.label(it) != null }
            }
            tvApps.text = if (count == 0) {
                getString(R.string.chime_count_none)
            } else {
                resources.getQuantityString(R.plurals.chime_count, count, count)
            }
        }
    }

    /** The loop check's switch and the away timer's length; the apps that don't count are on their own screen. */
    private fun setUpLoopCard() {
        val loopPrefs = LoopPreferences(this)
        val switch = findViewById<MaterialSwitch>(R.id.swLoopCheck)
        switch.isChecked = loopPrefs.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            loopPrefs.enabled = checked
            LoopMonitor.kick(this)
        }

        val chips = findViewById<ChipGroup>(R.id.chipsLoopAway)
        for (minutes in LoopPolicy.AWAY_MINUTES) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(R.string.break_card_minutes, minutes)
                isCheckable = true
                isChecked = minutes == loopPrefs.awayMinutes
            })
        }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            val minutes = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as Int } ?: return@setOnCheckedStateChangeListener
            loopPrefs.awayMinutes = minutes
        }

        findViewById<View>(R.id.btnLoopApps).setOnClickListener { startActivity(LoopAppsActivity.intent(this)) }
        findViewById<View>(R.id.btnLoopHideWatch).setOnClickListener { openWatchChannel() }
    }

    /**
     * The watcher's channel page: turn it off, or minimise it — the app can't (see `Notifications.CHANNEL_FOREGROUND_WATCH`).
     * The watch runs on either way. On the two cards whose features keep it on all day.
     */
    private fun openWatchChannel() = Notifications.openChannelSettings(this, Notifications.CHANNEL_FOREGROUND_WATCH)

    private fun updateLoopCard() {
        val tvToday = findViewById<TextView>(R.id.tvLoopToday)
        lifecycleScope.launch {
            val today = withContext(Dispatchers.IO) { LoopMonitor.today(applicationContext) }
            tvToday.text = when {
                today.awayUntilMs != null -> getString(
                    R.string.loop_away_until, android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(Date(today.awayUntilMs))
                )
                today.loops == 0 -> getString(R.string.loop_count_none)
                else -> resources.getQuantityString(R.plurals.loop_count_today, today.loops, today.loops)
            }
        }
    }

    /**
     * Pause tracking (prompt 17): Pause… offers the four lengths, Resume ends the pause. "Until morning" names its time — the
     * server's morning as last heard, asked again each time the card is shown ([updatePauseCard]).
     */
    private fun setUpPauseCard() {
        findViewById<View>(R.id.btnPause).setOnClickListener {
            lifecycleScope.launch {
                val morningMs = withContext(Dispatchers.IO) { Pauses.morning(applicationContext, ask = false) }
                val morning = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(Date(morningMs))
                val lengths = PauseLength.entries
                val labels = lengths.map {
                    when (it) {
                        PauseLength.FIFTEEN_MINUTES -> getString(R.string.pause_15_minutes)
                        PauseLength.ONE_HOUR -> getString(R.string.pause_1_hour)
                        PauseLength.UNTIL_MORNING -> getString(R.string.pause_until_morning, morning)
                        PauseLength.UNTIL_RESUMED -> getString(R.string.pause_until_i_resume)
                    }
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.pause_dialog_title)
                    .setItems(labels) { _, which -> changePause { Pauses.start(applicationContext, lengths[which]) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        findViewById<View>(R.id.btnPauseResume).setOnClickListener { changePause { Pauses.resume(applicationContext) } }
    }

    private fun changePause(change: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { change() }
            updatePauseCard()
            updateBudgetCard()
        }
    }

    private fun updatePauseCard() {
        val tvState = findViewById<TextView>(R.id.tvPauseState)
        val btnPause = findViewById<View>(R.id.btnPause)
        val btnResume = findViewById<View>(R.id.btnPauseResume)
        lifecycleScope.launch {
            val active = withContext(Dispatchers.IO) { Pauses.active(applicationContext) }
            tvState.text = if (active == null) {
                getString(R.string.pause_card_tracking)
            } else {
                getString(R.string.pause_card_paused, PauseNotifier.until(this@MainActivity, active))
            }
            btnPause.visibility = if (active == null) View.VISIBLE else View.GONE
            btnResume.visibility = if (active == null) View.GONE else View.VISIBLE
            // The morning "Until morning" offers: asked now, so the dialog has it without waiting on the network.
            if (active == null) withContext(Dispatchers.IO) { Pauses.morning(applicationContext) }
        }
    }

    /** Quick capture: the field itself, and on Android 13+ the system's one-tap "add this tile" request. */
    private fun setUpCaptureCard() {
        findViewById<View>(R.id.btnCaptureNow).setOnClickListener { startActivity(CaptureActivity.intent(this)) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val addTile = findViewById<View>(R.id.btnCaptureAddTile)
        addTile.visibility = View.VISIBLE
        addTile.setOnClickListener {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, CaptureTileService::class.java),
                getString(R.string.capture_tile_label),
                Icon.createWithResource(this, R.drawable.ic_stat_capture),
                mainExecutor
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                Toast.makeText(
                    this, if (added) R.string.capture_tile_added else R.string.capture_tile_not_added, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateCaptureCard() {
        val tvWaiting = findViewById<TextView>(R.id.tvCaptureWaiting)
        lifecycleScope.launch {
            val (waiting, refused) = withContext(Dispatchers.IO) {
                val dao = CaptureDatabase.get(applicationContext).captureDao()
                dao.count() to dao.refusedCount()
            }
            tvWaiting.text = when {
                waiting == 0 -> getString(R.string.capture_card_none)
                refused == 0 -> resources.getQuantityString(R.plurals.capture_card_waiting, waiting, waiting)
                else -> resources.getQuantityString(R.plurals.capture_card_waiting, waiting, waiting) + " " +
                    resources.getQuantityString(R.plurals.capture_card_refused, refused, refused)
            }
        }
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
