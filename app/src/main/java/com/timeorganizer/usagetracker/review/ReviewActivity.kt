package com.timeorganizer.usagetracker.review

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeorganizer.usagetracker.LoginActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedAppDto
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.ignore.IgnoreCallOutcome
import com.timeorganizer.usagetracker.ignore.IgnoreDialogs
import com.timeorganizer.usagetracker.ignore.IgnoreSync
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * The evening review: the apps that held the most of today's phone time without a rule, one card each — map it to
 * the activity the user's timers suggest, pick another, ignore it, or skip it. A mapping is the server's rule
 * create, which also puts the package's time already stored today into History, so a tap pays off tonight. Ignore is
 * the one ignore the "Ignored apps" screen uses too ([IgnoreSync]): the phone stops recording the app at once and the
 * server deletes what it stored.
 *
 * The list is the server's (`unmapped`) and is read fresh each time the screen opens; the phone decides
 * nothing about what is unmapped or worth asking. Nothing here is logged: package and activity names are personal.
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rows: LinearLayout
    private lateinit var btnRetry: MaterialButton

    /** The pick list, fetched on the first "Choose activity" and kept until the server says one is gone. */
    private var activities: List<TrackerActivityDto>? = null

    /** Whether any card was answered, so an emptied list reads "all done" rather than "nothing to review". */
    private var answeredAny = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthPreferences.get(this).isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_review)
        applySystemBarInsets(findViewById(R.id.root))
        tvSummary = findViewById(R.id.tvReviewSummary)
        tvMessage = findViewById(R.id.tvReviewMessage)
        progress = findViewById(R.id.progressReview)
        rows = findViewById(R.id.reviewRows)
        btnRetry = findViewById(R.id.btnReviewRetry)

        findViewById<View>(R.id.btnReviewDone).setOnClickListener { finish() }
        btnRetry.setOnClickListener { load() }

        // Opened from the notification or not, the question has been put.
        Notifications.cancel(this, Notifications.ID_EVENING_REVIEW)
        load()
    }

    private fun load() {
        rows.removeAllViews()
        tvSummary.text = ""
        tvMessage.visibility = View.GONE
        btnRetry.visibility = View.GONE
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val response = call { RetrofitClient.apiService.unmappedToday() }
            progress.visibility = View.GONE

            val today = response?.takeIf { it.isSuccessful }?.body()
            if (today == null) {
                showMessage(getString(if (response == null) R.string.review_offline else R.string.review_server_error), retry = true)
                return@launch
            }
            show(today)
        }
    }

    private fun show(today: UnmappedTodayResponse) {
        val apps = today.apps.orEmpty()
        if (apps.isEmpty()) {
            showMessage(getString(R.string.review_nothing))
            return
        }

        tvSummary.text = getString(R.string.review_summary, ReviewText.duration(today.unmappedSeconds))
        val inflater = LayoutInflater.from(this)
        apps.forEach { rows.addView(card(inflater, it)) }
    }

    private fun card(inflater: LayoutInflater, app: UnmappedAppDto): View {
        val card = inflater.inflate(R.layout.item_review_app, rows, false)
        card.findViewById<TextView>(R.id.tvAppLabel).text = app.appLabel
        card.findViewById<TextView>(R.id.tvAppTime).text = ReviewText.duration(app.seconds)

        val suggestedId = app.suggestedActivityId
        val suggestedName = app.suggestedActivityName
        card.findViewById<MaterialButton>(R.id.btnSuggested).apply {
            if (suggestedId != null && suggestedName != null) {
                visibility = View.VISIBLE
                text = getString(R.string.review_map_to, suggestedName)
                setOnClickListener { submit(card, CreateTrackerRuleRequest(app.packageName, activityId = suggestedId), suggestedName) }
            }
        }
        card.findViewById<View>(R.id.btnPick).setOnClickListener {
            chooseActivity { choice -> submit(card, CreateTrackerRuleRequest(app.packageName, activityId = choice.id), choice.name) }
        }
        card.findViewById<View>(R.id.btnIgnore).setOnClickListener {
            IgnoreDialogs.confirmIgnore(this, app.appLabel, mappedTo = null) { ignore(card, app) }
        }
        card.findViewById<View>(R.id.btnSkip).setOnClickListener {
            rows.removeView(card)
            afterRowSettled()
        }
        return card
    }

    /** Not a rule create: the phone drops the app from now on even if the server can't be reached yet. */
    private fun ignore(card: View, app: UnmappedAppDto) {
        setActionsEnabled(card, false)
        status(card, getString(R.string.review_saving))

        lifecycleScope.launch {
            val label = withContext(Dispatchers.IO) { InstalledApps(this@ReviewActivity).label(app.packageName) } ?: app.appLabel
            val result = IgnoreSync(this@ReviewActivity).ignore(app.packageName, label)
            if (result.outcome == IgnoreCallOutcome.REJECTED) {
                status(card, getString(R.string.review_save_failed))
                setActionsEnabled(card, true)
                return@launch
            }
            card.findViewById<View>(R.id.rowActions).visibility = View.GONE
            status(
                card,
                getString(if (result.outcome == IgnoreCallOutcome.CONFIRMED) R.string.review_ignored else R.string.review_ignored_pending)
            )
            answeredAny = true
            afterRowSettled()
        }
    }

    private fun submit(card: View, request: CreateTrackerRuleRequest, activityName: String) {
        setActionsEnabled(card, false)
        status(card, getString(R.string.review_saving))

        lifecycleScope.launch {
            val response = call { RetrofitClient.apiService.createRule(request) }
            when (if (response == null) RuleOutcome.TRY_AGAIN else RuleOutcome.of(response.code())) {
                RuleOutcome.SAVED -> {
                    card.findViewById<View>(R.id.rowActions).visibility = View.GONE
                    status(card, saved(response?.body(), activityName))
                    answeredAny = true
                    afterRowSettled()
                }
                RuleOutcome.ALREADY_MAPPED -> {
                    // Another device got there first; the list is stale, not the tap wrong.
                    Toast.makeText(this@ReviewActivity, R.string.review_already_mapped, Toast.LENGTH_SHORT).show()
                    load()
                }
                RuleOutcome.ACTIVITY_GONE -> {
                    activities = null
                    status(card, getString(R.string.review_activity_gone))
                    setActionsEnabled(card, true)
                }
                RuleOutcome.TRY_AGAIN -> {
                    status(card, getString(R.string.review_save_failed))
                    setActionsEnabled(card, true)
                }
            }
        }
    }

    private fun saved(body: CreateTrackerRuleResponse?, activityName: String): String = when {
        body != null && body.attributedSeconds > 0 ->
            getString(R.string.review_mapped_added, activityName, ReviewText.duration(body.attributedSeconds.toLong()))
        else -> getString(R.string.review_mapped, activityName)
    }

    private fun chooseActivity(onChosen: (TrackerActivityDto) -> Unit) {
        lifecycleScope.launch {
            val list = activities ?: call { RetrofitClient.apiService.activities() }
                ?.takeIf { it.isSuccessful }?.body()?.also { activities = it }
            if (list == null) {
                Toast.makeText(this@ReviewActivity, R.string.review_offline, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (list.isEmpty()) {
                Toast.makeText(this@ReviewActivity, R.string.review_no_activities, Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@ReviewActivity)
                .setTitle(R.string.review_choose_activity)
                .setItems(list.map { it.name }.toTypedArray()) { _, index -> onChosen(list[index]) }
                .show()
        }
    }

    /** A skipped or answered card may have been the last one. */
    private fun afterRowSettled() {
        if (rows.childCount > 0) return
        tvSummary.text = ""
        showMessage(getString(if (answeredAny) R.string.review_all_done else R.string.review_nothing))
    }

    private fun showMessage(text: String, retry: Boolean = false) {
        tvMessage.text = text
        tvMessage.visibility = View.VISIBLE
        btnRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun status(card: View, text: String) {
        card.findViewById<TextView>(R.id.tvRowStatus).apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun setActionsEnabled(card: View, enabled: Boolean) {
        listOf(R.id.btnSuggested, R.id.btnPick, R.id.btnIgnore, R.id.btnSkip).forEach {
            card.findViewById<View>(it).isEnabled = enabled
        }
    }

    /** `null` when nothing was learned: offline, a timeout, an unreadable body. */
    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
