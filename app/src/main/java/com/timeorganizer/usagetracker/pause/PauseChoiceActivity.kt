package com.timeorganizer.usagetracker.pause

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tile's four lengths ([PauseDialog]) over whatever was on screen. Translucent and in its own task, like
 * `CaptureActivity`, so it finishes back to the app it came over. An activity rather than `TileService.showDialog`: it is the
 * Capture tile's path, the dialog is the status card's, and a Material dialog needs an activity's theme.
 */
class PauseChoiceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val app = applicationContext
        lifecycleScope.launch {
            PauseDialog.show(this@PauseChoiceActivity, onCancel = ::finish) { length ->
                lifecycleScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) { Pauses.start(app, length) }
                    finish()
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PauseChoiceActivity::class.java)
    }
}
