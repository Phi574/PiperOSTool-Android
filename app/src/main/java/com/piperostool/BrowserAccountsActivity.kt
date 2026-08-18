package com.piperostool

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date

data class PendingBrowserCredential(
    val site: String,
    val origin: String,
    val username: String,
    val password: String
)

object BrowserCredentialCaptureSession {
    @Volatile var pending: PendingBrowserCredential? = null
    fun consume(): PendingBrowserCredential? = pending.also { pending = null }
}

class BrowserAccountsActivity : AppCompatActivity() {
    private val vault = BrowserCredentialVault()
    private lateinit var root: View
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_browser_accounts)
        root = findViewById(R.id.browserAccountsRoot)
        list = findViewById(R.id.accountsList)
        progress = findViewById(R.id.accountsProgress)
        subtitle = findViewById(R.id.accountsSubtitle)
        applyInsets()
        findViewById<ImageButton>(R.id.btnAccountsBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnVaultSecurity).setOnClickListener { showSecurityOptions() }
        PiperModernUi.apply(root)
        PiperAutoFont.watch(root)

        if (FirebaseAuth.getInstance().currentUser == null) {
            showEmpty(getString(R.string.browser_accounts_sign_in_required))
            return
        }
        openVault()
    }

    override fun onDestroy() {
        if (isFinishing) {
            BrowserCredentialCaptureSession.pending = null
            BrowserVaultSession.clear()
        }
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun openVault() {
        setBusy(true)
        vault.isConfigured { result -> runOnUiThread {
            setBusy(false)
            result.onFailure { showError(it); return@runOnUiThread }
            if (result.getOrDefault(false)) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                if (BrowserVaultSession.keyFor(uid) != null) loadAccounts()
                else showUnlockDialog()
            } else {
                showCreatePinDialog()
            }
        } }
    }

    private fun showUnlockDialog() {
        val pin = secureInput(getString(R.string.browser_vault_pin_hint))
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.browser_vault_unlock),
            message = getString(R.string.browser_vault_unlock_message),
            icon = R.drawable.ic_browser_lock,
            content = pin,
            positiveLabel = getString(R.string.unlock),
            neutralLabel = getString(R.string.browser_vault_start_over),
            onPositive = {
                val value = pin.text.toString()
                if (value.length < 4) return@showCustom false
                setBusy(true)
                vault.unlock(value) { result -> runOnUiThread {
                    pin.text?.clear()
                    setBusy(false)
                    result.fold(
                        onSuccess = { loadAccounts() },
                        onFailure = { showErrorMessage(getString(R.string.browser_vault_wrong_pin)) }
                    )
                } }
                true
            },
            onNeutral = { confirmResetVault(returnToUnlock = true) },
            onNegative = { finish() }
        ).setCancelable(false)
    }

    private fun showCreatePinDialog(reset: Boolean = false) {
        val first = secureInput(getString(R.string.browser_vault_new_pin))
        val second = secureInput(getString(R.string.browser_vault_confirm_pin))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(first)
            addView(second, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(if (reset) R.string.browser_vault_reset else R.string.browser_vault_create),
            message = getString(R.string.browser_vault_pin_security_note),
            icon = R.drawable.ic_browser_lock,
            content = content,
            positiveLabel = getString(R.string.save),
            onPositive = {
                val pin = first.text.toString()
                if (pin.length < 4 || pin != second.text.toString()) {
                    showErrorMessage(getString(R.string.browser_vault_pin_mismatch))
                    return@showCustom false
                }
                setBusy(true)
                vault.create(pin, clearExisting = reset) { result -> runOnUiThread {
                    first.text?.clear(); second.text?.clear(); setBusy(false)
                    result.fold(onSuccess = { loadAccounts() }, onFailure = ::showError)
                } }
                true
            },
            onNegative = {
                if (!reset) {
                    finish()
                } else {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    if (BrowserVaultSession.keyFor(uid) == null) showUnlockDialog() else loadAccounts()
                }
            }
        ).setCancelable(false)
    }

    private fun confirmResetVault(returnToUnlock: Boolean = false) {
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.browser_vault_reset),
            message = getString(R.string.browser_vault_reset_warning),
            positiveLabel = getString(R.string.browser_vault_delete_and_reset),
            destructive = true,
            onPositive = {
                showCreatePinDialog(reset = true)
                true
            },
            onNegative = { if (returnToUnlock) showUnlockDialog() }
        )
    }

    private fun showSecurityOptions() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (BrowserVaultSession.keyFor(uid) == null) {
            showUnlockDialog()
            return
        }
        val old = secureInput(getString(R.string.browser_vault_old_pin))
        val fresh = secureInput(getString(R.string.browser_vault_new_pin))
        val confirm = secureInput(getString(R.string.browser_vault_confirm_pin))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(old); addView(fresh); addView(confirm)
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.browser_vault_security),
            message = getString(R.string.browser_vault_change_pin_message),
            icon = R.drawable.ic_browser_lock,
            content = content,
            positiveLabel = getString(R.string.save),
            neutralLabel = getString(R.string.browser_vault_start_over),
            onPositive = {
                val newPin = fresh.text.toString()
                if (newPin.length < 4 || newPin != confirm.text.toString()) return@showCustom false
                setBusy(true)
                vault.changePin(old.text.toString(), newPin) { result -> runOnUiThread {
                    old.text?.clear(); fresh.text?.clear(); confirm.text?.clear(); setBusy(false)
                    result.fold(
                        onSuccess = { toast(getString(R.string.browser_vault_pin_changed)) },
                        onFailure = { showErrorMessage(getString(R.string.browser_vault_wrong_pin)) }
                    )
                } }
                true
            },
            onNeutral = { confirmResetVault() }
        )
    }

    private fun loadAccounts() {
        setBusy(true)
        subtitle.text = getString(R.string.browser_accounts_syncing)
        vault.load { result -> runOnUiThread {
            setBusy(false)
            result.fold(
                onSuccess = { credentials ->
                    subtitle.text = resources.getQuantityString(
                        R.plurals.browser_accounts_count,
                        credentials.size,
                        credentials.size
                    )
                    render(credentials)
                    BrowserCredentialCaptureSession.consume()?.let { pending ->
                        val existing = credentials.firstOrNull {
                            it.origin.equals(pending.origin, ignoreCase = true) &&
                                it.username.equals(pending.username, ignoreCase = true)
                        }
                        showEditDialog(existing, pending)
                    }
                },
                onFailure = ::showError
            )
        } }
    }

    private fun render(credentials: List<BrowserCredential>) {
        list.removeAllViews()
        if (credentials.isEmpty()) {
            showEmpty(getString(R.string.browser_accounts_empty))
            return
        }
        credentials.forEach { list.addView(accountCard(it)) }
    }

    private fun accountCard(item: BrowserCredential): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(12))
        background = surface()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }

        addView(TextView(this@BrowserAccountsActivity).apply {
            text = item.site
            textSize = 17f
            setTextColor(PiperModernUi.textColor(this@BrowserAccountsActivity))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@BrowserAccountsActivity).apply {
            text = item.username.ifBlank { getString(R.string.browser_account_no_username) }
            textSize = 14f
            setTextColor(PiperModernUi.secondaryTextColor(this@BrowserAccountsActivity))
        })
        addView(TextView(this@BrowserAccountsActivity).apply {
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            text = getString(
                R.string.browser_account_dates,
                format.format(Date(item.createdAt)),
                format.format(Date(item.updatedAt))
            )
            textSize = 11f
            setTextColor(PiperModernUi.secondaryTextColor(this@BrowserAccountsActivity))
        })
        addView(LinearLayout(this@BrowserAccountsActivity).apply {
            gravity = Gravity.END
            addView(actionButton(getString(R.string.edit)) { showEditDialog(item, null) })
            addView(actionButton(getString(R.string.delete), destructive = true) {
                confirmDelete(item)
            })
        })
    }

    private fun showEditDialog(existing: BrowserCredential?, pending: PendingBrowserCredential?) {
        val site = textInput(getString(R.string.browser_account_site), pending?.site ?: existing?.site.orEmpty())
        val username = textInput(getString(R.string.browser_account_username), pending?.username ?: existing?.username.orEmpty())
        val password = secureInput(getString(R.string.browser_account_password)).apply {
            setText(pending?.password ?: existing?.password.orEmpty())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(site); addView(username); addView(password)
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(if (existing == null) R.string.browser_account_add else R.string.browser_account_edit),
            content = content,
            positiveLabel = getString(R.string.save),
            onPositive = {
                if (site.text.isNullOrBlank() || password.text.isNullOrEmpty()) return@showCustom false
                setBusy(true)
                vault.save(
                    site = site.text.toString(),
                    origin = pending?.origin ?: existing?.origin.orEmpty(),
                    username = username.text.toString(),
                    password = password.text.toString(),
                    existingId = existing?.id,
                    createdAt = existing?.createdAt
                ) { result -> runOnUiThread {
                    password.text?.clear(); setBusy(false)
                    result.fold(onSuccess = { loadAccounts() }, onFailure = ::showError)
                } }
                true
            }
        )
    }

    private fun confirmDelete(item: BrowserCredential) {
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.browser_account_delete_title),
            message = getString(R.string.browser_account_delete_message, item.site),
            positiveLabel = getString(R.string.delete),
            destructive = true
        ) {
            setBusy(true)
            vault.delete(item.id) { result -> runOnUiThread {
                setBusy(false)
                result.fold(onSuccess = { loadAccounts() }, onFailure = ::showError)
            } }
        }
    }

    private fun showEmpty(message: String) {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(80), dp(24), dp(24))
            textSize = 15f
            setTextColor(PiperModernUi.secondaryTextColor(this@BrowserAccountsActivity))
        })
        subtitle.text = message
        setBusy(false)
    }

    private fun secureInput(hint: String) = textInput(hint, "").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun textInput(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(PiperModernUi.textColor(this@BrowserAccountsActivity))
        setHintTextColor(PiperModernUi.secondaryTextColor(this@BrowserAccountsActivity))
        background = surface()
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(8) }
    }

    private fun actionButton(label: String, destructive: Boolean = false, click: () -> Unit) =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(
                if (destructive) android.graphics.Color.rgb(176, 50, 58)
                else PiperModernUi.accentColor(this@BrowserAccountsActivity)
            )
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { click() }
        }

    private fun surface() = GradientDrawable().apply {
        setColor(PiperModernUi.surfaceColor(this@BrowserAccountsActivity))
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), PiperModernUi.borderColor(this@BrowserAccountsActivity))
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun showError(error: Throwable) =
        showErrorMessage(error.message ?: getString(R.string.browser_vault_error))

    private fun showErrorMessage(message: String) =
        PiperDialog.showMessage(this, getString(R.string.error), message)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
