package com.timeorganizer.usagetracker.ignore

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
import com.google.android.material.button.MaterialButton
import com.timeorganizer.usagetracker.LoginActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place to ignore and un-ignore phone apps: the apps used in the last 7 days (from `UsageStatsManager`, with
 * a launcher entry, busiest first; top 30, then "Show all"), and below them every ignored app not among those, so any
 * ignore can be undone. Each row says what the server's rules make of the app ("Mapped to Thesis", "Ignored").
 *
 * The rows come from the cached rules first and again once `GET rules` answered. Nothing is uploaded to build the
 * list, and nothing here is logged: package names and labels are personal.
 */
class IgnoredAppsActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var list: RecyclerView

    private lateinit var installedApps: InstalledApps
    private lateinit var ignoreSync: IgnoreSync
    private lateinit var store: IgnoreStore
    private val adapter = AppsAdapter()

    private var usage: List<AppUsage> = emptyList()
    private var excluded: Set<String> = emptySet()
    private var showAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthPreferences.get(this).isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_ignored_apps)
        applySystemBarInsets(findViewById(R.id.root))
        progress = findViewById(R.id.progressIgnored)
        tvMessage = findViewById(R.id.tvIgnoredMessage)
        list = findViewById(R.id.ignoredList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.btnIgnoredDone).setOnClickListener { finish() }

        installedApps = InstalledApps(this)
        ignoreSync = IgnoreSync(this)
        store = IgnoreStore.get(this)
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val hasUsageAccess = PermissionHelper.hasUsageStatsPermission(this@IgnoredAppsActivity)
            withContext(Dispatchers.IO) {
                excluded = installedApps.homePackages + packageName
                usage = if (hasUsageAccess) recentUsage(System.currentTimeMillis()) else emptyList()
            }
            render(hasUsageAccess)

            // The cached rules may be a sync old; the server's say what the web changed meanwhile.
            try {
                ignoreSync.refreshRules()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cached rules stand.
            }
            progress.visibility = View.GONE
            render(hasUsageAccess)
        }
    }

    /** The apps with ≥ 1 min in front in the window and a launcher entry, as the OS aggregates them. */
    private fun recentUsage(nowMs: Long): List<AppUsage> =
        installedApps.recentlyUsed(this, nowMs, IgnoredAppsList.WINDOW_MS, IgnoredAppsList.MIN_FOREGROUND_MS)

    private fun render(hasUsageAccess: Boolean) {
        val apps = IgnoredAppsList.build(usage, excluded, store.state(), System.currentTimeMillis())
        val items = buildList {
            val shown = if (showAll) apps.recent else apps.recent.take(IgnoredAppsList.TOP_COUNT)
            shown.forEach { add(ListItem.App(it)) }
            if (shown.size < apps.recent.size) add(ListItem.ShowAll(apps.recent.size - shown.size))
            if (apps.others.isNotEmpty()) {
                add(ListItem.Header(getString(R.string.ignored_others_header)))
                // An installed app's own name beats the one the server last saw.
                apps.others.forEach { add(ListItem.App(it.copy(label = installedApps.label(it.packageName) ?: it.label))) }
            }
        }
        adapter.submit(items)

        tvMessage.visibility = if (apps.recent.isEmpty()) View.VISIBLE else View.GONE
        tvMessage.text = getString(if (hasUsageAccess) R.string.ignored_nothing_used else R.string.ignored_no_usage_access)
    }

    private fun onAction(row: AppRow, button: MaterialButton) {
        if (row.state.isIgnored) {
            IgnoreDialogs.confirmStopIgnoring(this, row.label) { perform(button) { stopIgnoring(row) } }
        } else {
            IgnoreDialogs.confirmIgnore(this, row.label, row.mappedTo) { perform(button) { ignore(row) } }
        }
    }

    /** Runs the answer with the row's button off, says how it went, and redraws from the store. */
    private fun perform(button: MaterialButton, action: suspend () -> String) {
        button.isEnabled = false
        lifecycleScope.launch {
            val message = action()
            Toast.makeText(this@IgnoredAppsActivity, message, Toast.LENGTH_LONG).show()
            render(PermissionHelper.hasUsageStatsPermission(this@IgnoredAppsActivity))
        }
    }

    private suspend fun ignore(row: AppRow): String {
        val result = ignoreSync.ignore(row.packageName, row.label)
        return when (result.outcome) {
            IgnoreCallOutcome.CONFIRMED -> {
                val purged = result.response?.purgedSessions ?: 0
                if (purged > 0) getString(R.string.ignored_done_purged, row.label, purged) else getString(R.string.ignored_done, row.label)
            }
            IgnoreCallOutcome.REJECTED -> getString(R.string.ignored_refused)
            IgnoreCallOutcome.AUTH_REQUIRED, IgnoreCallOutcome.KEEP -> getString(R.string.ignored_done_pending, row.label)
        }
    }

    private suspend fun stopIgnoring(row: AppRow): String {
        val result = ignoreSync.stopIgnoring(row.packageName)
        return when (result.outcome) {
            IgnoreCallOutcome.CONFIRMED -> getString(R.string.stopped_ignoring_done, row.label)
            IgnoreCallOutcome.REJECTED -> getString(R.string.stopped_ignoring_refused)
            IgnoreCallOutcome.AUTH_REQUIRED, IgnoreCallOutcome.KEEP -> getString(R.string.stopped_ignoring_pending, row.label)
        }
    }

    private fun detail(row: AppRow): String {
        val parts = mutableListOf<String>()
        row.foregroundMs?.let { parts += getString(R.string.ignored_time_week, ReviewText.duration(it / 1000L)) }
        when (row.state) {
            AppIgnoreState.NONE -> row.mappedTo?.let { parts += getString(R.string.ignored_state_mapped, it) }
            AppIgnoreState.IGNORED -> parts += getString(R.string.ignored_state_ignored)
            AppIgnoreState.IGNORING -> parts += getString(R.string.ignored_state_ignoring)
            AppIgnoreState.STOPPING -> parts += getString(R.string.ignored_state_stopping)
            AppIgnoreState.IGNORE_FAILED -> parts += getString(R.string.ignored_state_ignore_failed)
            AppIgnoreState.STOP_FAILED -> parts += getString(R.string.ignored_state_stop_failed)
        }
        return parts.joinToString("\n")
    }

    private sealed interface ListItem {
        data class App(val row: AppRow) : ListItem
        data class Header(val text: String) : ListItem
        data class ShowAll(val more: Int) : ListItem
    }

    private inner class AppsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<ListItem> = emptyList()
        private val icons = mutableMapOf<String, Drawable?>()

        fun submit(newItems: List<ListItem>) {
            items = newItems
            @Suppress("NotifyDataSetChanged") // a short list, rebuilt whole after every answer
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ListItem.App -> TYPE_APP
            is ListItem.Header -> TYPE_HEADER
            is ListItem.ShowAll -> TYPE_SHOW_ALL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                TYPE_APP -> R.layout.item_ignored_app
                TYPE_HEADER -> R.layout.item_ignored_header
                else -> R.layout.item_ignored_show_all
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val item = items[position]) {
                is ListItem.Header -> (view as TextView).text = item.text
                is ListItem.ShowAll -> (view as MaterialButton).apply {
                    text = getString(R.string.ignored_show_all, item.more)
                    setOnClickListener {
                        showAll = true
                        render(PermissionHelper.hasUsageStatsPermission(this@IgnoredAppsActivity))
                    }
                }
                is ListItem.App -> bindApp(view, item.row)
            }
        }

        private fun bindApp(view: View, row: AppRow) {
            val icon = icons.getOrPut(row.packageName) { installedApps.icon(row.packageName) }
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(
                icon ?: ContextCompat.getDrawable(this@IgnoredAppsActivity, android.R.drawable.sym_def_app_icon)
            )
            view.findViewById<TextView>(R.id.tvAppLabel).text = row.label
            view.findViewById<TextView>(R.id.tvAppDetail).apply {
                text = detail(row)
                visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            view.findViewById<MaterialButton>(R.id.btnAppAction).apply {
                isEnabled = true
                text = getString(if (row.state.isIgnored) R.string.stop_ignoring_action else R.string.ignore_action)
                setOnClickListener { onAction(row, this) }
            }
        }
    }

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_SHOW_ALL = 2

        fun intent(context: Context): Intent = Intent(context, IgnoredAppsActivity::class.java)
    }
}
