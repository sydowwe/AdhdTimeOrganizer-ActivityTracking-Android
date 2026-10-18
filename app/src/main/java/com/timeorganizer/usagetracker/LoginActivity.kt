package com.timeorganizer.usagetracker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Password sign-in, then — for an account with two-factor authentication — the code step
 * (`/auth/login/2fa/extension` with the pending token the password step returned).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvTwoFactorHint: TextView
    private lateinit var tilCode: TextInputLayout
    private lateinit var etCode: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private lateinit var authPrefs: AuthPreferences

    /** Set while the code step is showing; lives about ten minutes on the server. */
    private var pendingAuthToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        authPrefs = AuthPreferences.get(this)

        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        tvTwoFactorHint = findViewById(R.id.tvTwoFactorHint)
        tilCode = findViewById(R.id.tilCode)
        etCode = findViewById(R.id.etCode)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        savedInstanceState?.getString(STATE_PENDING_TOKEN)?.let { showCodeStep(it) }
        if (savedInstanceState == null) {
            when (authPrefs.signOutReason) {
                SignOutReason.SESSION_ENDED -> showError(R.string.login_session_ended)
                SignOutReason.LOST_REFRESH -> showError(R.string.login_lost_refresh)
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
        val code = etCode.text?.toString()?.trim()?.replace(" ", "") ?: ""
        tilCode.error = null
        tvError.visibility = View.GONE

        if (code.isEmpty()) {
            tilCode.error = getString(R.string.two_factor_code_required)
            return
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
                    showError(if (pendingAuthToken == null) R.string.login_invalid_credentials else R.string.two_factor_invalid_code)
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

    private fun showCodeStep(token: String) {
        pendingAuthToken = token
        tilEmail.isEnabled = false
        tilPassword.visibility = View.GONE
        tvTwoFactorHint.visibility = View.VISIBLE
        tilCode.visibility = View.VISIBLE
        btnLogin.setText(R.string.two_factor_verify)
        etCode.requestFocus()
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        etEmail.isEnabled = !loading && pendingAuthToken == null
        etPassword.isEnabled = !loading
        etCode.isEnabled = !loading
    }

    private fun showError(@StringRes message: Int) = showError(getString(message))

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private companion object {
        const val STATE_PENDING_TOKEN = "pending_auth_token"
    }
}
