package com.piperostool

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val statusHandler = Handler(Looper.getMainLooper())
    private var accountObservation: AccountSessionGuard.Observation? = null
    private var checkingStatus = false
    private var leavingDisabledScreen = false
    private val statusCheck = object : Runnable {
        override fun run() {
            verifyCurrentStatus()
            statusHandler.postDelayed(this, STATUS_CHECK_INTERVAL_MS)
        }
    }

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
        findViewById<Button>(R.id.btnRecheckAccount).setOnClickListener {
            recheckAccount()
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

    private fun recheckAccount() {
        val button = findViewById<Button>(R.id.btnRecheckAccount)
        button.isEnabled = false
        button.text = getString(R.string.account_checking_status)
        if (FirebaseAuth.getInstance().currentUser == null) {
            openLoginForReauthentication()
            return
        }
        AccountSessionGuard.verify(this) { state ->
            button.isEnabled = true
            button.text = getString(R.string.account_recheck)
            when (state) {
                AccountSessionState.Valid -> handleAccountState(state)
                is AccountSessionState.Expired -> openLoginForReauthentication()
                AccountSessionState.Offline -> PiperDialog.showMessage(
                    this,
                    getString(R.string.account_recheck),
                    getString(R.string.offline_message)
                )
                is AccountSessionState.Disabled -> handleAccountState(state)
            }
        }
    }

    private fun openLoginForReauthentication() {
        leavingDisabledScreen = true
        accountObservation?.close()
        statusHandler.removeCallbacks(statusCheck)
        AccountSessionGuard.clearCachedDisabled(this)
        FirebaseAuth.getInstance().signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    override fun onStart() {
        super.onStart()
        accountObservation?.close()
        accountObservation = AccountSessionGuard.observe(this, ::handleAccountState)
        statusHandler.removeCallbacks(statusCheck)
        statusHandler.post(statusCheck)
    }

    override fun onStop() {
        accountObservation?.close()
        accountObservation = null
        statusHandler.removeCallbacks(statusCheck)
        super.onStop()
    }

    private fun verifyCurrentStatus() {
        if (checkingStatus || leavingDisabledScreen) return
        checkingStatus = true
        AccountSessionGuard.verify(this) { state ->
            checkingStatus = false
            handleAccountState(state)
        }
    }

    private fun handleAccountState(state: AccountSessionState) {
        if (leavingDisabledScreen) return
        when (state) {
            AccountSessionState.Valid -> {
                leavingDisabledScreen = true
                AccountSessionGuard.clearCachedDisabled(this)
                accountObservation?.close()
                statusHandler.removeCallbacks(statusCheck)
                startActivity(Intent(this, HomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            is AccountSessionState.Expired -> {
                if (AccountSessionGuard.cachedDisabled(this) != null) return
                leavingDisabledScreen = true
                AccountSessionGuard.clearCachedDisabled(this)
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    putExtra(SplashScreenActivity.EXTRA_SESSION_EXPIRED, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            AccountSessionState.Offline, is AccountSessionState.Disabled -> Unit
        }
    }

    companion object {
        const val EXTRA_EMAIL = "disabled_email"
        const val EXTRA_DISABLED_AT = "disabled_at"
        const val EXTRA_REASON = "disabled_reason"
        private const val SUPPORT_EMAIL = "gayivt@gmail.com"
        private const val STATUS_CHECK_INTERVAL_MS = 10_000L

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
