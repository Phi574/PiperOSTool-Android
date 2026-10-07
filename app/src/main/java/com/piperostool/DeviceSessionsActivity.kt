package com.piperostool

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.text.DateFormat
import java.util.Date

class DeviceSessionsActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var listener: ListenerRegistration? = null
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_sessions)
        val background = findViewById<ImageView>(R.id.homeBackground)
        background.visibility = View.GONE
        PiperModernUi.watch(this)
        findViewById<View>(R.id.btnSessionsBack).setOnClickListener { finish() }
        list = findViewById(R.id.deviceSessionsList)
        empty = findViewById(R.id.tvDeviceSessionsEmpty)
        observeSessions()
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    private fun observeSessions() {
        val user = auth.currentUser ?: return finish()
        val current = DeviceSessionManager.currentSessionId(this, user.uid)
        listener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(user.uid).collection("deviceSessions")
            .orderBy("loginAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    empty.visibility = View.VISIBLE
                    empty.text = getString(R.string.sessions_load_failed)
                    return@addSnapshotListener
                }
                list.removeAllViews()
                val docs = snapshot?.documents.orEmpty().filter { it.getBoolean("historyHidden") != true }
                empty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
                docs.forEach { doc -> addSessionItem(doc.data.orEmpty(), doc.id, doc.id == current) }
            }
    }

    private fun addSessionItem(data: Map<String, Any>, sessionId: String, isCurrent: Boolean) {
        val item = layoutInflater.inflate(R.layout.item_device_session, list, false)
        val revoked = data["revoked"] == true
        val active = data["active"] == true && !revoked
        val lastSeen = (data["lastSeenAt"] as? Number)?.toLong() ?: 0L
        item.findViewById<TextView>(R.id.tvSessionDeviceName).text =
            data["deviceName"]?.toString() ?: getString(R.string.sessions_unknown_device)
        item.findViewById<TextView>(R.id.tvSessionStatus).text = when {
            isCurrent -> getString(R.string.sessions_this_device)
            revoked -> getString(R.string.sessions_revoked)
            active && System.currentTimeMillis() - lastSeen < 120_000L -> getString(R.string.sessions_online)
            active -> getString(R.string.sessions_active)
            else -> getString(R.string.sessions_signed_out)
        }
        val location = if (data["locationLat"] != null && data["locationLon"] != null) {
            "${data["locationLat"]}, ${data["locationLon"]}"
        } else getString(R.string.sessions_location_unavailable)
        val loginAt = (data["loginAt"] as? Number)?.toLong() ?: 0L
        item.findViewById<TextView>(R.id.tvSessionDetails).text = getString(
            R.string.sessions_detail_format,
            data["androidVersion"]?.toString() ?: "-",
            data["appVersion"]?.toString() ?: "-",
            DateFormat.getDateTimeInstance().format(Date(loginAt)),
            DateFormat.getDateTimeInstance().format(Date(lastSeen)),
            location,
            data["deviceId"]?.toString()?.take(12) ?: "-"
        )
        item.findViewById<ImageView>(R.id.ivSessionDevice).imageTintList =
            ColorStateList.valueOf(if (active) PiperModernUi.accentColor(this) else PiperModernUi.secondaryTextColor(this))
        item.findViewById<Button>(R.id.btnRevokeSession).apply {
            visibility = if (isCurrent || !active) View.GONE else View.VISIBLE
            setOnClickListener {
                PiperDialog.showConfirm(
                    this@DeviceSessionsActivity,
                    getString(R.string.sessions_revoke_title),
                    getString(R.string.sessions_revoke_message),
                    getString(R.string.sessions_revoke_action),
                    destructive = true,
                ) {
                    val uid = auth.currentUser?.uid ?: return@showConfirm
                    DeviceSessionManager.revokeSession(
                        uid, sessionId, DeviceSessionManager.currentSessionId(this@DeviceSessionsActivity, uid)
                    ) { success ->
                        Toast.makeText(
                            this@DeviceSessionsActivity,
                            if (success) R.string.sessions_revoked_success else R.string.sessions_revoke_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        list.addView(item)
        PiperModernUi.apply(item)
        item.findViewById<View>(R.id.btnDeleteSession).apply {
            visibility = if (!isCurrent && (revoked || data["active"] == false)) View.VISIBLE else View.GONE
            setOnClickListener {
                PiperDialog.showConfirm(this@DeviceSessionsActivity,
                    getString(R.string.sessions_delete_history),
                    getString(R.string.sessions_delete_message),
                    getString(R.string.sessions_delete_history), destructive = true) {
                    isEnabled = false
                    DeviceSessionManager.removeEndedSession(this@DeviceSessionsActivity, sessionId) { success ->
                        if (!isDestroyed && !isFinishing && !success) {
                            isEnabled = true
                            Toast.makeText(this@DeviceSessionsActivity, R.string.sessions_delete_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

}
