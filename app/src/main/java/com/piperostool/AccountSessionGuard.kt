package com.piperostool

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase

sealed interface AccountSessionState {
    data object Valid : AccountSessionState
    data object Offline : AccountSessionState
    data class Expired(val message: String? = null) : AccountSessionState
    data class Disabled(
        val email: String?,
        val disabledAt: Long?,
        val reason: String?
    ) : AccountSessionState
}

object AccountSessionGuard {
    fun verify(context: Context, callback: (AccountSessionState) -> Unit) {
        cachedDisabled(context)?.let {
            callback(it)
            return
        }
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
            ?: return callback(AccountSessionState.Expired())
        if (!NetworkAccess.isOnline(context)) {
            return callback(AccountSessionState.Offline)
        }

        FirebaseDatabase.getInstance()
            .getReference("users/${user.uid}")
            .get()
            .addOnCompleteListener { statusTask ->
                val disabled = if (statusTask.isSuccessful) {
                    statusTask.result?.let { disabledState(it, user.email) }
                } else {
                    null
                }
                if (disabled != null) {
                    rememberDisabled(context, disabled)
                    callback(disabled)
                } else {
                    verifyAuthToken(context, user.email, callback)
                }
            }
    }

    private fun verifyAuthToken(
        context: Context,
        email: String?,
        callback: (AccountSessionState) -> Unit
    ) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return callback(AccountSessionState.Expired())
        user.reload().addOnCompleteListener { reload ->
            if (!reload.isSuccessful) {
                callback(classifyFailure(context, reload.exception, email))
                return@addOnCompleteListener
            }
            user.getIdToken(true).addOnCompleteListener { token ->
                if (token.isSuccessful) {
                    clearCachedDisabled(context)
                    callback(AccountSessionState.Valid)
                } else {
                    callback(classifyFailure(context, token.exception, email))
                }
            }
        }
    }

    private fun disabledState(snapshot: DataSnapshot, email: String?): AccountSessionState.Disabled? {
        val accountStatus = snapshot.child("accountStatus")
        val status = accountStatus.stringStatus()
            ?: snapshot.child("status").getValue(String::class.java)?.lowercase()
        val enabled = accountStatus.child("enabled").getValue(Boolean::class.java)
            ?: snapshot.child("enabled").getValue(Boolean::class.java)
        val disabled = accountStatus.child("disabled").getValue(Boolean::class.java)
            ?: snapshot.child("disabled").getValue(Boolean::class.java)
        if (enabled != false && disabled != true && status !in setOf("disabled", "blocked", "suspended")) {
            return null
        }
        return AccountSessionState.Disabled(
            email = email,
            disabledAt = accountStatus.child("disabledAt").longValue()
                ?: accountStatus.child("disabled_at").longValue()
                ?: snapshot.child("disabledAt").longValue()
                ?: snapshot.child("disabled_at").longValue(),
            reason = accountStatus.child("reason").getValue(String::class.java)
                ?: accountStatus.child("disabledReason").getValue(String::class.java)
                ?: snapshot.child("reason").getValue(String::class.java)
                ?: snapshot.child("disabledReason").getValue(String::class.java)
                ?: snapshot.child("suspensionReason").getValue(String::class.java)
        )
    }

    fun disabledFromAuthFailure(
        context: Context,
        error: Exception?,
        email: String?
    ): AccountSessionState.Disabled? {
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        if (code != "ERROR_USER_DISABLED") return null
        return AccountSessionState.Disabled(email, System.currentTimeMillis(), null).also {
            rememberDisabled(context, it)
        }
    }

    fun cachedDisabled(context: Context): AccountSessionState.Disabled? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DISABLED, false)) return null
        return AccountSessionState.Disabled(
            email = prefs.getString(KEY_EMAIL, null),
            disabledAt = prefs.getLong(KEY_DISABLED_AT, 0L).takeIf { it > 0L },
            reason = prefs.getString(KEY_REASON, null)
        )
    }

    fun rememberDisabled(context: Context, state: AccountSessionState.Disabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISABLED, true)
            .putString(KEY_EMAIL, state.email)
            .putLong(KEY_DISABLED_AT, state.disabledAt ?: System.currentTimeMillis())
            .putString(KEY_REASON, state.reason)
            .apply()
    }

    fun clearCachedDisabled(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun DataSnapshot.stringStatus(): String? {
        return if (hasChildren()) {
            child("status").getValue(String::class.java)?.lowercase()
        } else {
            getValue(String::class.java)?.lowercase()
        }
    }

    private fun DataSnapshot.longValue(): Long? = when (val value = value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun classifyFailure(
        context: Context,
        error: Exception?,
        email: String?
    ): AccountSessionState {
        if (error is FirebaseNetworkException) return AccountSessionState.Offline
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        return when (code) {
            "ERROR_USER_DISABLED" -> AccountSessionState.Disabled(
                email,
                System.currentTimeMillis(),
                null
            ).also { rememberDisabled(context, it) }
            "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND" ->
                AccountSessionState.Expired(error?.message)
            else -> AccountSessionState.Offline
        }
    }

    private const val PREFS_NAME = "account_session_guard"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISABLED_AT = "disabled_at"
    private const val KEY_REASON = "reason"
}
