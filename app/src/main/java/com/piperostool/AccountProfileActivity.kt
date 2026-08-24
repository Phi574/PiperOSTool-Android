package com.piperostool

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class AccountProfileActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var uid: EditText
    private lateinit var fullName: EditText
    private lateinit var birthDate: EditText
    private lateinit var gender: Spinner
    private lateinit var phone: EditText
    private lateinit var email: EditText
    private lateinit var save: Button
    private lateinit var request: Button
    private lateinit var status: TextView
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_profile)
        PiperModernUi.apply(findViewById(R.id.accountProfileRoot))
        findViewById<android.view.View>(R.id.btnProfileBack).setOnClickListener { finish() }
        uid = findViewById(R.id.etProfileUid)
        fullName = findViewById(R.id.etProfileName)
        birthDate = findViewById(R.id.etProfileBirthDate)
        gender = findViewById(R.id.spinnerProfileGender)
        phone = findViewById(R.id.etProfilePhone)
        email = findViewById(R.id.etProfileEmail)
        save = findViewById(R.id.btnSaveProfile)
        request = findViewById(R.id.btnRequestProfileChange)
        status = findViewById(R.id.tvProfileStatus)
        gender.adapter = ArrayAdapter.createFromResource(
            this, R.array.profile_gender_labels, android.R.layout.simple_spinner_dropdown_item
        )
        uid.setText(auth.currentUser?.uid.orEmpty())
        email.setText(auth.currentUser?.email.orEmpty())
        email.isEnabled = false
        birthDate.setOnClickListener { if (!locked) pickBirthDate() }
        save.setOnClickListener { saveProfileOnce() }
        request.setOnClickListener { showChangeRequest() }
        loadProfile()
    }

    private fun profileDocument() = auth.currentUser?.uid?.let { uid ->
        db.collection("users").document(uid).collection("accountProfile").document("main")
    }

    private fun loadProfile() {
        val user = auth.currentUser ?: return finish()
        status.text = getString(R.string.profile_loading)
        val profile = profileDocument() ?: return finish()
        profile.get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                locked = true
                fullName.setText(doc.getString("fullName").orEmpty())
                birthDate.setText(doc.getString("dateOfBirth").orEmpty())
                phone.setText(doc.getString("phoneNumber").orEmpty())
                email.setText(doc.getString("email") ?: user.email.orEmpty())
                val code = doc.getString("gender").orEmpty()
                gender.setSelection(resources.getStringArray(R.array.profile_gender_values).indexOf(code).coerceAtLeast(0))
                setLockedUi()
            } else {
                db.collection("users").document(user.uid).get().addOnSuccessListener {
                    fullName.setText(it.getString("name").orEmpty())
                }
                status.text = getString(R.string.profile_one_time_warning)
                request.isEnabled = false
            }
        }.addOnFailureListener {
            status.text = getString(R.string.profile_load_failed)
        }
    }

    private fun setLockedUi() {
        listOf(fullName, birthDate, phone).forEach { it.isEnabled = false }
        gender.isEnabled = false
        save.isEnabled = false
        save.text = getString(R.string.profile_locked)
        request.isEnabled = true
        status.text = getString(R.string.profile_locked_message)
    }

    private fun saveProfileOnce() {
        val user = auth.currentUser ?: return
        val name = fullName.text.toString().trim()
        val dob = birthDate.text.toString().trim()
        val number = phone.text.toString().trim()
        if (name.length < 2 || dob.isBlank() || number.length < 7) {
            Toast.makeText(this, R.string.profile_complete_fields, Toast.LENGTH_LONG).show()
            return
        }
        val values = resources.getStringArray(R.array.profile_gender_values)
        val data = mapOf(
            "uid" to user.uid,
            "fullName" to name,
            "dateOfBirth" to dob,
            "gender" to values[gender.selectedItemPosition],
            "phoneNumber" to number,
            "email" to (user.email ?: ""),
            "locked" to true,
            "createdAt" to System.currentTimeMillis()
        )
        save.isEnabled = false
        db.runTransaction { transaction ->
            val ref = profileDocument() ?: throw IllegalStateException("No account")
            if (transaction.get(ref).exists()) throw IllegalStateException("Profile already locked")
            transaction.set(ref, data)
        }.addOnSuccessListener {
            locked = true
            setLockedUi()
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_LONG).show()
        }.addOnFailureListener {
            save.isEnabled = true
            Toast.makeText(this, getString(R.string.profile_save_failed, it.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun pickBirthDate() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            birthDate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
        }, calendar.get(Calendar.YEAR) - 18, calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun showChangeRequest() {
        val input = EditText(this).apply {
            hint = getString(R.string.profile_change_reason_hint)
            minLines = 3
            setPadding(24, 18, 24, 18)
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.profile_change_request),
            content = input,
            positiveLabel = getString(R.string.profile_send_request),
            onPositive = {
                val reason = input.text.toString().trim()
                if (reason.length < 10) {
                    Toast.makeText(this, R.string.profile_reason_required, Toast.LENGTH_LONG).show()
                    false
                } else {
                    submitChangeRequest(reason)
                    true
                }
            }
        )
    }

    private fun submitChangeRequest(reason: String) {
        val user = auth.currentUser ?: return
        val requestId = UUID.randomUUID().toString()
        val data = mapOf(
            "requestId" to requestId,
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "reason" to reason,
            "status" to "pending",
            "createdAt" to System.currentTimeMillis()
        )
        db.collection("users").document(user.uid).collection("profileChangeRequests")
            .document(requestId).set(data).addOnSuccessListener { openAppealEmail(reason) }
            .addOnFailureListener { Toast.makeText(this, R.string.profile_request_failed, Toast.LENGTH_LONG).show() }
    }

    private fun openAppealEmail(reason: String) {
        val user = auth.currentUser ?: return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:gayivt@gmail.com")).apply {
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.profile_email_subject, user.uid))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.profile_email_body, user.uid, user.email ?: "", reason))
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.account_appeal_no_email_app, Toast.LENGTH_LONG).show()
        }
    }
}
