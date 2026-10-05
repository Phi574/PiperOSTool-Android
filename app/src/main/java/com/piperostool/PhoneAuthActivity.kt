package com.piperostool

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.hbb20.CountryCodePicker
import java.util.concurrent.TimeUnit

class PhoneAuthActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var countryPicker: CountryCodePicker
    private lateinit var phoneInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var sendCode: Button
    private lateinit var verifyCode: Button
    private lateinit var resendCode: Button
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var busy = false

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            signInWithPhoneCredential(credential, allowWhileBusy = true)
        }

        override fun onVerificationFailed(error: FirebaseException) {
            setBusy(false)
            val message = when {
                error is FirebaseAuthMissingActivityForRecaptchaException ->
                    getString(R.string.auth_phone_config_error)
                error is FirebaseAuthInvalidCredentialsException ->
                    getString(R.string.auth_phone_invalid)
                (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode in
                    setOf("ERROR_TOO_MANY_REQUESTS", "ERROR_QUOTA_EXCEEDED") ->
                    getString(R.string.auth_phone_rate_limited)
                else -> getString(R.string.auth_phone_failed)
            }
            showStatus(message)
        }

        override fun onCodeSent(
            sentVerificationId: String,
            token: PhoneAuthProvider.ForceResendingToken
        ) {
            setBusy(false)
            verificationId = sentVerificationId
            resendToken = token
            codeInput.visibility = View.VISIBLE
            sendCode.visibility = View.GONE
            verifyCode.visibility = View.VISIBLE
            resendCode.visibility = View.VISIBLE
            showStatus(getString(R.string.auth_phone_code_sent))
        }

        override fun onCodeAutoRetrievalTimeOut(id: String) {
            verificationId = id
            showStatus(getString(R.string.auth_phone_timeout))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_auth)
        root = findViewById(R.id.phoneAuthRoot)
        AuthScreenUi.apply(
            this,
            root,
            findViewById(R.id.authBackground),
            findViewById(R.id.modernAuthOverlay)
        )
        countryPicker = findViewById(R.id.phoneCountryPicker)
        phoneInput = findViewById(R.id.edtPhoneNumber)
        nameInput = findViewById(R.id.edtPhoneDisplayName)
        codeInput = findViewById(R.id.edtPhoneCode)
        status = findViewById(R.id.tvPhoneAuthStatus)
        sendCode = findViewById(R.id.btnPhoneSendCode)
        verifyCode = findViewById(R.id.btnPhoneVerify)
        resendCode = findViewById(R.id.btnPhoneResend)
        countryPicker.registerCarrierNumberEditText(phoneInput)
        nameInput.setText(intent.getStringExtra(EXTRA_PREFERRED_NAME).orEmpty())
        findViewById<TextView>(R.id.phoneAuthVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))
        findViewById<View>(R.id.btnPhoneBack).setOnClickListener { finish() }
        sendCode.setOnClickListener { NetworkAccess.requireOnline(root) { requestCode() } }
        resendCode.setOnClickListener { NetworkAccess.requireOnline(root) { requestCode(resend = true) } }
        verifyCode.setOnClickListener { verifyEnteredCode() }
        NetworkAccess.observe(this, this) { online ->
            sendCode.visibility = if (online && verificationId == null) View.VISIBLE else View.GONE
            if (!online) NetworkAccess.showOffline(root)
        }
    }

    private fun requestCode(resend: Boolean = false) {
        if (busy) return
        if (!countryPicker.isValidFullNumber) {
            phoneInput.error = getString(R.string.auth_phone_invalid)
            return
        }
        val number = countryPicker.fullNumberWithPlus
        if (!number.matches(Regex("^\\+[1-9]\\d{7,14}$"))) {
            phoneInput.error = getString(R.string.auth_phone_invalid)
            return
        }
        auth.useAppLanguage()
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(number)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
        if (resend) resendToken?.let(builder::setForceResendingToken)
        setBusy(true, sending = true)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    private fun verifyEnteredCode() {
        val id = verificationId ?: return requestCode()
        val code = codeInput.text.toString().trim()
        if (code.length !in 4..10) {
            codeInput.error = getString(R.string.auth_phone_code_invalid)
            return
        }
        signInWithPhoneCredential(PhoneAuthProvider.getCredential(id, code))
    }

    private fun signInWithPhoneCredential(credential: PhoneAuthCredential, allowWhileBusy: Boolean = false) {
        if (busy && !allowWhileBusy) return
        setBusy(true)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (!task.isSuccessful) {
                setBusy(false)
                showStatus(AuthProviderMessages.text(this, task.exception))
                return@addOnCompleteListener
            }
            val user = auth.currentUser
            if (user == null) {
                setBusy(false)
                showStatus(getString(R.string.auth_phone_failed))
                return@addOnCompleteListener
            }
            AuthSocialProfile.createForNewUser(
                this,
                user,
                task.result.additionalUserInfo?.isNewUser == true,
                nameInput.text.toString()
            ) {
                AuthPostLogin.continueToApp(this, user.uid)
            }
        }
    }

    private fun setBusy(value: Boolean, sending: Boolean = false) {
        busy = value
        phoneInput.isEnabled = !value
        countryPicker.isEnabled = !value
        nameInput.isEnabled = !value
        codeInput.isEnabled = !value
        sendCode.isEnabled = !value
        verifyCode.isEnabled = !value
        resendCode.isEnabled = !value
        val label = if (sending) R.string.auth_phone_sending else R.string.auth_phone_verifying
        if (value) showStatus(getString(label))
        listOf(sendCode, verifyCode, resendCode).forEach { it.alpha = if (value) 0.55f else 1f }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_PREFERRED_NAME = "phone_auth_preferred_name"
    }
}
