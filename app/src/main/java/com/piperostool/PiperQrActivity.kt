package com.piperostool

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PiperQrActivity : AppCompatActivity() {
    private enum class Kind {
        TEXT, URL, WIFI, PHONE, SMS, EMAIL, VCARD, LOCATION, EVENT, SOCIAL,
        DEEP_LINK, PAYMENT, PRODUCT, TICKET, PERSON, DOCUMENT, JSON
    }

    private data class FieldSpec(
        val key: String,
        val label: Int,
        val inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        val options: List<String>? = null,
        val multiline: Boolean = false
    )

    private data class ScanItem(
        val title: String,
        val body: String,
        val intent: Intent? = null,
        val confirmation: Boolean = false,
        val copyText: String = body
    )

    private lateinit var kindMenu: TextView
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var createPanel: View
    private lateinit var scanPanel: View
    private lateinit var generatedCard: View
    private lateinit var generatedImage: ImageView
    private lateinit var generatedText: TextView
    private lateinit var scanResultCard: View
    private lateinit var scanResultTitle: TextView
    private lateinit var scanResultBody: TextView
    private lateinit var handleScanButton: MaterialButton
    private val fieldInputs = linkedMapOf<String, EditText>()
    private val labels by lazy { resources.getStringArray(R.array.piper_qr_type_labels).toList() }
    private var selectedKind = Kind.TEXT
    private var generatedPayload: String? = null
    private var generatedBitmap: Bitmap? = null
    private var currentScan: ScanItem? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            if (result.contents != null) toast(R.string.qr_scan_cancelled)
            return@registerForActivityResult
        }
        showScanResult(decode(contents))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_piper_qr)
        applyInsets()
        bindViews()
        bindActions()
        renderFields()
        PiperModernUi.watch(this)
        PiperAutoFont.watch(findViewById(R.id.piperQrRoot))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.piperQrRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, dp(8f) + bars.top, view.paddingRight, bars.bottom)
            insets
        }
    }

    private fun bindViews() {
        kindMenu = findViewById(R.id.piperQrTypeMenu)
        fieldsContainer = findViewById(R.id.piperQrDynamicFields)
        createPanel = findViewById(R.id.piperQrCreatePanel)
        scanPanel = findViewById(R.id.piperQrScanPanel)
        generatedCard = findViewById(R.id.piperQrGeneratedCard)
        generatedImage = findViewById(R.id.piperQrGeneratedImage)
        generatedText = findViewById(R.id.piperQrGeneratedText)
        scanResultCard = findViewById(R.id.piperQrScanResultCard)
        scanResultTitle = findViewById(R.id.piperQrScanResultTitle)
        scanResultBody = findViewById(R.id.piperQrScanResultBody)
        handleScanButton = findViewById(R.id.btnPiperQrHandleScan)
        kindMenu.text = labels.first()
    }

    private fun bindActions() {
        findViewById<View>(R.id.btnPiperQrBack).setOnClickListener { finish() }
        findViewById<View>(R.id.qrTypeSelectorCard).setOnClickListener { showKindPicker() }
        findViewById<MaterialButtonToggleGroup>(R.id.piperQrTabGroup)
            .addOnButtonCheckedListener { _, checkedId, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val creating = checkedId == R.id.btnPiperQrCreateTab
                createPanel.visibility = if (creating) View.VISIBLE else View.GONE
                scanPanel.visibility = if (creating) View.GONE else View.VISIBLE
                findViewById<View>(R.id.piperQrScroll).scrollTo(0, 0)
            }
        findViewById<View>(R.id.btnPiperQrStartGenerate).setOnClickListener { generate() }
        findViewById<View>(R.id.btnPiperQrStartScan).setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.qr_scan_help))
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
                    .setCaptureActivity(PiperQrCaptureActivity::class.java)
            )
        }
        findViewById<View>(R.id.btnPiperQrCopy).setOnClickListener {
            generatedPayload?.let { copy(it) }
        }
        findViewById<View>(R.id.btnPiperQrSave).setOnClickListener { saveGenerated() }
        findViewById<View>(R.id.btnPiperQrShare).setOnClickListener { shareGenerated() }
        findViewById<View>(R.id.btnPiperQrCopyScan).setOnClickListener {
            currentScan?.copyText?.let { copy(it) }
        }
        handleScanButton.setOnClickListener { continueWithScan() }
    }

    private fun showKindPicker() {
        PiperActionSheet.showSearchSelect(
            this,
            getString(R.string.qr_type_label),
            labels.mapIndexed { index, label -> PiperSheetChoice(index.toString(), label, index == selectedKind.ordinal) }
        ) { key ->
            val index = key.toIntOrNull() ?: return@showSearchSelect
            selectedKind = Kind.entries.getOrNull(index) ?: return@showSearchSelect
            kindMenu.text = labels[index]
            generatedCard.visibility = View.GONE
            renderFields()
        }
    }

    private fun showFieldOptionPicker(spec: FieldSpec, input: EditText) {
        val options = spec.options ?: return
        PiperActionSheet.showSearchSelect(
            this,
            getString(spec.label),
            options.mapIndexed { index, label -> PiperSheetChoice(index.toString(), label, label == input.text.toString()) }
        ) { key -> input.setText(options.getOrNull(key.toIntOrNull() ?: -1).orEmpty()) }
    }

    private fun renderFields() {
        fieldsContainer.removeAllViews()
        fieldInputs.clear()
        specsFor(selectedKind).forEach { spec ->
            val layout = TextInputLayout(this).apply {
                hint = getString(spec.label)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8f)
                }
            }
            val input: EditText = TextInputEditText(this).apply {
                inputType = if (spec.options == null) spec.inputType else InputType.TYPE_NULL
                if (spec.options != null) {
                    setText(spec.options.first())
                    isFocusable = false
                    isClickable = true
                    setOnClickListener { showFieldOptionPicker(spec, this) }
                }
                if (spec.multiline) {
                    minLines = 3
                    maxLines = 6
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }
            }
            input.tag = spec.key
            fieldInputs[spec.key] = input
            layout.addView(input, LinearLayout.LayoutParams(-1, -2))
            fieldsContainer.addView(layout)
        }
    }

    private fun specsFor(kind: Kind): List<FieldSpec> {
        fun f(key: String, label: Int, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
              multiline: Boolean = false) = FieldSpec(key, label, type, multiline = multiline)
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        return when (kind) {
            Kind.TEXT -> listOf(f("content", R.string.qr_field_content, multiline = true))
            Kind.URL -> listOf(f("url", R.string.qr_field_url, uri))
            Kind.WIFI -> listOf(
                f("ssid", R.string.qr_field_ssid), f("password", R.string.qr_field_password),
                FieldSpec("security", R.string.qr_wifi_security, options = listOf(
                    getString(R.string.qr_security_wpa), getString(R.string.qr_security_wep), getString(R.string.qr_security_none)
                ))
            )
            Kind.PHONE -> listOf(f("phone", R.string.qr_field_phone, InputType.TYPE_CLASS_PHONE))
            Kind.SMS -> listOf(f("phone", R.string.qr_field_phone, InputType.TYPE_CLASS_PHONE), f("message", R.string.qr_field_message, multiline = true))
            Kind.EMAIL -> listOf(f("email", R.string.qr_field_email, email), f("subject", R.string.qr_field_subject), f("body", R.string.qr_field_message, multiline = true))
            Kind.VCARD -> listOf(f("name", R.string.qr_field_name), f("phone", R.string.qr_field_phone, InputType.TYPE_CLASS_PHONE), f("email", R.string.qr_field_email, email), f("organization", R.string.qr_field_organization))
            Kind.LOCATION -> listOf(f("lat", R.string.qr_field_latitude, decimal), f("lon", R.string.qr_field_longitude, decimal), f("place", R.string.qr_field_place))
            Kind.EVENT -> listOf(f("title", R.string.qr_field_event_title), f("location", R.string.qr_field_event_location), f("start", R.string.qr_field_event_start, InputType.TYPE_CLASS_DATETIME), f("end", R.string.qr_field_event_end, InputType.TYPE_CLASS_DATETIME))
            Kind.SOCIAL -> listOf(f("url", R.string.qr_field_url, uri))
            Kind.DEEP_LINK -> listOf(f("uri", R.string.qr_field_deep_link, uri))
            Kind.PAYMENT -> listOf(f("uri", R.string.qr_field_payment_uri, uri))
            Kind.PRODUCT -> listOf(f("id", R.string.qr_field_product_id), f("name", R.string.qr_field_name), f("url", R.string.qr_field_product_url, uri))
            Kind.TICKET -> listOf(f("id", R.string.qr_field_ticket_id), f("event", R.string.qr_field_ticket_event))
            Kind.PERSON -> listOf(f("id", R.string.qr_field_person_id), f("name", R.string.qr_field_name), f("organization", R.string.qr_field_organization), f("kind", R.string.qr_field_person_type))
            Kind.DOCUMENT -> listOf(f("url", R.string.qr_field_document_url, uri))
            Kind.JSON -> listOf(f("json", R.string.qr_field_custom_json, multiline = true))
        }
    }

    private fun generate() {
        try {
            val values = fieldInputs.mapValues { it.value.text?.toString()?.trim().orEmpty() }
            val payload = encode(selectedKind, values)
            val bitmap = encodeBitmap(payload)
            generatedPayload = payload
            generatedBitmap = bitmap
            generatedImage.setImageBitmap(bitmap)
            generatedText.text = payload
            generatedCard.visibility = View.VISIBLE
            findViewById<NestedScrollView>(R.id.piperQrScroll).post {
                findViewById<NestedScrollView>(R.id.piperQrScroll).smoothScrollTo(0, generatedCard.bottom)
            }
        } catch (error: IllegalArgumentException) {
            toast(error.message ?: getString(R.string.qr_invalid_input))
        } catch (_: Exception) {
            toast(R.string.qr_invalid_input)
        }
    }

    private fun encode(kind: Kind, v: Map<String, String>): String {
        fun required(key: String): String = v[key].orEmpty().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(getString(R.string.qr_invalid_input))
        fun optional(key: String) = v[key].orEmpty()
        fun validWebUrl(key: String): String {
            val value = required(key)
            val uri = Uri.parse(value)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank())
                throw IllegalArgumentException(getString(R.string.qr_invalid_url))
            return value
        }
        fun custom(type: String, data: JSONObject) = JSONObject()
            .put("piperos_qr", 1).put("type", type).put("data", data).toString()
        return when (kind) {
            Kind.TEXT -> required("content")
            Kind.URL, Kind.SOCIAL -> validWebUrl("url")
            Kind.WIFI -> {
                val security = when (required("security")) {
                    getString(R.string.qr_security_wep) -> "WEP"
                    getString(R.string.qr_security_none) -> "nopass"
                    else -> "WPA"
                }
                val password = if (security == "nopass") "" else required("password")
                "WIFI:T:$security;S:${wifiEscape(required("ssid"))};P:${wifiEscape(password)};;"
            }
            Kind.PHONE -> "tel:${Uri.encode(required("phone"))}"
            Kind.SMS -> "SMSTO:${required("phone")}:${required("message")}"
            Kind.EMAIL -> "MATMSG:TO:${required("email")};SUB:${optional("subject")};BODY:${required("body")};"
            Kind.VCARD -> buildString {
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:3.0")
                appendLine("FN:${vcardEscape(required("name"))}")
                optional("phone").takeIf(String::isNotBlank)?.let { appendLine("TEL;TYPE=CELL:${vcardEscape(it)}") }
                optional("email").takeIf(String::isNotBlank)?.let { appendLine("EMAIL:${vcardEscape(it)}") }
                optional("organization").takeIf(String::isNotBlank)?.let { appendLine("ORG:${vcardEscape(it)}") }
                append("END:VCARD")
            }
            Kind.LOCATION -> {
                val lat = required("lat").toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                    ?: throw IllegalArgumentException(getString(R.string.qr_invalid_input))
                val lon = required("lon").toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                    ?: throw IllegalArgumentException(getString(R.string.qr_invalid_input))
                val place = optional("place")
                "geo:$lat,$lon" + if (place.isNotBlank()) "?q=$lat,$lon(${Uri.encode(place)})" else ""
            }
            Kind.EVENT -> {
                val start = required("start").uppercase(Locale.ROOT)
                val end = required("end").uppercase(Locale.ROOT)
                if (!start.matches(Regex("\\d{8}T\\d{6}Z?")) || !end.matches(Regex("\\d{8}T\\d{6}Z?")))
                    throw IllegalArgumentException(getString(R.string.qr_invalid_input))
                buildString {
                    appendLine("BEGIN:VCALENDAR")
                    appendLine("VERSION:2.0")
                    appendLine("BEGIN:VEVENT")
                    appendLine("SUMMARY:${icsEscape(required("title"))}")
                    appendLine("DTSTART:$start")
                    appendLine("DTEND:$end")
                    optional("location").takeIf(String::isNotBlank)?.let { appendLine("LOCATION:${icsEscape(it)}") }
                    appendLine("END:VEVENT")
                    append("END:VCALENDAR")
                }
            }
            Kind.DEEP_LINK -> {
                val uri = required("uri")
                if (!uri.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")) || uri.startsWith("javascript:", true) || uri.startsWith("file:", true))
                    throw IllegalArgumentException(getString(R.string.qr_invalid_deep_link))
                uri
            }
            Kind.PAYMENT -> required("uri").also { value ->
                if (!Uri.parse(value).scheme.orEmpty().matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*$")))
                    throw IllegalArgumentException(getString(R.string.qr_invalid_input))
            }
            Kind.PRODUCT -> custom("product", JSONObject().put("id", required("id")).put("name", optional("name")).put("url", optional("url").takeIf(String::isNotBlank)))
            Kind.TICKET -> custom("ticket", JSONObject().put("id", required("id")).put("event", optional("event")))
            Kind.PERSON -> custom("person", JSONObject().put("id", required("id")).put("name", optional("name")).put("organization", optional("organization")).put("kind", optional("kind")))
            Kind.DOCUMENT -> validWebUrl("url")
            Kind.JSON -> {
                val raw = required("json")
                try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException(getString(R.string.qr_invalid_json)) }
                raw
            }
        }
    }

    private fun wifiEscape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    private fun vcardEscape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    private fun icsEscape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun encodeBitmap(value: String): Bitmap {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 900, 900)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private fun decode(raw: String): ScanItem {
        val value = raw.trim()
        if (value.startsWith("WIFI:", true)) {
            val ssid = wifiValue(value, "S")
            val password = wifiValue(value, "P")
            val security = wifiValue(value, "T").ifBlank { "WPA" }
            return ScanItem(getString(R.string.qr_wifi_security), getString(R.string.qr_wifi_details, ssid, security, password), Intent(Settings.ACTION_WIFI_SETTINGS), true)
        }
        if (value.startsWith("BEGIN:VCARD", true)) return decodeVcard(value)
        if (value.startsWith("BEGIN:VCALENDAR", true) || value.startsWith("BEGIN:VEVENT", true)) return decodeEvent(value)
        if (value.startsWith("MATMSG:", true)) {
            val email = value.substringAfter("TO:", "").substringBefore(';')
            val subject = value.substringAfter("SUB:", "").substringBefore(';')
            val body = value.substringAfter("BODY:", "").substringBefore(";;").trimEnd(';')
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}"))
                .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
            return ScanItem(getString(R.string.qr_field_email), listOf(email, subject, body).filter(String::isNotBlank).joinToString("\n"), intent, true)
        }
        if (value.startsWith("SMSTO:", true) || value.startsWith("SMS:", true)) {
            val bodyStart = value.indexOf(':', value.indexOf(':') + 1)
            val phone = value.substringAfter(':').let { if (bodyStart >= 0) it.substringBeforeLast(':') else it }
            val body = if (bodyStart >= 0) value.substring(bodyStart + 1) else ""
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)).putExtra("sms_body", body)
            return ScanItem(getString(R.string.qr_scan_tab), listOf(phone, body).filter(String::isNotBlank).joinToString("\n"), intent, true)
        }
        if (value.startsWith("TEL:", true) || value.startsWith("tel:", true)) {
            val phone = value.substringAfter(':')
            return ScanItem(getString(R.string.qr_field_phone), phone, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)), true)
        }
        if (value.startsWith("geo:", true)) return ScanItem(getString(R.string.qr_field_place), value, Intent(Intent.ACTION_VIEW, Uri.parse(value)), true)
        if (value.startsWith("mailto:", true)) return ScanItem(getString(R.string.qr_field_email), value.substringAfter(':'), Intent(Intent.ACTION_SENDTO, Uri.parse(value)), true)
        if (value.startsWith("http://", true) || value.startsWith("https://", true))
            return ScanItem(getString(R.string.qr_field_url), value, Intent(Intent.ACTION_VIEW, Uri.parse(value)), true)

        val json = runCatching { JSONObject(value) }.getOrNull()
        if (json != null && json.optInt("piperos_qr") == 1) {
            val data = json.optJSONObject("data") ?: JSONObject()
            return when (json.optString("type")) {
                "product" -> {
                    val url = data.optString("url")
                    ScanItem(getString(R.string.qr_product_title), "${data.optString("name")}\n${data.optString("id")}\n${getString(R.string.qr_product_no_backend)}".trim(),
                        url.takeIf(String::isNotBlank)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }, url.isNotBlank(), value)
                }
                "ticket" -> ScanItem(getString(R.string.qr_ticket_title), "${data.optString("event")}\n${data.optString("id")}\n${getString(R.string.qr_ticket_no_backend)}", copyText = value)
                "person" -> ScanItem(getString(R.string.qr_person_title), "${data.optString("kind")}\n${data.optString("name")}\n${data.optString("id")}\n${data.optString("organization")}\n${getString(R.string.qr_person_no_backend)}", copyText = value)
                else -> ScanItem(getString(R.string.qr_custom_data_title), data.toString(2), copyText = value)
            }
        }
        if (json != null) return ScanItem(getString(R.string.qr_custom_data_title), json.toString(2), copyText = value)
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        if (uri?.scheme != null) return ScanItem(getString(R.string.qr_open_action), value, Intent(Intent.ACTION_VIEW, uri), true)
        return ScanItem(getString(R.string.qr_field_content), value, copyText = value)
    }

    private fun decodeVcard(raw: String): ScanItem {
        fun prop(vararg names: String): String {
            val line = raw.lineSequence().firstOrNull { candidate -> names.any { candidate.startsWith("$it:", true) || candidate.startsWith("$it;", true) } } ?: return ""
            return line.substringAfter(':', "").trim()
        }
        val name = prop("FN", "N")
        val phone = prop("TEL")
        val email = prop("EMAIL")
        val org = prop("ORG")
        val intent = Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, name)
            .putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            .putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            .putExtra(ContactsContract.Intents.Insert.COMPANY, org)
        return ScanItem(getString(R.string.qr_contact_title), listOf(name, phone, email, org).filter(String::isNotBlank).joinToString("\n"), intent, true, raw)
    }

    private fun decodeEvent(raw: String): ScanItem {
        fun prop(key: String) = raw.lineSequence().firstOrNull { it.startsWith("$key:", true) }?.substringAfter(':').orEmpty().trim()
        val title = prop("SUMMARY")
        val location = prop("LOCATION")
        val start = eventTime(prop("DTSTART"))
        val end = eventTime(prop("DTEND"))
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        if (start != null) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        if (end != null) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        return ScanItem(getString(R.string.qr_field_event_title), listOf(title, location, prop("DTSTART"), prop("DTEND")).filter(String::isNotBlank).joinToString("\n"), intent, true, raw)
    }

    private fun eventTime(value: String): Long? {
        if (value.isBlank()) return null
        val utc = value.endsWith('Z')
        val pattern = if (utc) "yyyyMMdd'T'HHmmss'Z'" else "yyyyMMdd'T'HHmmss"
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; if (utc) timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value)?.time
        }.getOrNull()
    }

    private fun wifiValue(raw: String, key: String): String {
        val match = Regex("(?:^|;)$key:((?:\\\\.|[^;])*)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1).orEmpty()
        return match.replace(Regex("\\\\(.)"), "$1")
    }

    private fun showScanResult(item: ScanItem) {
        currentScan = item
        scanResultTitle.text = item.title
        scanResultBody.text = item.body
        handleScanButton.visibility = if (item.intent == null) View.GONE else View.VISIBLE
        handleScanButton.text = if (item.intent?.action == Settings.ACTION_WIFI_SETTINGS) getString(R.string.qr_wifi_settings) else getString(R.string.qr_open_action)
        scanResultCard.visibility = View.VISIBLE
        findViewById<NestedScrollView>(R.id.piperQrScroll).post {
            findViewById<NestedScrollView>(R.id.piperQrScroll).smoothScrollTo(0, scanResultCard.bottom)
        }
    }

    private fun continueWithScan() {
        val item = currentScan ?: return
        val intent = item.intent ?: return
        val run = {
            try {
                val chooser = Intent.createChooser(intent, item.title)
                startActivity(chooser)
            } catch (_: ActivityNotFoundException) {
                toast(R.string.qr_no_handler)
            } catch (_: SecurityException) {
                toast(R.string.qr_no_handler)
            }
        }
        if (item.confirmation) {
            PiperDialog.showConfirm(
                context = this,
                title = item.title,
                message = if (intent.action == Settings.ACTION_WIFI_SETTINGS) item.body
                    else getString(R.string.qr_confirm_external),
                positiveLabel = getString(
                    if (intent.action == Settings.ACTION_WIFI_SETTINGS) R.string.qr_wifi_settings
                    else R.string.qr_open_action
                )
            ) { run() }
        } else run()
    }

    private fun saveGenerated() {
        val bitmap = generatedBitmap ?: return
        runCatching {
            val name = "PiperOS-QR-${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PiperOS QR")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) error("compress failed")
            } ?: error("open failed")
            toast(R.string.qr_saved)
        }.onFailure { toast(R.string.qr_save_failed) }
    }

    private fun shareGenerated() {
        val bitmap = generatedBitmap ?: return
        runCatching {
            val dir = File(cacheDir, "piper-qr").apply { mkdirs() }
            val file = File(dir, "qr-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, getString(R.string.qr_share_chooser), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.qr_share_chooser)))
        }.onFailure { toast(R.string.qr_save_failed) }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PiperOS QR", text))
        toast(R.string.qr_content_copied)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
