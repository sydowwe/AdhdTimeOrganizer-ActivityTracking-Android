package com.timeorganizer.usagetracker.mapping

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeorganizer.usagetracker.LoginActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.RepointTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.SetDistractionRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.ignore.AppUsage
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.ignore.IgnoreSync
import com.timeorganizer.usagetracker.ignore.IgnoredAppsList
import com.timeorganizer.usagetracker.review.ReviewPreferences
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.review.RuleOutcome
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Mapped apps": every app the user's rules map to an activity, one row each — change the activity, unlink, or set the
 * activity's distraction flag. "Add app" maps a recently used app before it ever shows up as unmapped; "App categories"
 * edits the picker's filter. Ignored apps are not here: the "Ignored apps" screen owns them.
 *
 * The rows are the server's rules (`GET rules`, the cache the sync keeps), shown from the cache first and again once the
 * server answered. A change or an unlink applies from now on: time already in History stays with the old activity, and
 * every confirmation says so. Nothing here is logged: package, app and activity names are personal.
 */
class MappedAppsActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var list: RecyclerView

    private lateinit var installedApps: InstalledApps
    private lateinit var ignoreSync: IgnoreSync
    private lateinit var store: IgnoreStore
    private val adapter = RowsAdapter()

    /** The picker's options, read on first use and kept until something says they are stale. */
    private val options = MappingOptions()
    private val picker by lazy { ActivityPicker(this, options) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthPreferences.get(this).isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_mapped_apps)
        applySystemBarInsets(findViewById(R.id.root))
        progress = findViewById(R.id.progressMapped)
        tvMessage = findViewById(R.id.tvMappedMessage)
        list = findViewById(R.id.mappedList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.btnMappedDone).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddApp).setOnClickListener { addApp() }
        findViewById<View>(R.id.btnAppCategories).setOnClickListener { AppCategoryLinksDialog(this, options).show() }

        installedApps = InstalledApps(this)
        ignoreSync = IgnoreSync(this)
        store = IgnoreStore.get(this)

        render()
        reload()
    }

    /** The cached rules may be a sync old; the server's say what another device or the web changed meanwhile. */
    private fun reload() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                ignoreSync.refreshRules()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cached rules stand.
            }
            progress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val rows = MappedAppsList.build(store.state().rules) { installedApps.label(it) }
        adapter.submit(rows)
        tvMessage.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        tvMessage.setText(R.string.mapped_nothing)
    }

    // --- Change -----------------------------------------------------------------------------------------------------

    private fun change(row: MappedAppRow) {
        lifecycleScope.launch {
            val category = withContext(Dispatchers.IO) { installedApps.appCategory(row.packageName) }
            // No package to map: a new activity is only made here, and the rule is re-pointed to it below.
            picker.pick(row.label, category, mapPackage = null, backfillDays = null) { choice ->
                if (choice.id == row.activityId) return@pick
                MaterialAlertDialogBuilder(this@MappedAppsActivity)
                    .setTitle(getString(R.string.change_confirm_title, row.label, choice.name))
                    .setMessage(R.string.history_stays)
                    .setPositiveButton(R.string.mapped_change) { _, _ -> perform { repoint(row, choice) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private suspend fun repoint(row: MappedAppRow, choice: PickedActivity): String {
        val outcome = MappingCalls.outcome(
            MappingCalls.call { RetrofitClient.apiService.repointRule(row.ruleId, RepointTrackerRuleRequest(choice.id)) }
        )
        return if (outcome == MappingOutcome.Saved) getString(R.string.changed_done, row.label, choice.name) else otherwise(outcome)
    }

    // --- Unlink -----------------------------------------------------------------------------------------------------

    private fun unlink(row: MappedAppRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unlink_confirm_title, row.label))
            .setMessage(R.string.unlink_confirm_text)
            .setPositiveButton(R.string.mapped_unlink) { _, _ -> perform { unlinkNow(row) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun unlinkNow(row: MappedAppRow): String {
        val outcome = MappingCalls.outcome(MappingCalls.call { RetrofitClient.apiService.unlinkRule(row.ruleId) })
        return if (outcome == MappingOutcome.Saved) getString(R.string.unlinked_done, row.label) else otherwise(outcome)
    }

    // --- Distraction ------------------------------------------------------------------------------------------------

    /** The activity's own flag, three ways; with other apps on the same activity, a second line says it reaches them too. */
    private fun distraction(row: MappedAppRow) {
        val follow = when (val meaning = MappedAppsList.followMeaning(row)) {
            FollowMeaning.NoCategory -> getString(R.string.distraction_follow_none)
            is FollowMeaning.Category ->
                getString(if (meaning.isDistraction) R.string.distraction_follow_yes else R.string.distraction_follow_no, meaning.name)
        }
        val choices = DistractionChoice.entries
        val labels = choices.map {
            when (it) {
                DistractionChoice.FOLLOW -> follow
                DistractionChoice.YES -> getString(R.string.distraction_yes)
                DistractionChoice.NO -> getString(R.string.distraction_no)
            }
        }
        val current = DistractionChoice.of(row.ownDistraction)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.distraction_title, row.activityName))
            .setSingleChoiceItems(labels.toTypedArray(), choices.indexOf(current)) { dialog, index ->
                dialog.dismiss()
                val picked = choices[index]
                if (picked == current) return@setSingleChoiceItems
                val sharers = MappedAppsList.sharers(row)
                if (sharers == null) {
                    perform { setDistraction(row, picked) }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.distraction_shared, row.activityName, sharersText(sharers)))
                        .setPositiveButton(R.string.links_save) { _, _ -> perform { setDistraction(row, picked) } }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun setDistraction(row: MappedAppRow, choice: DistractionChoice): String {
        val outcome = MappingCalls.outcome(
            MappingCalls.call { RetrofitClient.apiService.setDistraction(row.activityId, SetDistractionRequest(choice.wire)) }
        )
        return if (outcome == MappingOutcome.Saved) getString(R.string.links_saved) else otherwise(outcome)
    }

    private fun sharersText(sharers: MappedAppsList.Sharers): String = when {
        sharers.more > 0 -> getString(R.string.sharers_more, sharers.named.joinToString(", "), sharers.more)
        sharers.named.size == 2 -> getString(R.string.sharers_two, sharers.named[0], sharers.named[1])
        else -> sharers.named.first()
    }

    // --- Add app ----------------------------------------------------------------------------------------------------

    /** The week's apps nobody decided about yet, busiest first → an activity → "also add this week's time?" → the rule. */
    private fun addApp() {
        if (!PermissionHelper.hasUsageStatsPermission(this)) {
            Toast.makeText(this, R.string.add_app_no_usage_access, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val nowMs = System.currentTimeMillis()
            val offered = withContext(Dispatchers.IO) {
                val usage = installedApps.recentlyUsed(this@MappedAppsActivity, nowMs, IgnoredAppsList.WINDOW_MS, IgnoredAppsList.MIN_FOREGROUND_MS)
                val state = store.state()
                MappedAppsList.addable(usage, MappedAppsList.decided(state.rules) + state.ignored(nowMs), installedApps.homePackages + packageName)
            }
            if (offered.isEmpty()) {
                Toast.makeText(this@MappedAppsActivity, R.string.add_app_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            val entries = offered.map { getString(R.string.add_app_row, it.label, ReviewText.duration(it.foregroundMs / 1000L)) }
            MaterialAlertDialogBuilder(this@MappedAppsActivity)
                .setTitle(R.string.add_app_title)
                .setItems(entries.toTypedArray()) { _, index -> map(offered[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun map(app: AppUsage) {
        lifecycleScope.launch {
            val category = withContext(Dispatchers.IO) { installedApps.appCategory(app.packageName) }
            picker.pick(app.label, category, mapPackage = app.packageName, backfillDays = null) { choice ->
                when (choice) {
                    is PickedActivity.Existing -> BackfillConfirm.show(this@MappedAppsActivity, app.label, choice.name) { days ->
                        perform { createRule(app, choice, days) }
                    }
                    // "+ New activity" mapped the package in the same call, with the dialog's own backfill box.
                    is PickedActivity.Created -> perform { mapped(app, choice.name, choice.response.attributedSeconds) }
                }
            }
        }
    }

    private suspend fun createRule(app: AppUsage, choice: PickedActivity, backfillDays: Int): String {
        val response = MappingCalls.call {
            RetrofitClient.apiService.createRule(CreateTrackerRuleRequest(app.packageName, choice.id, backfillDays))
        }
        return when (if (response == null) RuleOutcome.TRY_AGAIN else RuleOutcome.of(response.code())) {
            RuleOutcome.SAVED -> mapped(app, choice.name, response?.body()?.attributedSeconds ?: 0)
            RuleOutcome.ALREADY_MAPPED -> getString(R.string.review_already_mapped)
            RuleOutcome.ACTIVITY_GONE -> {
                options.invalidate()
                getString(R.string.review_activity_gone)
            }
            RuleOutcome.TRY_AGAIN -> getString(R.string.review_save_failed)
        }
    }

    /** Mapped: a snooze on the app has nothing left to hold back. */
    private fun mapped(app: AppUsage, activityName: String, attributedSeconds: Int): String {
        val prefs = ReviewPreferences(this)
        prefs.snoozes = prefs.snoozes.clear(app.packageName)
        return if (attributedSeconds > 0) {
            getString(R.string.mapped_done_added, app.label, activityName, ReviewText.duration(attributedSeconds.toLong()))
        } else {
            getString(R.string.mapped_done, app.label, activityName)
        }
    }

    // --- Shared -----------------------------------------------------------------------------------------------------

    /** The line for an answer that wasn't "saved"; a stale list or stale options are reloaded on the way. */
    private fun otherwise(outcome: MappingOutcome): String = when (outcome) {
        MappingOutcome.RuleGone -> getString(R.string.mapped_rule_gone)
        MappingOutcome.Gone -> {
            options.invalidate()
            getString(R.string.review_activity_gone)
        }
        MappingOutcome.Refused -> getString(R.string.mapped_refused)
        else -> getString(R.string.review_save_failed)
    }

    /** Runs the answer, says how it went, and redraws from the server's rules. */
    private fun perform(action: suspend () -> String) {
        lifecycleScope.launch {
            val message = action()
            Toast.makeText(this@MappedAppsActivity, message, Toast.LENGTH_LONG).show()
            reload()
        }
    }

    private fun detail(row: MappedAppRow): String {
        val activity = listOfNotNull(
            row.activityName,
            row.categoryName,
            getString(R.string.mapped_archived).takeIf { row.activityArchived }
        ).joinToString(" · ")
        val flag = when (MappedAppsList.flagText(row)) {
            MappedAppsList.FlagText.OWN_YES -> getString(R.string.distraction_yes)
            MappedAppsList.FlagText.OWN_NO -> getString(R.string.distraction_no)
            MappedAppsList.FlagText.FOLLOW_YES -> getString(R.string.mapped_flag_follow_yes, row.categoryName)
            MappedAppsList.FlagText.FOLLOW_NO -> getString(R.string.mapped_flag_follow_no, row.categoryName)
            MappedAppsList.FlagText.FOLLOW_NONE -> getString(R.string.mapped_flag_follow_none)
        }
        return "$activity\n$flag"
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<MappedAppRow> = emptyList()
        private val icons = mutableMapOf<String, Drawable?>()

        fun submit(newRows: List<MappedAppRow>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged") // a short list, rebuilt whole after every answer
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_mapped_app, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            val row = rows[position]
            val icon = icons.getOrPut(row.packageName) { installedApps.icon(row.packageName) }
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(
                icon ?: ContextCompat.getDrawable(this@MappedAppsActivity, android.R.drawable.sym_def_app_icon)
            )
            view.findViewById<TextView>(R.id.tvAppLabel).text = row.label
            view.findViewById<TextView>(R.id.tvAppDetail).text = detail(row)
            view.findViewById<View>(R.id.btnChange).setOnClickListener { change(row) }
            view.findViewById<View>(R.id.btnUnlink).setOnClickListener { unlink(row) }
            view.findViewById<View>(R.id.btnDistraction).setOnClickListener { distraction(row) }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MappedAppsActivity::class.java)
    }
}
