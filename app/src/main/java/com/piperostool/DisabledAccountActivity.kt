package com.piperostool

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date

class DisabledAccountActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_disabled_account)

        val root = findViewById<android.view.View>(R.id.disabledAccountRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val cached = AccountSessionGuard.cachedDisabled(this)
        val email = intent.getStringExtra(EXTRA_EMAIL) ?: cached?.email.orEmpty()
        val disabledAt = intent.getLongExtra(EXTRA_DISABLED_AT, cached?.disabledAt ?: 0L)
        val reason = (intent.getStringExtra(EXTRA_REASON) ?: cached?.reason)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.account_disabled_default_reason)
        findViewById<TextView>(R.id.disabledEmail).text = email.ifBlank { getString(R.string.account_unknown) }
        findViewById<TextView>(R.id.disabledTime).text = if (disabledAt > 0L) {
            DateFormat.getDateTimeInstance().format(Date(disabledAt))
        } else getString(R.string.account_unknown)
        findViewById<TextView>(R.id.disabledReason).text = reason

        findViewById<Button>(R.id.btnSendAppeal).setOnClickListener {
            val appealInput = findViewById<EditText>(R.id.appealReason)
            val appeal = appealInput.text.toString().trim()
            if (appeal.isBlank()) {
                appealInput.error = getString(R.string.account_appeal_required)
                return@setOnClickListener
            }
            val body = getString(R.string.account_appeal_email_body, email, disabledAt, reason, appeal)
            val mailUri = Uri.parse("mailto:$SUPPORT_EMAIL").buildUpon()
                .appendQueryParameter("subject", getString(R.string.account_appeal_subject, email))
                .appendQueryParameter("body", body)
                .build()
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, mailUri))
            } catch (_: ActivityNotFoundException) {
                PiperDialog.showMessage(
                    this,
                    getString(R.string.account_send_appeal),
                    getString(R.string.account_appeal_no_email_app, SUPPORT_EMAIL)
                )
            }
        }
        findViewById<Button>(R.id.btnDisabledSignOut).setOnClickListener {
            AccountSessionGuard.clearCachedDisabled(this)
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, WelcomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
        PiperModernUi.apply(root)
    }

    companion object {
        const val EXTRA_EMAIL = "disabled_email"
        const val EXTRA_DISABLED_AT = "disabled_at"
        const val EXTRA_REASON = "disabled_reason"
        private const val SUPPORT_EMAIL = "gayivt@gmail.com"

        fun createIntent(
            context: Context,
            state: AccountSessionState.Disabled,
            clearTask: Boolean = true
        ): Intent = Intent(context, DisabledAccountActivity::class.java).apply {
            putExtra(EXTRA_EMAIL, state.email)
            putExtra(EXTRA_DISABLED_AT, state.disabledAt ?: 0L)
            putExtra(EXTRA_REASON, state.reason)
            if (clearTask) {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }
}
