package com.timeorganizer.usagetracker.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import kotlinx.coroutines.launch

/**
 * Quick capture's one field (prompt 18), over whatever was on screen: the Quick Settings tile and the launcher shortcut
 * open it, Enter or **Save** stores the thought, the back key or **Cancel** drops it. A share (`ACTION_SEND`,
 * `text/plain`) is stored at once with no field: choosing this app in the share sheet is the second tap.
 *
 * Translucent and in its own task, so it finishes back to the app it came over. Stores first ([CaptureSender.capture]
 * commits the row), says so, then finishes; sending is the queue's job.
 */
class CaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val draft = CaptureRules.shared(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            if (draft == null) {
                Toast.makeText(this, R.string.capture_share_empty, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                save(draft)
            }
            return
        }
        if (savedInstanceState == null) showField()
        else finish()
    }

    private fun showField() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_capture, null)
        val field = view.findViewById<EditText>(R.id.etCapture)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_title)
            .setView(view)
            .setPositiveButton(R.string.capture_save, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        fun submit() {
            val draft = CaptureRules.typed(field.text?.toString()) ?: return
            dialog.dismiss()
            save(draft)
        }

        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun save(draft: CaptureDraft) {
        val signedIn = AuthPreferences.get(this).isLoggedIn
        val app = applicationContext
        lifecycleScope.launch {
            CaptureSender.capture(app, draft)
            Toast.makeText(app, if (signedIn) R.string.capture_saved else R.string.capture_saved_signed_out, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.timeorganizer.usagetracker.capture.CAPTURE"

        fun intent(context: Context): Intent =
            Intent(context, CaptureActivity::class.java).setAction(ACTION_CAPTURE)
    }
}
