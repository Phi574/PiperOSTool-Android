package com.piperostool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import java.util.UUID

object DeviceSessionManager {
    private const val PREFS = "piper_device_sessions"
    private const val DEVICE_ID = "device_id"
    private const val SESSION_PREFIX = "session_"
    private const val TOUCH_INTERVAL_MS = 60_000L
    private var lastTouchAt = 0L

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID, it).apply()
        }
    }

    fun currentSessionId(context: Context, uid: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SESSION_PREFIX + uid, null)

    fun startNewSession(context: Context, onComplete: (Boolean) -> Unit = {}) {
        val user = FirebaseAuth.getInstance().currentUser ?: return onComplete(false)
        val previousSessionId = currentSessionId(context, user.uid)
        val sessionId = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SESSION_PREFIX + user.uid, sessionId)
            .apply()
        val now = System.currentTimeMillis()
        val data = deviceData(context, sessionId, now).toMutableMap().apply {
            put("loginAt", now)
            put("lastSeenAt", now)
            put("active", true)
            put("revoked", false)
            put("status", "active")
        }
        sessionDocument(user.uid, sessionId).set(data).addOnCompleteListener { task ->
            if (task.isSuccessful && previousSessionId != null && previousSessionId != sessionId) {
                sessionDocument(user.uid, previousSessionId).update(
                    mapOf(
                        "lastSeenAt" to now,
                        "signedOutAt" to now,
                        "active" to false,
                        "status" to "replaced"
                    )
                )
            }
            onComplete(task.isSuccessful)
        }
    }

    fun ensureCurrentSession(context: Context, onComplete: (Boolean) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return onComplete(false)
        val sessionId = currentSessionId(context, user.uid)
        if (sessionId == null) {
            startNewSession(context) { onComplete(false) }
            return
        }
        sessionDocument(user.uid, sessionId).get().addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener onComplete(false)
            val document = task.result
            if (document == null || !document.exists()) {
                val now = System.currentTimeMillis()
                sessionDocument(user.uid, sessionId)
                    .set(deviceData(context, sessionId, now) + mapOf(
                        "loginAt" to now,
                        "lastSeenAt" to now,
                        "active" to true,
                        "revoked" to false,
                        "status" to "active"
                    ))
                    .addOnCompleteListener { onComplete(false) }
            } else {
                val revoked = document.getBoolean("revoked") == true
                if (!revoked) touch(context)
                onComplete(revoked)
            }
        }
    }

    fun observeRevocation(context: Context, callback: (Boolean) -> Unit): ListenerRegistration? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        val sessionId = currentSessionId(context, user.uid) ?: return null
        return sessionDocument(user.uid, sessionId).addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null && snapshot.exists()) {
                callback(snapshot.getBoolean("revoked") == true)
            }
        }
    }

    fun touch(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTouchAt < TOUCH_INTERVAL_MS) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val sessionId = currentSessionId(context, user.uid) ?: return
        lastTouchAt = now
        sessionDocument(user.uid, sessionId).update(
            mapOf("lastSeenAt" to now, "active" to true, "status" to "active")
        )
    }

    fun endCurrentSession(context: Context, onComplete: () -> Unit = {}) {
        val user = FirebaseAuth.getInstance().currentUser ?: return onComplete()
        val sessionId = currentSessionId(context, user.uid) ?: return onComplete()
        val now = System.currentTimeMillis()
        sessionDocument(user.uid, sessionId).update(
            mapOf("lastSeenAt" to now, "signedOutAt" to now, "active" to false, "status" to "signed_out")
        ).addOnCompleteListener {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(SESSION_PREFIX + user.uid).apply()
            onComplete()
        }
    }

    fun revokeSession(uid: String, sessionId: String, revokedBy: String?, onComplete: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        sessionDocument(uid, sessionId).update(
            mapOf(
                "revoked" to true,
                "active" to false,
                "status" to "revoked",
                "revokedAt" to now,
                "revokedBy" to (revokedBy ?: "user")
            )
        ).addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun revokeOtherSessions(context: Context, onComplete: (Boolean) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return onComplete(false)
        val current = currentSessionId(context, user.uid)
        FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .collection("deviceSessions").get().addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener onComplete(false)
                val batch = FirebaseFirestore.getInstance().batch()
                val now = System.currentTimeMillis()
                task.result?.documents?.filter { it.id != current && it.getBoolean("revoked") != true }
                    ?.forEach { doc ->
                        batch.update(doc.reference, mapOf(
                            "revoked" to true, "active" to false, "status" to "revoked",
                            "revokedAt" to now, "revokedBy" to (current ?: "password_change")
                        ))
                    }
                batch.commit().addOnCompleteListener { onComplete(it.isSuccessful) }
            }
    }

    private fun sessionDocument(uid: String, sessionId: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("deviceSessions").document(sessionId)

    private fun deviceData(context: Context, sessionId: String, now: Long): Map<String, Any> {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val data = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "deviceId" to deviceId(context),
            "deviceName" to "$manufacturer ${Build.MODEL}".trim(),
            "manufacturer" to manufacturer,
            "model" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "abis" to Build.SUPPORTED_ABIS.joinToString(),
            "appVersion" to AppVersion.name(context),
            "updatedAt" to now
        )
        lastKnownLocation(context)?.let { location ->
            data["locationLat"] = String.format(Locale.US, "%.3f", location.latitude).toDouble()
            data["locationLon"] = String.format(Locale.US, "%.3f", location.longitude).toDouble()
        }
        return data
    }

    @Suppress("MissingPermission")
    private fun lastKnownLocation(context: Context): android.location.Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.getProviders(true).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }
}
