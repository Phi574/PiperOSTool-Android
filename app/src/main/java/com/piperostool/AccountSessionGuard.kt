package com.piperostool

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.ListenerRegistration

sealed interface AccountSessionState {
    data object Valid : AccountSessionState
    data object Offline : AccountSessionState
    data class Expired(val message: String? = null) : AccountSessionState
}

/** Checks authentication and device-session revocation without an account-block page. */
object AccountSessionGuard {
    fun verify(context: Context, callback: (AccountSessionState) -> Unit) {
        // The side-by-side UI test package has no account data. Keep it isolated
        // from the production sign-in flow so instrumentation can exercise Home.
        if (context.packageName == "com.piper.os.tool.test") {
            callback(AccountSessionState.Valid)
            return
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: return callback(AccountSessionState.Expired())
        if (!NetworkAccess.isOnline(context)) {
            callback(AccountSessionState.Offline)
            return
        }
        user.reload().addOnCompleteListener { reload ->
            if (!reload.isSuccessful) {
                callback(classifyFailure(reload.exception))
                return@addOnCompleteListener
            }
            user.getIdToken(false).addOnCompleteListener { token ->
                if (!token.isSuccessful) {
                    callback(classifyFailure(token.exception))
                    return@addOnCompleteListener
                }
                DeviceSessionManager.ensureCurrentSession(context) { revoked ->
                    callback(if (revoked) AccountSessionState.Expired("Device session revoked")
                    else AccountSessionState.Valid)
                }
            }
        }
    }

    fun observe(context: Context, callback: (AccountSessionState) -> Unit): Observation? {
        if (FirebaseAuth.getInstance().currentUser == null) {
            callback(AccountSessionState.Expired())
            return null
        }
        val observation = Observation()
        DeviceSessionManager.ensureCurrentSession(context) { revoked ->
            if (revoked) callback(AccountSessionState.Expired("Device session revoked"))
            observation.add(DeviceSessionManager.observeRevocation(context) { isRevoked ->
                if (isRevoked) callback(AccountSessionState.Expired("Device session revoked"))
            })
        }
        return observation
    }

    class Observation internal constructor() {
        private val firestoreListeners = mutableListOf<ListenerRegistration>()
        private var closed = false

        @Synchronized
        internal fun add(registration: ListenerRegistration?) {
            if (registration == null) return
            if (closed) registration.remove() else firestoreListeners += registration
        }

        @Synchronized
        fun close() {
            closed = true
            firestoreListeners.forEach { it.remove() }
            firestoreListeners.clear()
        }
    }

    private fun classifyFailure(error: Exception?): AccountSessionState {
        if (error is FirebaseNetworkException) return AccountSessionState.Offline
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        return if (code in setOf("ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED",
                "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND")) {
            AccountSessionState.Expired(error?.message)
        } else AccountSessionState.Offline
    }
}
