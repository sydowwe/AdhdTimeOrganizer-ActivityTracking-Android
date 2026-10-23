package com.timeorganizer.usagetracker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.timeorganizer.usagetracker.auth.SignOutReason
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.AuthResponse
import com.timeorganizer.usagetracker.data.api.dto.LoginRequest
import com.timeorganizer.usagetracker.data.api.dto.TwoFactorLoginRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.sync.SyncEngine
import com.timeorganizer.usagetracker.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Password sign-in, then — for an account with two-factor authentication — the code step
 * (`/auth/tracker/login/2fa` with the pending token the password step returned).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvTwoFactorHint: TextView
    private lateinit var otpContainer: View
    private lateinit var etOtp: EditText
    private lateinit var otpBoxes: List<TextView>
    private lateinit var tilCode: TextInputLayout
    private lateinit var etCode: TextInputEditText
    private lateinit var btnCodeMode: Button
    private lateinit var btnLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private lateinit var authPrefs: AuthPreferences

    /** Set while the code step is showing; lives about ten minutes on the server. */
    private var pendingAuthToken: String? = null

    /** The code step takes a recovery code in a plain field instead of the six boxes. */
    private var useRecoveryCode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applySystemBarInsets(findViewById(R.id.root))

        authPrefs = AuthPreferences.get(this)

        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        tvTwoFactorHint = findViewById(R.id.tvTwoFactorHint)
        otpContainer = findViewById(R.id.otpContainer)
        etOtp = findViewById(R.id.etOtp)
        tilCode = findViewById(R.id.tilCode)
        etCode = findViewById(R.id.etCode)
        btnCodeMode = findViewById(R.id.btnCodeMode)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        val boxRow = findViewById<LinearLayout>(R.id.otpBoxes)
        otpBoxes = List(OTP_LENGTH) { layoutInflater.inflate(R.layout.item_otp_box, boxRow, false) as TextView }
        otpBoxes.forEach(boxRow::addView)
        etOtp.doAfterTextChanged { onOtpChanged() }
        etOtp.setOnFocusChangeListener { _, _ -> renderOtp() }
        btnCodeMode.setOnClickListener { setRecoveryMode(!useRecoveryCode) }

        savedInstanceState?.getString(STATE_PENDING_TOKEN)?.let {
            showCodeStep(it, recovery = savedInstanceState.getBoolean(STATE_RECOVERY))
        }
        if (savedInstanceState == null) {
            when (authPrefs.signOutReason) {
                SignOutReason.SESSION_ENDED -> showError(R.string.login_session_ended)
                SignOutReason.USER, null -> Unit
            }
        }

        btnLogin.setOnClickListener {
            if (pendingAuthToken == null) attemptLogin() else attemptCode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingAuthToken?.let { outState.putString(STATE_PENDING_TOKEN, it) }
        outState.putBoolean(STATE_RECOVERY, useRecoveryCode)
    }

    private fun attemptLogin() {
        val email = etEmail.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""

        tilEmail.error = null
        tilPassword.error = null
        tvError.visibility = View.GONE

        if (email.isEmpty()) {
            tilEmail.error = getString(R.string.login_email_required)
            return
        }
        if (password.isEmpty()) {
            tilPassword.error = getString(R.string.login_password_required)
            return
        }

        submit({ RetrofitClient.authApiService.login(LoginRequest(email, password)) }) { body ->
            val pending = body.pendingAuthToken
            when {
                !body.requiresTwoFactor -> false
                body.requiresTwoFactorSetup -> {
                    showError(R.string.login_two_factor_setup_required)
                    true
                }
                pending.isNullOrEmpty() -> {
                    showError(R.string.login_unexpected_response)
                    true
                }
                else -> {
                    showCodeStep(pending)
                    true
                }
            }
        }
    }

    private fun attemptCode() {
        val token = pendingAuthToken ?: return
        tilCode.error = null
        tvError.visibility = View.GONE

        val code: String
        if (useRecoveryCode) {
            code = etCode.text?.toString()?.trim()?.replace(" ", "") ?: ""
            if (code.isEmpty()) {
                tilCode.error = getString(R.string.two_factor_code_required)
                return
            }
        } else {
            code = etOtp.text?.toString() ?: ""
            if (code.length < OTP_LENGTH) {
                showError(R.string.two_factor_otp_incomplete)
                return
            }
        }

        submit({ RetrofitClient.authApiService.loginTwoFactor(TwoFactorLoginRequest(code, token)) }) { false }
    }

    /**
     * Runs one auth call and handles every answer the same way. [handleBody] returns `true` when it dealt with
     * a successful body itself (the 2FA hand-off); otherwise the body must carry the token pair.
     */
    private fun submit(call: suspend () -> Response<AuthResponse>, handleBody: (AuthResponse) -> Boolean) {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    call()
                } catch (_: Exception) {
                    null
                }
            }
            setLoading(false)

            val body = result?.body()
            when {
                result == null -> showError(R.string.login_network_error)
                result.isSuccessful && body == null -> showError(R.string.login_unexpected_response)
                result.isSuccessful && body != null -> if (!handleBody(body)) completeSignIn(body)
                result.code() == 401 || result.code() == 400 ->
                    if (pendingAuthToken == null) showError(R.string.login_invalid_credentials) else onCodeRefused()
                // Refused for this account after the password was right: scheduled for deletion, no tracker access.
                result.code() == 403 -> showError(R.string.login_account_refused)
                result.code() == 423 || result.code() == 429 -> showError(R.string.login_too_many_attempts)
                else -> showError(getString(R.string.login_failed_code, result.code()))
            }
        }
    }

    private fun completeSignIn(body: AuthResponse) {
        val access = body.accessToken
        val refresh = body.refreshToken
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
            showError(R.string.login_unexpected_response)
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                authPrefs.saveTokens(access, refresh)
                SyncEngine.onSignedIn(applicationContext, access)
            }
            TimeOrganizerApp.enqueueSyncNow(applicationContext)
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun showCodeStep(token: String, recovery: Boolean = false) {
        pendingAuthToken = token
        tilEmail.isEnabled = false
        tilPassword.visibility = View.GONE
        tvTwoFactorHint.visibility = View.VISIBLE
        btnCodeMode.visibility = View.VISIBLE
        btnLogin.setText(R.string.two_factor_verify)
        setRecoveryMode(recovery)
    }

    private fun setRecoveryMode(recovery: Boolean) {
        useRecoveryCode = recovery
        otpContainer.visibility = if (recovery) View.GONE else View.VISIBLE
        tilCode.visibility = if (recovery) View.VISIBLE else View.GONE
        tilCode.error = null
        tvError.visibility = View.GONE
        tvTwoFactorHint.setText(if (recovery) R.string.two_factor_recovery_hint else R.string.two_factor_hint)
        btnCodeMode.setText(if (recovery) R.string.two_factor_use_authenticator else R.string.two_factor_use_recovery)
        focusWithKeyboard(if (recovery) etCode else etOtp)
    }

    /** The six digits typed so far → the boxes; the full sixth digit submits by itself. */
    private fun onOtpChanged() {
        renderOtp()
        if (etOtp.length() == OTP_LENGTH && btnLogin.isEnabled) attemptCode()
    }

    private fun renderOtp() {
        val digits = etOtp.text?.toString() ?: ""
        val next = minOf(digits.length, OTP_LENGTH - 1)
        otpBoxes.forEachIndexed { i, box ->
            box.text = digits.getOrNull(i)?.toString() ?: ""
            box.isSelected = etOtp.hasFocus() && i == next
        }
    }

    /**
     * The server spends the pending token before it checks the code, so a refused code (or an expired token)
     * leaves nothing to retry with: back to the password step, still filled in, for a fresh token.
     */
    private fun onCodeRefused() {
        pendingAuthToken = null
        useRecoveryCode = false
        etOtp.text?.clear()
        etCode.text?.clear()
        tilEmail.isEnabled = true
        etEmail.isEnabled = true
        tilPassword.visibility = View.VISIBLE
        tvTwoFactorHint.visibility = View.GONE
        otpContainer.visibility = View.GONE
        tilCode.visibility = View.GONE
        btnCodeMode.visibility = View.GONE
        btnLogin.setText(R.string.login_sign_in)
        WindowCompat.getInsetsController(window, btnLogin).hide(WindowInsetsCompat.Type.ime())
        showError(R.string.two_factor_invalid_code)
    }

    private fun focusWithKeyboard(field: View) {
        field.requestFocus()
        WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        etEmail.isEnabled = !loading && pendingAuthToken == null
        etPassword.isEnabled = !loading
        etCode.isEnabled = !loading
        etOtp.isEnabled = !loading
        btnCodeMode.isEnabled = !loading
    }

    private fun showError(@StringRes message: Int) = showError(getString(message))

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private companion object {
        const val STATE_PENDING_TOKEN = "pending_auth_token"
        const val STATE_RECOVERY = "use_recovery_code"
        const val OTP_LENGTH = 6
    }
}
