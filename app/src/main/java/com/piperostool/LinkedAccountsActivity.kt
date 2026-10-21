package com.piperostool

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.launch

class LinkedAccountsActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var content: LinearLayout
    private lateinit var accountText: TextView
    private lateinit var status: TextView
    private lateinit var emailStatus: TextView
    private lateinit var googleStatus: TextView
    private lateinit var phoneStatus: TextView
    private lateinit var googleButton: MaterialButton
    private lateinit var phoneButton: MaterialButton
    private lateinit var unlinkButton: MaterialButton
    private var busy = false

    private val phoneLinkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) showStatus(getString(R.string.linked_accounts_done))
        refreshProviders()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PiperModernUi.watch(this)
        setContentView(buildScreen())
        refreshProviders()
    }

    private fun buildScreen(): android.view.View {
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@LinkedAccountsActivity, R.color.remote_page_background))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(content)
        content.addView(TextView(this).apply {
            text = "‹   ${getString(R.string.back)}"
            textSize = 15f
            setTextColor(PiperModernUi.secondaryTextColor(this@LinkedAccountsActivity))
            setPadding(0, dp(4), 0, dp(15))
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.linked_accounts_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@LinkedAccountsActivity))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.linked_accounts_summary)
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(this@LinkedAccountsActivity))
            setPadding(0, dp(6), 0, dp(16))
        })
        accountText = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@LinkedAccountsActivity))
        }
        content.addView(accountText)
        content.addView(providerCard(R.string.linked_accounts_password).also { emailStatus = it.second }.first)
        content.addView(providerCard(R.string.linked_accounts_google).also { googleStatus = it.second }.first)
        content.addView(providerCard(R.string.linked_accounts_phone).also { phoneStatus = it.second }.first)
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(this@LinkedAccountsActivity))
            setPadding(0, dp(12), 0, dp(10))
        }
        content.addView(status)
        googleButton = button(R.string.linked_accounts_link_google) { linkGoogle() }
        phoneButton = button(R.string.linked_accounts_link_phone) {
            phoneLinkLauncher.launch(Intent(this, PhoneAuthActivity::class.java).putExtra(PhoneAuthActivity.EXTRA_LINK_TO_CURRENT_USER, true))
        }
        unlinkButton = button(R.string.linked_accounts_unlink_phone) { confirmUnlinkPhone() }
        content.addView(googleButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        content.addView(phoneButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        content.addView(unlinkButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        scroll.fitsSystemWindows = true
        return scroll
    }

    private fun providerCard(label: Int): Pair<MaterialCardView, TextView> {
        lateinit var state: TextView
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@LinkedAccountsActivity))
            radius = dp(15).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(PiperModernUi.borderColor(this@LinkedAccountsActivity))
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                addView(TextView(context).apply {
                    text = getString(label)
                    textSize = 14f
                    setTextColor(PiperModernUi.textColor(this@LinkedAccountsActivity))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                state = TextView(context).apply { textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
                addView(state)
            }
            addView(row)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        return card to state
    }

    private fun button(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = getString(label)
        isAllCaps = false
        setOnClickListener { if (!busy) action() }
    }

    private fun refreshProviders() {
        val user = auth.currentUser
        if (user == null) {
            accountText.text = getString(R.string.linked_accounts_no_user)
            googleButton.isEnabled = false; phoneButton.isEnabled = false; unlinkButton.isEnabled = false
            return
        }
        accountText.text = getString(R.string.linked_accounts_current_email, user.email ?: user.phoneNumber ?: user.uid)
        setStatus(emailStatus, user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID })
        setStatus(googleStatus, user.providerData.any { it.providerId == "google.com" })
        setStatus(phoneStatus, user.providerData.any { it.providerId == PhoneAuthProvider.PROVIDER_ID })
        val hasGoogle = user.providerData.any { it.providerId == "google.com" }
        val hasPhone = user.providerData.any { it.providerId == PhoneAuthProvider.PROVIDER_ID }
        val hasOtherMethod = user.providerData.any { it.providerId != PhoneAuthProvider.PROVIDER_ID }
        googleButton.isEnabled = !busy && !hasGoogle
        phoneButton.isEnabled = !busy && !hasPhone
        unlinkButton.isEnabled = !busy && hasPhone && hasOtherMethod
        unlinkButton.alpha = if (unlinkButton.isEnabled) 1f else 0.5f
    }

    private fun setStatus(view: TextView, linked: Boolean) {
        view.text = getString(if (linked) R.string.linked_accounts_provider_linked else R.string.linked_accounts_provider_unlinked)
        view.setTextColor(ContextCompat.getColor(this, if (linked) R.color.remote_accent else android.R.color.darker_gray))
    }

    private fun linkGoogle() {
        if (auth.currentUser == null) return showStatus(getString(R.string.linked_accounts_no_user))
        busy = true
        refreshProviders()
        GoogleFirebaseSignIn.launch(
            activity = this,
            auth = auth,
            onBusy = { busy = it; refreshProviders() },
            onComplete = { linkedUser, _, error ->
                busy = false
                if (error != null) showStatus(linkError(error))
                else if (linkedUser != null) showStatus(getString(R.string.linked_accounts_done))
                refreshProviders()
            },
            linkToCurrentUser = true
        )
    }

    private fun confirmUnlinkPhone() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.linked_accounts_phone_confirm_title)
            .setMessage(R.string.linked_accounts_phone_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.linked_accounts_unlink_phone) { _, _ -> unlinkPhone() }
            .show()
    }

    private fun unlinkPhone() {
        val user = auth.currentUser ?: return showStatus(getString(R.string.linked_accounts_no_user))
        if (user.providerData.none { it.providerId != PhoneAuthProvider.PROVIDER_ID }) {
            showStatus(getString(R.string.linked_accounts_last_method)); return
        }
        busy = true; refreshProviders()
        user.unlink(PhoneAuthProvider.PROVIDER_ID).addOnCompleteListener(this) { task ->
            busy = false
            if (task.isSuccessful) showStatus(getString(R.string.linked_accounts_done))
            else showStatus(linkError(task.exception ?: IllegalStateException()))
            refreshProviders()
        }
    }

    private fun linkError(error: Exception): String = when {
        (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode == "ERROR_CREDENTIAL_ALREADY_IN_USE" -> getString(R.string.linked_accounts_linked_elsewhere)
        (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode == "ERROR_REQUIRES_RECENT_LOGIN" -> getString(R.string.linked_accounts_recent_login)
        error is GoogleAccountEmailMismatchException -> getString(R.string.linked_accounts_google_email_mismatch)
        else -> AuthProviderMessages.text(this, error)
    }

    private fun showStatus(message: String) {
        if (!::status.isInitialized) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); return }
        status.text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
