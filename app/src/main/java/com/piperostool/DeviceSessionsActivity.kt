package com.piperostool

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.EmailAuthProvider
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
        PiperModernUi.apply(findViewById(R.id.deviceSessionsRoot))
        findViewById<View>(R.id.btnSessionsBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnChangeAccountPassword).setOnClickListener { showPasswordDialog() }
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
                val docs = snapshot?.documents.orEmpty()
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
            visibility = if (isCurrent || revoked) View.GONE else View.VISIBLE
            setOnClickListener {
                PiperDialog.showConfirm(
                    this@DeviceSessionsActivity,
                    getString(R.string.sessions_revoke_title),
                    getString(R.string.sessions_revoke_message),
                    getString(R.string.sessions_revoke_action),
                    destructive = true
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
        PiperModernUi.apply(item)
        list.addView(item)
    }

    private fun showPasswordDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun passwordField(hintRes: Int) = EditText(this).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(PiperModernUi.surfaceColor(this@DeviceSessionsActivity))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), PiperModernUi.borderColor(this@DeviceSessionsActivity))
            }
        }
        val old = passwordField(R.string.sessions_old_password)
        val fresh = passwordField(R.string.sessions_new_password)
        val confirm = passwordField(R.string.sessions_confirm_password)
        listOf(old, fresh, confirm).forEachIndexed { index, field ->
            fields.addView(field, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(10) })
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.sessions_change_password),
            message = getString(R.string.sessions_change_password_note),
            content = fields,
            positiveLabel = getString(R.string.sessions_update_password),
            onPositive = {
                when {
                    fresh.text.length < 6 -> {
                        fresh.error = getString(R.string.sessions_password_too_short); false
                    }
                    fresh.text.toString() != confirm.text.toString() -> {
                        confirm.error = getString(R.string.sessions_password_mismatch); false
                    }
                    else -> { changePassword(old.text.toString(), fresh.text.toString()); true }
                }
            }
        )
    }

    private fun changePassword(old: String, fresh: String) {
        val user = auth.currentUser ?: return
        val email = user.email ?: return Toast.makeText(this, R.string.sessions_password_email_required, Toast.LENGTH_LONG).show()
        user.reauthenticate(EmailAuthProvider.getCredential(email, old)).addOnSuccessListener {
            user.updatePassword(fresh).addOnSuccessListener {
                DeviceSessionManager.revokeOtherSessions(this) {}
                Toast.makeText(this, R.string.sessions_password_changed, Toast.LENGTH_LONG).show()
            }.addOnFailureListener { showPasswordError(it) }
        }.addOnFailureListener { showPasswordError(it) }
    }

    private fun showPasswordError(error: Exception) {
        Toast.makeText(this, getString(R.string.sessions_password_failed, error.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
