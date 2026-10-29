package com.timeorganizer.usagetracker.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.timeorganizer.usagetracker.util.Constants

/**
 * "Sign in with Google" through Credential Manager: Google's account sheet, then an ID token for the server's web client
 * ([Constants.GOOGLE_SERVER_CLIENT_ID]). The token goes to `auth/tracker/login/google` as is; the server verifies it.
 * The token is never stored or logged: it names the user's email.
 */
object GoogleSignIn {

    sealed interface Outcome {
        data class Token(val idToken: String) : Outcome

        /** The user closed the sheet: say nothing. */
        data object Cancelled : Outcome

        data object NoAccount : Outcome
        data object Failed : Outcome
    }

    val isConfigured: Boolean get() = Constants.GOOGLE_SERVER_CLIENT_ID.isNotBlank()

    /** Shows Google's sheet over [activity]; suspends until the user picks an account or closes it. */
    suspend fun requestIdToken(activity: Activity): Outcome {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(Constants.GOOGLE_SERVER_CLIENT_ID).build())
            .build()

        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Outcome.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Outcome.Failed
            }
        } catch (_: GetCredentialCancellationException) {
            Outcome.Cancelled
        } catch (_: NoCredentialException) {
            Outcome.NoAccount
        } catch (_: GetCredentialException) {
            Outcome.Failed
        } catch (_: GoogleIdTokenParsingException) {
            Outcome.Failed
        }
    }
}
