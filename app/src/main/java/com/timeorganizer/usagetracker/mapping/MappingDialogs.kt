package com.timeorganizer.usagetracker.mapping

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkDto
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import kotlinx.coroutines.launch

/** What a picker answered: an activity that existed (the caller maps to it), or one it just made. */
sealed interface PickedActivity {
    val id: Long
    val name: String

    data class Existing(override val id: Long, override val name: String) : PickedActivity

    /** Made by "+ New activity". With a `mapPackage`, [response] says the package is mapped too (`ruleId`). */
    data class Created(override val id: Long, override val name: String, val response: CreateTrackerActivityResponse) : PickedActivity
}

/**
 * The one activity picker every mapping screen uses: the activities the app's category link points at (all of them
 * without a link, or when none fits), "Show all activities", and "+ New activity" (`NewActivityDialog`). The phone
 * creates activities but never roles or categories — those need a colour and an icon, and stay a web job.
 */
class ActivityPicker(private val host: AppCompatActivity, private val options: MappingOptions) {

    /**
     * [appCategory]: the app's own category (`InstalledApps.appCategory`), `null` = unfiltered. [mapPackage]: a new activity
     * maps this package in the same call, backfilling [backfillDays] — or, when that is `null`, as many as the dialog's own
     * "also add this week's time" box says (the review card has its box already; "Add app" has none).
     */
    fun pick(
        appLabel: String?,
        appCategory: String?,
        mapPackage: String?,
        backfillDays: Int?,
        onPicked: (PickedActivity) -> Unit
    ) {
        host.lifecycleScope.launch {
            val loaded = options.get()
            if (loaded == null) {
                toast(R.string.review_offline)
                return@launch
            }
            show(loaded, appLabel, appCategory, mapPackage, backfillDays, showAll = false, onPicked)
        }
    }

    private fun show(
        loaded: TrackerActivityOptionsResponse,
        appLabel: String?,
        appCategory: String?,
        mapPackage: String?,
        backfillDays: Int?,
        showAll: Boolean,
        onPicked: (PickedActivity) -> Unit
    ) {
        val all = loaded.activities.orEmpty()
        val link = MappingRules.linkFor(appCategory, loaded)
        val fitting = MappingRules.filtered(all, link)
        val filtering = !showAll && link != null && fitting.isNotEmpty() && fitting.size < all.size
        val shown = if (filtering) fitting else all

        val entries = buildList {
            add(host.getString(R.string.picker_new_activity))
            shown.forEach { add(it.name) }
            if (filtering) add(host.getString(R.string.picker_show_all, all.size - fitting.size))
        }
        val title = if (filtering) host.getString(R.string.picker_title_filtered, appCategoryName(appCategory))
        else host.getString(R.string.review_choose_activity)

        MaterialAlertDialogBuilder(host)
            .setTitle(title)
            .setItems(entries.toTypedArray()) { _, index ->
                when {
                    index == 0 -> NewActivityDialog(host, options).show(loaded, appLabel, link, mapPackage, backfillDays, onPicked)
                    filtering && index == entries.lastIndex ->
                        show(loaded, appLabel, appCategory, mapPackage, backfillDays, showAll = true, onPicked)
                    else -> shown[index - 1].let { onPicked(PickedActivity.Existing(it.id, it.name)) }
                }
            }
            .show()
    }

    private fun appCategoryName(appCategory: String?): String {
        val index = AppCategory.NAMES.indexOf(appCategory)
        return host.resources.getStringArray(R.array.app_category_names).getOrNull(index) ?: appCategory.orEmpty()
    }

    private fun toast(text: Int) = Toast.makeText(host, text, Toast.LENGTH_SHORT).show()
}

/**
 * "+ New activity": a name (the app's label to start with), an existing role (the app category's link, else "Phone
 * app"), an optional existing category, and the distraction flag as follow / yes / no — with what "follow" means right
 * now. The rules are `MappingRules`; this only shows them and sends `POST tracker/android/activities`.
 */
class NewActivityDialog(private val host: AppCompatActivity, private val options: MappingOptions) {

