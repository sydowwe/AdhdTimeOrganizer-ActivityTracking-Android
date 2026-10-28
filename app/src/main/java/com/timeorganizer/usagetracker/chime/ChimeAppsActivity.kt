package com.timeorganizer.usagetracker.chime

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
import com.timeorganizer.usagetracker.LoginActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.ignore.AppUsage
import com.timeorganizer.usagetracker.ignore.IgnoredAppsList
import com.timeorganizer.usagetracker.now.KeptDistractions
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which apps the time chime is for: "Chime my distraction apps" (the server's list, followed as it changes) and a switch
 * per app. The rows are the week's apps — the same list "Ignored apps" shows — with the distraction ones first, and below
 * them any chosen app not used this week. Every switch is saved at once and decided on at once.
 *
 * The distraction list comes from the cached copy first, then from `/now`. Nothing here is logged.
 */
class ChimeAppsActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var list: RecyclerView

    private lateinit var installedApps: InstalledApps
    private lateinit var prefs: ChimePreferences
    private val adapter = AppsAdapter()

    private var usage: List<AppUsage> = emptyList()
    private var excluded: Set<String> = emptySet()
    private var hasUsageAccess = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthPreferences.get(this).isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_chime_apps)
        applySystemBarInsets(findViewById(R.id.root))
        progress = findViewById(R.id.progressChime)
        tvMessage = findViewById(R.id.tvChimeMessage)
        list = findViewById(R.id.chimeList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.btnChimeDone).setOnClickListener { finish() }

        installedApps = InstalledApps(this)
        prefs = ChimePreferences(this)

        val follow = findViewById<MaterialSwitch>(R.id.swChimeDistractions)
        follow.isChecked = prefs.choices.followDistractions
        follow.setOnCheckedChangeListener { _, checked ->
            prefs.choices = prefs.choices.copy(followDistractions = checked)
            changed()
        }
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            hasUsageAccess = PermissionHelper.hasUsageStatsPermission(this@ChimeAppsActivity)
            withContext(Dispatchers.IO) {
                excluded = installedApps.homePackages + packageName
                usage = if (hasUsageAccess) {
                    installedApps.recentlyUsed(
                        this@ChimeAppsActivity, System.currentTimeMillis(), IgnoredAppsList.WINDOW_MS, IgnoredAppsList.MIN_FOREGROUND_MS
                    )
                } else {
                    emptyList()
                }
            }
            render()

            // The cached list may be a sync old, or empty before the chime was ever on.
            if (KeptDistractions.refresh(this@ChimeAppsActivity)) ChimeMonitor.kick(this@ChimeAppsActivity)
            progress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val apps = ChimeAppsList.build(usage, excluded, prefs.choices, KeptDistractions.get(this)) { installedApps.label(it) }
        val items = buildList {
            apps.recent.forEach { add(ListItem.App(it)) }
            if (apps.others.isNotEmpty()) {
                add(ListItem.Header(getString(R.string.chime_others_header)))
                apps.others.forEach { add(ListItem.App(it)) }
            }
        }
        adapter.submit(items)

        tvMessage.visibility = if (apps.recent.isEmpty()) View.VISIBLE else View.GONE
        tvMessage.text = getString(if (hasUsageAccess) R.string.ignored_nothing_used else R.string.ignored_no_usage_access)
    }

    private fun onSwitch(row: ChimeAppRow, on: Boolean) {
        if (on == row.chimed) return
        prefs.choices = prefs.choices.set(row.packageName, on, KeptDistractions.get(this))
        changed()
    }

    private fun changed() {
        ChimeMonitor.kick(this)
        render()
    }

    private fun detail(row: ChimeAppRow): String {
        val parts = mutableListOf<String>()
        row.foregroundMs?.let { parts += getString(R.string.ignored_time_week, ReviewText.duration(it / 1000L)) }
        if (row.reason == ChimeReason.UNTICKED) {
            parts += getString(R.string.chime_reason_unticked)
        } else if (row.distraction) {
            parts += getString(R.string.chime_reason_distraction)
        }
        return parts.joinToString(" · ")
    }

    private sealed interface ListItem {
        data class App(val row: ChimeAppRow) : ListItem
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
            val layout = if (viewType == TYPE_APP) R.layout.item_chime_app else R.layout.item_ignored_header
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder.itemView as TextView).text = item.text
                is ListItem.App -> bindApp(holder.itemView, item.row)
            }
        }

        private fun bindApp(view: View, row: ChimeAppRow) {
            val icon = icons.getOrPut(row.packageName) { installedApps.icon(row.packageName) }
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(
                icon ?: ContextCompat.getDrawable(this@ChimeAppsActivity, android.R.drawable.sym_def_app_icon)
            )
            view.findViewById<TextView>(R.id.tvAppLabel).text = row.label
            view.findViewById<TextView>(R.id.tvAppDetail).apply {
                text = detail(row)
                visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            val switch = view.findViewById<MaterialSwitch>(R.id.swAppChime)
            switch.setOnCheckedChangeListener(null) // a recycled row must not fire the last app's listener
            switch.isChecked = row.chimed
            switch.contentDescription = row.label
            switch.setOnCheckedChangeListener { _, checked -> onSwitch(row, checked) }
            view.setOnClickListener { switch.toggle() }
        }
    }

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_HEADER = 1

        fun intent(context: Context): Intent = Intent(context, ChimeAppsActivity::class.java)
    }
}
