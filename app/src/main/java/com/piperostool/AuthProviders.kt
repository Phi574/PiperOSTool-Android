package com.piperostool

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch

object GoogleFirebaseSignIn {
    fun launch(
        activity: AppCompatActivity,
        auth: FirebaseAuth,
        onBusy: (Boolean) -> Unit,
        onComplete: (FirebaseUser?, Boolean, Exception?) -> Unit
    ) {
        val clientIdResource = activity.resources.getIdentifier(
            "default_web_client_id",
            "string",
            activity.packageName
        )
        if (clientIdResource == 0) {
            onComplete(null, false, GoogleProviderConfigurationException())
            return
        }
        val serverClientId = activity.getString(clientIdResource).takeIf(String::isNotBlank)
        if (serverClientId == null) {
            onComplete(null, false, GoogleProviderConfigurationException())
            return
        }

        val credentialManager = CredentialManager.create(activity)
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        onBusy(true)
        activity.lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (
                    credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    onBusy(false)
                    onComplete(null, false, IllegalStateException("Unsupported Google credential"))
                    return@launch
                }
                val googleToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(googleToken, null))
                    .addOnCompleteListener(activity) { task ->
                        onBusy(false)
                        if (task.isSuccessful) {
                            onComplete(
                                auth.currentUser,
                                task.result.additionalUserInfo?.isNewUser == true,
                                null
                            )
                        } else {
                            onComplete(null, false, task.exception)
                        }
                    }
            } catch (error: GetCredentialCancellationException) {
                onBusy(false)
                onComplete(null, false, error)
            } catch (error: GetCredentialException) {
                onBusy(false)
                onComplete(null, false, error)
            } catch (error: Exception) {
                onBusy(false)
                onComplete(null, false, error)
            }
        }
    }
}

class GoogleProviderConfigurationException : Exception()

object AuthProviderMessages {
    fun text(context: android.content.Context, error: Exception?): String {
        if (error is GoogleProviderConfigurationException) {
            return context.getString(R.string.auth_google_setup_required)
        }
        if (error is GetCredentialCancellationException) {
            return context.getString(R.string.auth_google_cancelled)
        }
        val firebaseError = error as? FirebaseAuthException
        val code = firebaseError?.errorCode.orEmpty()
        return when (code) {
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                context.getString(R.string.auth_account_exists_other_provider)
            "ERROR_INVALID_EMAIL" -> context.getString(R.string.auth_error_invalid_email)
            "ERROR_EMAIL_ALREADY_IN_USE" -> context.getString(R.string.auth_error_email_in_use)
            "ERROR_USER_DISABLED" -> context.getString(R.string.auth_error_user_disabled)
            "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND",
            "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIAL" ->
                context.getString(R.string.auth_invalid_credentials)
            "ERROR_PASSWORD_DOES_NOT_MEET_REQUIREMENTS", "ERROR_WEAK_PASSWORD" ->
                context.getString(R.string.auth_error_password_policy)
            "ERROR_OPERATION_NOT_ALLOWED" -> context.getString(R.string.auth_error_operation_disabled)
            "ERROR_TOO_MANY_REQUESTS" -> context.getString(R.string.auth_error_too_many_requests)
            "ERROR_NETWORK_REQUEST_FAILED" -> context.getString(R.string.auth_network_error)
            "ERROR_REQUIRES_RECENT_LOGIN" -> context.getString(R.string.auth_error_recent_login)
            "ERROR_EXPIRED_ACTION_CODE", "ERROR_INVALID_ACTION_CODE" ->
                context.getString(R.string.auth_error_expired_action)
            "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_API_KEY" ->
                context.getString(R.string.auth_error_app_configuration)
            else -> {
                val message = error?.localizedMessage?.takeIf(String::isNotBlank)
                    ?.replace(Regex("(?i)firebase:\\s*error\\s*\\("), "")
                    ?.removeSuffix(")")
                    ?.take(220)
                if (code.isNotBlank() && message != null) {
                    context.getString(R.string.auth_error_unknown_with_code, code, message)
                } else if (code.isNotBlank()) {
                    context.getString(R.string.auth_error_unknown_code, code)
                } else if (error is com.google.firebase.FirebaseException && message != null) {
                    context.getString(R.string.auth_error_unknown_with_code, "Firebase", message)
                } else {
                    context.getString(R.string.auth_provider_failed)
                }
            }
        }
    }