    fun show(
        loaded: TrackerActivityOptionsResponse,
        appLabel: String?,
        link: AppCategoryLinkDto?,
        mapPackage: String?,
        backfillDays: Int?,
        onPicked: (PickedActivity) -> Unit
    ) {
        val view = LayoutInflater.from(host).inflate(R.layout.dialog_new_activity, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val spRole = view.findViewById<Spinner>(R.id.spRole)
        val spCategory = view.findViewById<Spinner>(R.id.spCategory)
        val rgDistraction = view.findViewById<RadioGroup>(R.id.rgDistraction)
        val rbFollow = view.findViewById<RadioButton>(R.id.rbFollow)
        val cbBackfill = view.findViewById<CheckBox>(R.id.cbBackfill)
        val tvError = view.findViewById<TextView>(R.id.tvNewActivityError)

        val defaults = MappingRules.defaults(appLabel, link, loaded)
        etName.setText(defaults.name)

        // Roles: with no "Phone app" role yet, a first entry sends none and the server makes it.
        val roles = loaded.roles.orEmpty()
        val roleIds: List<Long?> = buildList {
            if (loaded.phoneAppRoleId == null) add(null)
            roles.forEach { add(it.id) }
        }
        val roleNames = buildList {
            if (loaded.phoneAppRoleId == null) add(host.getString(R.string.new_activity_phone_app_role))
            roles.forEach { add(it.name) }
        }
        spRole.adapter = spinnerAdapter(roleNames)
        spRole.setSelection(roleIds.indexOf(defaults.roleId).coerceAtLeast(0))

        val categories = loaded.categories.orEmpty()
        spCategory.adapter = spinnerAdapter(listOf(host.getString(R.string.new_activity_no_category)) + categories.map { it.name })
        spCategory.setSelection(categories.indexOfFirst { it.id == defaults.categoryId } + 1)

        fun followText() {
            val category = categories.getOrNull(spCategory.selectedItemPosition - 1)
            rbFollow.text = when (val meaning = FollowMeaning.of(category)) {
                FollowMeaning.NoCategory -> host.getString(R.string.distraction_follow_none)
                is FollowMeaning.Category -> host.getString(
                    if (meaning.isDistraction) R.string.distraction_follow_yes else R.string.distraction_follow_no, meaning.name
                )
            }
        }
        followText()
        spCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = followText()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        cbBackfill.visibility = if (mapPackage != null && backfillDays == null) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(host)
            .setTitle(R.string.new_activity_title)
            .setView(view)
            .setPositiveButton(R.string.new_activity_create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val create = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            create.setOnClickListener {
                tilName.error = null
                tvError.visibility = View.GONE
                val name = when (val check = MappingRules.checkName(etName.text?.toString())) {
                    is MappingRules.NameCheck.Ok -> check.name
                    MappingRules.NameCheck.Blank -> return@setOnClickListener run { tilName.error = host.getString(R.string.new_activity_name_required) }
                    MappingRules.NameCheck.TooLong -> return@setOnClickListener run {
                        tilName.error = host.getString(R.string.new_activity_name_too_long, MappingRules.MAX_NAME_LENGTH)
                    }
                }
                val distraction = when (rgDistraction.checkedRadioButtonId) {
                    R.id.rbYes -> DistractionChoice.YES
                    R.id.rbNo -> DistractionChoice.NO
                    else -> DistractionChoice.FOLLOW
                }
                val request = CreateTrackerActivityRequest(
                    name = name,
                    roleId = roleIds.getOrNull(spRole.selectedItemPosition),
                    categoryId = categories.getOrNull(spCategory.selectedItemPosition - 1)?.id,
                    isDistraction = distraction.wire,
                    mapPackage = mapPackage,
                    appLabel = if (mapPackage != null) appLabel else null,
                    backfillDays = when {
                        mapPackage == null -> 0
                        backfillDays != null -> backfillDays
                        cbBackfill.isChecked -> MappingRules.WEEK_BACKFILL_DAYS
                        else -> 0
                    }
                )
                create.isEnabled = false
                host.lifecycleScope.launch {
                    val response = MappingCalls.call { RetrofitClient.apiService.createActivity(request) }
                    create.isEnabled = true
                    when (val outcome = MappingCalls.outcome(response)) {
                        MappingOutcome.Saved -> {
                            val body = response?.body() ?: return@launch
                            options.invalidate()
                            dialog.dismiss()
                            onPicked(PickedActivity.Created(body.activityId, name, body))
                        }
                        is MappingOutcome.NameTaken -> offerExisting(dialog, name, outcome.activityId, onPicked)
                        MappingOutcome.NameArchived -> tilName.error = host.getString(R.string.new_activity_name_archived)
                        MappingOutcome.AlreadyMapped -> {
                            dialog.dismiss()
                            Toast.makeText(host, R.string.review_already_mapped, Toast.LENGTH_SHORT).show()
                        }
                        MappingOutcome.Gone -> {
                            options.invalidate()
                            dialog.dismiss()
                            Toast.makeText(host, R.string.new_activity_options_gone, Toast.LENGTH_LONG).show()
                        }
                        MappingOutcome.Refused -> showError(tvError, R.string.new_activity_refused)
                        MappingOutcome.RuleGone, MappingOutcome.TryAgain -> showError(tvError, R.string.review_save_failed)
                    }
                }
            }
        }
        dialog.show()
    }

    /** The name is an active activity's: offer that one instead of a second of the same name. */
    private fun offerExisting(dialog: AlertDialog, name: String, activityId: Long?, onPicked: (PickedActivity) -> Unit) {
        if (activityId == null) return
        MaterialAlertDialogBuilder(host)
            .setMessage(host.getString(R.string.new_activity_name_taken, name))
            .setPositiveButton(R.string.new_activity_use_existing) { _, _ ->
                dialog.dismiss()
                onPicked(PickedActivity.Existing(activityId, name))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(view: TextView, text: Int) {
        view.setText(text)
        view.visibility = View.VISIBLE
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(host, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}

/** The app-category links (`PUT tracker/android/app-category-links/{appCategory}`): the picker's filter per app category. */
class AppCategoryLinksDialog(private val host: AppCompatActivity, private val options: MappingOptions) {

    fun show() {
        host.lifecycleScope.launch {
            val loaded = options.get() ?: return@launch Toast.makeText(host, R.string.review_offline, Toast.LENGTH_SHORT).show()
            val names = host.resources.getStringArray(R.array.app_category_names)
            val rows = AppCategory.NAMES.mapIndexed { index, category ->
                val link = MappingRules.linkFor(category, loaded)
                val target = listOfNotNull(
                    loaded.roles.orEmpty().firstOrNull { it.id == link?.roleId }?.name,
                    loaded.categories.orEmpty().firstOrNull { it.id == link?.categoryId }?.name
                ).joinToString(" · ")
                host.getString(R.string.links_row, names[index], target.ifEmpty { host.getString(R.string.links_not_linked) })
            }
            MaterialAlertDialogBuilder(host)
                .setTitle(R.string.links_title)
                .setItems(rows.toTypedArray()) { _, index -> edit(loaded, AppCategory.NAMES[index], names[index]) }
                .setPositiveButton(R.string.review_done, null)
                .show()
        }
    }

    private fun edit(loaded: TrackerActivityOptionsResponse, category: String, label: String) {
        val view = LayoutInflater.from(host).inflate(R.layout.dialog_link_editor, null)
        val spRole = view.findViewById<Spinner>(R.id.spLinkRole)
        val spCategory = view.findViewById<Spinner>(R.id.spLinkCategory)
        val roles = loaded.roles.orEmpty()
        val categories = loaded.categories.orEmpty()
        val none = host.getString(R.string.links_any)
        spRole.adapter = adapter(listOf(none) + roles.map { it.name })
        spCategory.adapter = adapter(listOf(none) + categories.map { it.name })
        val link = MappingRules.linkFor(category, loaded)
        spRole.setSelection(roles.indexOfFirst { it.id == link?.roleId } + 1)
        spCategory.setSelection(categories.indexOfFirst { it.id == link?.categoryId } + 1)

        MaterialAlertDialogBuilder(host)
            .setTitle(host.getString(R.string.links_editor_title, label))
            .setView(view)
            .setPositiveButton(R.string.links_save) { _, _ ->
                save(category, roles.getOrNull(spRole.selectedItemPosition - 1)?.id, categories.getOrNull(spCategory.selectedItemPosition - 1)?.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Both "any" deletes the link. */
    private fun save(category: String, roleId: Long?, categoryId: Long?) {
        host.lifecycleScope.launch {
            val response = MappingCalls.call {
                RetrofitClient.apiService.putAppCategoryLink(category, AppCategoryLinkRequest(roleId, categoryId))
            }
            val text = when (MappingCalls.outcome(response)) {
                MappingOutcome.Saved -> R.string.links_saved
                MappingOutcome.Gone -> R.string.new_activity_options_gone
                else -> R.string.review_save_failed
            }
            options.invalidate()
            Toast.makeText(host, text, Toast.LENGTH_SHORT).show()
            show()
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(host, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}

/** "Map X to Y" with "also add this week's time" — for an existing activity picked for a package (a new one has the box itself). */
object BackfillConfirm {
    fun show(host: AppCompatActivity, appLabel: String, activityName: String, onConfirmed: (backfillDays: Int) -> Unit) {
        val box = CheckBox(host).apply { setText(R.string.backfill_week) }
        val padding = (24 * host.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(host).apply {
            setPadding(padding, padding / 3, padding, 0)
            addView(box)
        }
        MaterialAlertDialogBuilder(host)
            .setTitle(host.getString(R.string.map_confirm_title, appLabel, activityName))
            .setView(frame)
            .setPositiveButton(R.string.map_confirm) { _, _ ->
                onConfirmed(if (box.isChecked) MappingRules.WEEK_BACKFILL_DAYS else 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
