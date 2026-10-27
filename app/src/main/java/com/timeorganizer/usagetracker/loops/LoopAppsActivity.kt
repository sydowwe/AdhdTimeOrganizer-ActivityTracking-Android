package com.timeorganizer.usagetracker.loops

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.ignore.AppUsage
import com.timeorganizer.usagetracker.ignore.IgnoredAppsList
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Apps that don't count": the chat apps a run of quick pickups goes to without being a loop. A switch per app, the week's
 * apps (the list "Ignored apps" shows) with the ticked ones first, and below them any ticked app not used this week.
 * Every switch is saved at once. Nothing is ticked until the user does; nothing here needs the server.
 */
class LoopAppsActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView

    private lateinit var installedApps: InstalledApps
    private lateinit var prefs: LoopPreferences
    private val adapter = AppsAdapter()

    private var usage: List<AppUsage> = emptyList()
    private var excluded: Set<String> = emptySet()
    private var hasUsageAccess = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loop_apps)
        applySystemBarInsets(findViewById(R.id.root))
        progress = findViewById(R.id.progressLoopApps)
        tvMessage = findViewById(R.id.tvLoopAppsMessage)
        findViewById<RecyclerView>(R.id.loopAppsList).apply {
            layoutManager = LinearLayoutManager(this@LoopAppsActivity)
            adapter = this@LoopAppsActivity.adapter
        }
        findViewById<View>(R.id.btnLoopAppsDone).setOnClickListener { finish() }

        installedApps = InstalledApps(this)
        prefs = LoopPreferences(this)
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            hasUsageAccess = PermissionHelper.hasUsageStatsPermission(this@LoopAppsActivity)
            withContext(Dispatchers.IO) {
                excluded = installedApps.homePackages + packageName
                usage = if (hasUsageAccess) {
                    installedApps.recentlyUsed(
                        this@LoopAppsActivity, System.currentTimeMillis(), IgnoredAppsList.WINDOW_MS, IgnoredAppsList.MIN_FOREGROUND_MS
                    )
                } else {
                    emptyList()
                }
            }
            progress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val apps = LoopAppsList.build(usage, excluded, prefs.notCounted) { installedApps.label(it) }
        val items = buildList {
            apps.recent.forEach { add(ListItem.App(it)) }
            if (apps.others.isNotEmpty()) {
                add(ListItem.Header(getString(R.string.loop_apps_others_header)))
                apps.others.forEach { add(ListItem.App(it)) }
            }
        }
        adapter.submit(items)

        tvMessage.visibility = if (apps.recent.isEmpty()) View.VISIBLE else View.GONE
        tvMessage.text = getString(if (hasUsageAccess) R.string.ignored_nothing_used else R.string.ignored_no_usage_access)
    }

    private fun onSwitch(row: LoopAppRow, on: Boolean) {
        if (on == row.notCounted) return
        prefs.notCounted = if (on) prefs.notCounted + row.packageName else prefs.notCounted - row.packageName
        LoopMonitor.kick(this)
        render()
    }

    private sealed interface ListItem {
        data class App(val row: LoopAppRow) : ListItem
        data class Header(val text: String) : ListItem
    }

    private inner class AppsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<ListItem> = emptyList()
        private val icons = mutableMapOf<String, Drawable?>()

        fun submit(newItems: List<ListItem>) {
            items = newItems
            @Suppress("NotifyDataSetChanged") // a short list, rebuilt whole after every switch
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ListItem.App -> TYPE_APP
            is ListItem.Header -> TYPE_HEADER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            // The chime's row: icon, label, a detail line and a switch.
            val layout = if (viewType == TYPE_APP) R.layout.item_chime_app else R.layout.item_ignored_header
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder.itemView as TextView).text = item.text
                is ListItem.App -> bindApp(holder.itemView, item.row)
            }
        }

        private fun bindApp(view: View, row: LoopAppRow) {
            val icon = icons.getOrPut(row.packageName) { installedApps.icon(row.packageName) }
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(
                icon ?: ContextCompat.getDrawable(this@LoopAppsActivity, android.R.drawable.sym_def_app_icon)
            )
            view.findViewById<TextView>(R.id.tvAppLabel).text = row.label
            view.findViewById<TextView>(R.id.tvAppDetail).apply {
                text = row.foregroundMs?.let { getString(R.string.ignored_time_week, ReviewText.duration(it / 1000L)) }
                visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            val switch = view.findViewById<MaterialSwitch>(R.id.swAppChime)
            switch.setOnCheckedChangeListener(null) // a recycled row must not fire the last app's listener
            switch.isChecked = row.notCounted
            switch.contentDescription = row.label
            switch.setOnCheckedChangeListener { _, checked -> onSwitch(row, checked) }
            view.setOnClickListener { switch.toggle() }
        }
    }

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_HEADER = 1

        fun intent(context: Context): Intent = Intent(context, LoopAppsActivity::class.java)
    }
}