    fun requiresPasswordUpgrade(error: Exception?): Boolean {
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        val details = generateSequence<Throwable>(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .uppercase()
        return code == "ERROR_PASSWORD_DOES_NOT_MEET_REQUIREMENTS" ||
            code == "ERROR_WEAK_PASSWORD" ||
            error?.javaClass?.simpleName == "FirebaseAuthPasswordDoesNotMeetRequirementsException" ||
            details.contains("PASSWORD_DOES_NOT_MEET_REQUIREMENTS")
    }
}

object AuthPasswordPolicy {
    private val allowedSpecialCharacters = setOf(
        '^', '$', '*', '.', '[', ']', '{', '}', '(', ')', '?', '"', '!', '@', '#', '%', '&',
        '/', '\\', ',', '>', '<', '\'', ':', ';', '|', '_', '~'
    )

    fun missingRequirements(password: String): List<Int> = buildList {
        if (password.length < 6) add(R.string.auth_password_rule_minimum)
        if (password.length > 4096) add(R.string.auth_password_rule_maximum)
        if (password.none(Char::isLowerCase)) add(R.string.auth_password_rule_lowercase)
        if (password.none(Char::isUpperCase)) add(R.string.auth_password_rule_uppercase)
        if (password.none(Char::isDigit)) add(R.string.auth_password_rule_number)
        if (password.none(allowedSpecialCharacters::contains)) add(R.string.auth_password_rule_special)
    }
}

object AuthSocialProfile {
    fun createForNewUser(
        activity: AppCompatActivity,
        user: FirebaseUser,
        isNewUser: Boolean,
        suggestedName: String?,
        onComplete: () -> Unit
    ) {
        if (!isNewUser) {
            onComplete()
            return
        }
        val name = user.displayName?.takeIf(String::isNotBlank)
            ?: suggestedName?.trim()?.takeIf(String::isNotBlank)
            ?: user.phoneNumber
            ?: activity.getString(R.string.auth_default_display_name)
        val profile = UserProfileChangeRequest.Builder().setDisplayName(name).build()
        user.updateProfile(profile).addOnCompleteListener(activity) {
            val fields = mutableMapOf<String, Any>(
                "name" to name,
                "role" to "User",
                "createdAt" to System.currentTimeMillis()
            )
            user.email?.let { fields["email"] = it }
            user.phoneNumber?.let { fields["phoneNumber"] = it }
            FirebaseFirestore.getInstance().collection("users").document(user.uid)
                .set(fields, SetOptions.merge())
                .addOnCompleteListener(activity) { onComplete() }
        }
    }
}

object AuthPostLogin {
    fun continueToApp(activity: AppCompatActivity, userId: String) {
        DeviceSessionManager.startNewSession(activity) {
            AccountSessionGuard.verify(activity) { state ->
                if (activity.isFinishing || activity.isDestroyed) return@verify
                when (state) {
                    AccountSessionState.Valid, AccountSessionState.Offline ->
                        openProtectedDestination(activity, userId)
                    is AccountSessionState.Expired -> {
                        FirebaseAuth.getInstance().signOut()
                        openHomeOrLogin(activity, LoginActivity::class.java)
                    }
                }
            }
        }
    }

    private fun openProtectedDestination(activity: AppCompatActivity, userId: String) {
        val reference = FirebaseDatabase.getInstance()
            .getReference("users/$userId/security/password")
        reference.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                if (activity.isFinishing || activity.isDestroyed) return
                if (snapshot.exists() && snapshot.value?.toString()?.isNotEmpty() == true) {
                    val intent = Intent(activity, LockScreenActivity::class.java)
                        .putExtra("IS_UNLOCK_MODE", true)
                    openAndFinish(activity, intent)
                } else {
                    openHomeOrLogin(activity, HomeActivity::class.java)
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    openHomeOrLogin(activity, HomeActivity::class.java)
                }
            }
        })
    }

    private fun openHomeOrLogin(activity: AppCompatActivity, target: Class<*>) {
        openAndFinish(activity, Intent(activity, target))
    }

    private fun openAndFinish(activity: AppCompatActivity, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
        activity.finish()
    }
}

fun showAuthError(activity: AppCompatActivity, error: Exception?) {
    if (error is GetCredentialCancellationException) return
    Toast.makeText(activity, AuthProviderMessages.text(activity, error), Toast.LENGTH_LONG).show()
}
