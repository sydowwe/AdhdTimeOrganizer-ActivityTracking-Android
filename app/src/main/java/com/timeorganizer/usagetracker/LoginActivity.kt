package com.timeorganizer.usagetracker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.LoginRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private lateinit var authPrefs: AuthPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        authPrefs = AuthPreferences(this)

        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val email = etEmail.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""

        tilEmail.error = null
        tilPassword.error = null
        tvError.visibility = View.GONE

        if (email.isEmpty()) {
            tilEmail.error = "Email is required"
            return
        }
        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.authApiService.login(LoginRequest(email, password))
                } catch (e: Exception) {
                    null
                }
            }

            setLoading(false)

            when {
                result == null -> showError("Network error. Check your connection.")
                result.isSuccessful -> {
                    val body = result.body()
                    when {
                        body == null -> showError("Unexpected empty response from server.")
                        body.requiresTwoFactor -> showError("Two-factor authentication is required. Not supported in this app.")
                        body.accessToken == null || body.refreshToken == null ->
                            showError("Login failed: missing tokens in response.")
                        else -> {
                            authPrefs.saveTokens(body.accessToken, body.refreshToken)
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                }
                result.code() == 401 || result.code() == 403 -> {
                    showError("Invalid email or password.")
                }
                else -> {
                    showError("Login failed (${result.code()}). Try again.")
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
