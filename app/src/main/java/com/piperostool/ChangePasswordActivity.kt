package com.piperostool

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth

class ChangePasswordActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var oldPassword: EditText
    private lateinit var newPassword: EditText
    private lateinit var confirmPassword: EditText
    private lateinit var submit: MaterialButton
    private lateinit var forgot: TextView
    private lateinit var policyHint: TextView
    private var submitting = false
    private var sendingReset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContent())
        PiperModernUi.watch(this)
        confirmPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateMatch(showError = true)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        newPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePolicyHint()
                validateMatch(showError = true)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        submit.setOnClickListener { changePassword() }
        forgot.setOnClickListener { sendResetEmail() }
        updateResetButtonState()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(24))
            setBackgroundColor(ContextCompat.getColor(this@ChangePasswordActivity, R.color.remote_page_background))
        }
        root.addView(TextView(this).apply {
            text = "‹   ${getString(R.string.back)}"
            setTextColor(PiperModernUi.secondaryTextColor(this@ChangePasswordActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(8), 0, dp(14))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.security_change_password)
            setTextColor(PiperModernUi.textColor(this@ChangePasswordActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.sessions_change_password_note)
            setTextColor(PiperModernUi.secondaryTextColor(this@ChangePasswordActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(20))
        })
        oldPassword = passwordField(R.string.sessions_old_password, root)
        newPassword = passwordField(R.string.sessions_new_password, root)
        confirmPassword = passwordField(R.string.sessions_confirm_password, root)
        policyHint = TextView(this).apply {
            setTextColor(PiperModernUi.secondaryTextColor(this@ChangePasswordActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(14))
            text = getString(R.string.auth_password_requirements_hint)
        }
        root.addView(policyHint)
        submit = MaterialButton(this).apply {
            text = getString(R.string.sessions_update_password)
            isAllCaps = false
        }
        root.addView(submit, LinearLayout.LayoutParams(-1, dp(52)))
        forgot = TextView(this).apply {
            text = getString(R.string.security_forgot_password)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { sendResetEmail() }
        }
        root.addView(forgot, LinearLayout.LayoutParams(-1, -2))
        return NestedScrollView(this).apply {
            isFillViewport = true
            addView(root, android.widget.FrameLayout.LayoutParams(-1, -2))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
                val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(0, top, 0, bottom)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }
    }

    private fun passwordField(hint: Int, parent: LinearLayout): EditText {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setTextColor(PiperModernUi.textColor(this@ChangePasswordActivity))
            setHintTextColor(PiperModernUi.secondaryTextColor(this@ChangePasswordActivity))
            setHint(hint)
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(PiperModernUi.surfaceColor(this@ChangePasswordActivity))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), PiperModernUi.borderColor(this@ChangePasswordActivity))
            }
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        return input
    }

    private fun validateMatch(showError: Boolean): Boolean {
        val confirmation = confirmPassword.text?.toString().orEmpty()
        val matches = confirmation.isEmpty() || confirmation == newPassword.text?.toString().orEmpty()
        confirmPassword.error = if (!matches && showError) getString(R.string.sessions_password_mismatch) else null
        return matches
    }

    private fun updatePolicyHint() {
        val missing = AuthPasswordPolicy.missingRequirements(newPassword.text?.toString().orEmpty())
        policyHint.text = if (missing.isEmpty()) getString(R.string.security_password_policy_met)
        else getString(R.string.auth_password_requirements_missing, missing.joinToString(", ") { getString(it) })
    }

    private fun changePassword() {
        if (submitting) return
        val user = auth.currentUser ?: return finish()
        val email = user.email?.trim().orEmpty()
        if (email.isBlank() || user.providerData.none { it.providerId == EmailAuthProvider.PROVIDER_ID }) {
            Toast.makeText(this, R.string.sessions_password_email_required, Toast.LENGTH_LONG).show()
            return
        }
        val old = oldPassword.text?.toString().orEmpty()
        val fresh = newPassword.text?.toString().orEmpty()
        if (old.isEmpty()) {
            oldPassword.error = getString(R.string.security_required_field)
            return
        }
        val missing = AuthPasswordPolicy.missingRequirements(fresh)
        if (missing.isNotEmpty()) {
            newPassword.error = getString(R.string.auth_password_requirements_missing, missing.joinToString(", ") { getString(it) })
            return
        }
        if (!validateMatch(showError = true) || confirmPassword.text?.isEmpty() == true) {
            if (confirmPassword.text?.isEmpty() == true) confirmPassword.error = getString(R.string.security_required_field)
            return
        }
        if (!NetworkAccess.isOnline(this)) {
            Toast.makeText(this, R.string.auth_network_error, Toast.LENGTH_LONG).show()
            return
        }
        submitting = true
        submit.isEnabled = false
        submit.text = getString(R.string.security_password_saving)
        user.reauthenticate(EmailAuthProvider.getCredential(email, old)).addOnCompleteListener(this) { reauth ->
            if (!reauth.isSuccessful) return@addOnCompleteListener finishSubmit(reauth.exception)
            user.updatePassword(fresh).addOnCompleteListener(this) { update ->
                if (!update.isSuccessful) return@addOnCompleteListener finishSubmit(update.exception)
                DeviceSessionManager.revokeOtherSessions(this) { revoked ->
                    Toast.makeText(this,
                        if (revoked) R.string.sessions_password_changed else R.string.security_password_changed_sessions_warning,
                        Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun finishSubmit(error: Exception?) {
        submitting = false
        submit.isEnabled = true
        submit.text = getString(R.string.sessions_update_password)
        showAuthError(this, error)
    }

    private fun sendResetEmail() {
        if (sendingReset) return
        val user = auth.currentUser ?: return finish()
        val email = user.email?.trim().orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, R.string.sessions_password_email_required, Toast.LENGTH_LONG).show()
            return
        }
        if (!NetworkAccess.isOnline(this)) {
            Toast.makeText(this, R.string.auth_network_error, Toast.LENGTH_LONG).show()
            return
        }
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(RESET_LIMIT_PREFS, MODE_PRIVATE)
        val key = resetLimitKey(user.uid)
        val blockedUntilKey = resetBlockedUntilKey(user.uid)
        val blockedUntil = prefs.getLong(blockedUntilKey, 0L)
        if (blockedUntil > now) {
            Toast.makeText(this, R.string.security_reset_rate_limited, Toast.LENGTH_LONG).show()
            return
        }
        val recent = prefs.getString(key, "").orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { now - it < RESET_WINDOW_MS }
        if (recent.size >= RESET_LIMIT) {
            prefs.edit().putLong(blockedUntilKey, now + RESET_WINDOW_MS).apply()
            updateResetButtonState()
            Toast.makeText(this, R.string.security_reset_rate_limited, Toast.LENGTH_LONG).show()
            return
        }
        sendingReset = true
        forgot.isEnabled = false
        forgot.text = getString(R.string.security_reset_sending)
        auth.useAppLanguage()
        auth.sendPasswordResetEmail(email).addOnCompleteListener(this) { task ->
            sendingReset = false
            if (task.isSuccessful) {
                val sentAt = System.currentTimeMillis()
                val sentTimes = recent + sentAt
                prefs.edit().putString(key, sentTimes.joinToString(",")).apply()
                if (sentTimes.size >= RESET_LIMIT) prefs.edit().putLong(blockedUntilKey, sentAt + RESET_WINDOW_MS).apply()
                Toast.makeText(this, R.string.security_reset_email_sent, Toast.LENGTH_LONG).show()
            } else showAuthError(this, task.exception)
            updateResetButtonState()
        }
    }

    private fun updateResetButtonState() {
        val user = auth.currentUser
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(RESET_LIMIT_PREFS, MODE_PRIVATE)
        val blocked = user?.let { prefs.getLong(resetBlockedUntilKey(it.uid), 0L) > now } ?: false
        forgot.text = getString(R.string.security_forgot_password)
        forgot.setTextColor(if (blocked) Color.GRAY else PiperModernUi.accentColor(this))
        forgot.alpha = if (blocked) 0.62f else 1f
        // Keep the view clickable while blocked so it can explain when access returns.
        forgot.isEnabled = !sendingReset
    }

    private fun resetLimitKey(uid: String) = "reset_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun resetBlockedUntilKey(uid: String) = "blocked_until_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val RESET_LIMIT_PREFS = "password_reset_rate_limit"
        private const val RESET_LIMIT = 3
        private const val RESET_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
