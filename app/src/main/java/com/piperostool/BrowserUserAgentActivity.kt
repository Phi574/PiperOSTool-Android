package com.piperostool

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BrowserUserAgentActivity : AppCompatActivity() {
    private data class Device(val row: Int, val brand: String, val series: String,
                              val name: String, val fields: JSONObject) {
        fun field(vararg names: String): String = names.firstNotNullOfOrNull {
            fields.optString(it).takeIf(String::isNotBlank)
        }.orEmpty()
        val model: String get() = field("Mã model chính", "Mã model", "Model UA", "Model/Mã máy", "UA Device Token", "Chrome UA Platform", "UA Platform")
        val codes: List<String> get() = listOf(model) + field("Mã model khác", "Mã khác", "Mã nội bộ", "SKU Global / chính", "SKU khác", "Codename")
            .split(',', ';', '|').map(String::trim).filter(String::isNotBlank)
        val os: String get() = field("Android UA hợp lý", "Android hợp lệ đến 2026", "Android hợp lệ", "Android hợp lý", "iOS hợp lý tối đa", "OS hợp lý tối đa")
    }

    private lateinit var store: BrowserSessionStore
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var generate: MaterialButton
    private lateinit var restart: MaterialButton
    private lateinit var locationButton: MaterialButton
    private lateinit var progress: ProgressBar
    private val pickers = mutableMapOf<String, MaterialButton>()
    private var devices = emptyList<Device>()
    private var device: Device? = null
    private var brand = ""
    private var series = ""
    private var model = ""
    private var os = ""
    private var mozilla = "5.0"
    private var webkit = "537.36"
    private var chrome = ""
    private var webViewChromeMajor = 136
    private var safari = "537.36"
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserSessionStore(this)
        webViewChromeMajor = Regex("(?:Chrome|CriOS)/(\\d+)").find(WebSettings.getDefaultUserAgent(this))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 136
        chrome = "$webViewChromeMajor.0.0.0"
        buildLayout()
        lifecycleScope.launch {
            devices = withContext(Dispatchers.IO) {
                val source = assets.open("phone_catalog.json").bufferedReader().use { it.readText() }
                val array = JSONArray(source)
                List(array.length()) { index ->
                    array.getJSONObject(index).let {
                        Device(it.getInt("row"), it.getString("brand"), it.getString("series"),
                            it.getString("name"), it.getJSONObject("fields"))
                    }
                }
            }
            restoreSelection()
            updatePickers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationButton.isInitialized) {
            locationButton.text = "Vị trí website: ${store.browserLocation()?.label ?: "Theo thiết bị"}"
            restart.visibility = if (store.customUserAgent() != null || store.browserLocation() != null)
                View.VISIBLE else View.GONE
        }
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        if (!PiperUiPreferences.isModern(this)) scroll.setBackgroundColor(Color.argb(115, 15, 26, 44))
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(18), dp(18) + bars.top, dp(18), dp(24) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@BrowserUserAgentActivity).apply {
                text = "‹"
                textSize = 26f
                contentDescription = "Quay lại"
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(this@BrowserUserAgentActivity).apply {
                text = "Phiên User-Agent"
                textSize = 22f
                setTextColor(PiperModernUi.textColor(this@BrowserUserAgentActivity))
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(12) })
        root.addView(TextView(this).apply {
            text = "Tùy chỉnh dấu nhận diện trang web. Phiên bản trình duyệt là chuỗi nhận diện, không thay đổi lõi WebView."
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(this@BrowserUserAgentActivity))
        })
        status = TextView(this).apply {
            text = "Đang tải danh mục thiết bị…"
            setPadding(0, dp(14), 0, dp(12))
        }
        root.addView(status)
        root.addView(button("Khôi phục mặc định theo máy") {
            store.clearCustomUserAgent()
            store.clearBrowserLocation()
            device = null
            brand = ""; series = ""; model = ""; os = ""
            updatePickers()
            status.text = "Đã khôi phục User-Agent của thiết bị. Khởi động lại Browser để áp dụng."
            restart.visibility = View.VISIBLE
        })
        root.addView(section("Thiết bị"))
        search = EditText(this).apply {
            hint = "Tìm tên máy hoặc mã model"
            setSingleLine(true)
            textSize = 15f
            setTextColor(PiperModernUi.textColor(this@BrowserUserAgentActivity))
            setHintTextColor(PiperModernUi.secondaryTextColor(this@BrowserUserAgentActivity))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(PiperModernUi.surfaceColor(this@BrowserUserAgentActivity))
                setStroke(dp(1), PiperModernUi.borderColor(this@BrowserUserAgentActivity))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePickers() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(9) })
        listOf("brand" to "Hãng thiết bị", "series" to "Dòng thiết bị", "device" to "Tên thiết bị",
            "os" to "Phiên bản Android / OS", "model" to "Mã máy").forEach { (key, label) ->
            root.addView(button(label) { choose(key) }.also { pickers[key] = it })
        }
        root.addView(section("Vị trí website"))
        locationButton = button("Vị trí website: Theo thiết bị") {
            startActivity(Intent(this, BrowserLocationMapActivity::class.java))
        }
        root.addView(locationButton)
        root.addView(TextView(this).apply {
            text = "Chỉ đổi vị trí HTML5 mà trang web thấy trong PiperOS Browser; không đổi IP công cộng hoặc vị trí của ứng dụng khác."
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(this@BrowserUserAgentActivity))
            setPadding(dp(7), 0, dp(7), dp(4))
        })
        root.addView(section("Phiên bản chuỗi nhận diện"))
        listOf("mozilla" to "Mozilla", "webkit" to "AppleWebKit", "chrome" to "Chrome / CriOS",
            "safari" to "Mobile Safari / Safari").forEach { (key, label) ->
            root.addView(button(label) { choose(key) }.also { pickers[key] = it })
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        generate = button("Khởi tạo User-Agent") { generateUserAgent() }
        restart = button("Khởi động lại PiperOS Browser") {
            startActivity(Intent(this, PiperBrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
        root.addView(generate)
        root.addView(restart)
        restart.visibility = if (store.customUserAgent() != null) View.VISIBLE else View.GONE
        locationButton.text = "Vị trí website: ${store.browserLocation()?.label ?: "Theo thiết bị"}"
        PiperModernUi.apply(root)
    }

    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minHeight = dp(44)
        insetTop = dp(2); insetBottom = dp(2)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(5); bottomMargin = dp(5) }
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(PiperModernUi.textColor(this@BrowserUserAgentActivity))
        setPadding(dp(5), dp(21), 0, dp(9))
    }

    private fun matching(): List<Device> {
        val query = search.text.toString().trim()
        return if (query.isBlank()) devices else devices.filter {
            (it.name + " " + it.model + " " + it.codes.joinToString(" ")).contains(query, ignoreCase = true)
        }
    }

    private fun choose(key: String) {
        if (devices.isEmpty()) return
        val candidates = matching()
        val choices = when (key) {
            "brand" -> candidates.map(Device::brand).distinct().sorted()
            "series" -> candidates.filter { it.brand == brand }.map(Device::series).distinct().sorted()
            "device" -> candidates.filter { it.brand == brand && it.series == series }
                .map { "${it.name} · #${it.row}" }
            "os" -> osOptions(device)
            "model" -> device?.codes?.filter(String::isNotBlank)?.distinct().orEmpty()
            "mozilla" -> listOf("5.0")
            "webkit" -> if (brand == "Apple" || brand == "macOS") listOf("605.1.15", "604.1") else listOf("537.36")
            "chrome" -> chromeOptions(device)
            "safari" -> if (brand == "Apple" || brand == "macOS") listOf("604.1", "605.1.15") else listOf("537.36")
            else -> emptyList()
        }
        if (choices.isEmpty()) {
            status.text = "Hãy chọn hãng, dòng và thiết bị trước."
            return
        }
        PiperActionSheet.showSearchSelect(
            this,
            pickers[key]?.text?.toString().orEmpty(),
            choices.mapIndexed { index, value -> PiperSheetChoice(index.toString(), value) }
        ) { choice ->
                val index = choice.toInt()
                val value = choices[index]
                when (key) {
                    "brand" -> { brand = value; series = ""; device = null; model = ""; os = "" }
                    "series" -> { series = value; device = null; model = ""; os = "" }
                    "device" -> {
                        device = candidates.filter { it.brand == brand && it.series == series }[index]
                        model = device?.model.orEmpty()
                        os = osOptions(device).lastOrNull().orEmpty()
                        webkit = if (brand == "Apple" || brand == "macOS") "605.1.15" else "537.36"
                        safari = if (brand == "Apple" || brand == "macOS") "604.1" else "537.36"
                        chrome = chromeOptions(device).lastOrNull().orEmpty()
                    }
                    "os" -> os = value
                    "model" -> model = value
                    "mozilla" -> mozilla = value
                    "webkit" -> webkit = value
                    "chrome" -> chrome = value
                    "safari" -> safari = value
                }
                updatePickers()
            }
    }

    private fun osOptions(selected: Device?): List<String> {
        selected ?: return emptyList()
        if (selected.brand == "macOS" || selected.brand == "Windows")
            return listOf(selected.fields.optString("Phiên bản thực").ifBlank { selected.name })
        val source = selected.os.ifBlank { selected.field("Android gốc", "iOS gốc", "OS gốc") }
        val numbers = Regex("\\d+(?:\\.\\d+)?").findAll(source).map { it.value }.toList()
        val original = Regex("\\d+(?:\\.\\d+)?")
            .find(selected.field("Android gốc", "iOS gốc", "OS gốc"))?.value?.toDoubleOrNull()
        val first = (original ?: numbers.firstOrNull()?.toDoubleOrNull())
            ?: return listOf(source).filter(String::isNotBlank)
        val last = numbers.lastOrNull()?.toDoubleOrNull() ?: first
        if (first > last || last - first > 30) return listOf(source)
        return ((first.toInt())..(last.toInt())).map(Int::toString).let { versions ->
            (versions + numbers.filter { '.' in it }).distinct().sortedBy { it.toDoubleOrNull() ?: 0.0 }
        }
    }

    private fun chromeOptions(selected: Device?): List<String> {
        val current = webViewChromeMajor
        val bound = Regex("<=\\s*(\\d+)").find(selected?.field("Chrome hợp lý", "Chrome hiện đại hỗ trợ").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val max = minOf(current, bound ?: current)
        return (max - 3..max).filter { it > 0 }.map { "$it.0.0.0" }
    }

    private fun signature(): String = listOf(device?.row, model, os, mozilla, webkit, chrome, safari).joinToString("|")

    private fun updatePickers() {
        pickers["brand"]?.text = "Hãng thiết bị: ${brand.ifBlank { "Chọn" }}"
        pickers["series"]?.text = "Dòng thiết bị: ${series.ifBlank { "Chọn" }}"
        pickers["device"]?.text = "Tên thiết bị: ${device?.name ?: "Chọn"}"
        pickers["os"]?.text = "Phiên bản Android / OS: ${os.ifBlank { "Chọn" }}"
        pickers["model"]?.text = "Mã máy: ${model.ifBlank { "Chọn" }}"
        pickers["mozilla"]?.text = "Mozilla: $mozilla"
        pickers["webkit"]?.text = "AppleWebKit: $webkit"
        pickers["chrome"]?.text = "Chrome / CriOS: $chrome"
        pickers["safari"]?.text = "Mobile Safari / Safari: $safari"
        if (::generate.isInitialized) {
            generate.isEnabled = !building && device != null && model.isNotBlank() && os.isNotBlank() &&
                signature() != store.customUserAgentSignature()
            generate.alpha = if (generate.isEnabled) 1f else 0.48f
        }
        if (::status.isInitialized && devices.isNotEmpty() && !building) {
            status.text = "${matching().size} / ${devices.size} mục · ${device?.name ?: "Chọn thiết bị để cấu hình"}"
        }
    }

    private fun generateUserAgent() {
        val selected = device ?: return
        if (!generate.isEnabled) return
        building = true
        progress.visibility = View.VISIBLE
        status.text = "Đang xây dựng và kiểm tra User-Agent…"
        updatePickers()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { buildUserAgent(selected) }
            val valid = result.startsWith("Mozilla/$mozilla") && result.contains("AppleWebKit/$webkit") &&
                result.contains(model) && result.contains("Safari/$safari")
            if (valid) {
                val choices = JSONObject().put("model", model).put("os", os).put("mozilla", mozilla)
                    .put("webkit", webkit).put("chrome", chrome).put("safari", safari)
                store.setCustomUserAgent(selected.name, result, signature(), selected.row, choices.toString())
                status.text = "Đã khởi tạo: $result"
                restart.visibility = View.VISIBLE
            } else status.text = "Không thể tạo User-Agent hợp lệ từ lựa chọn hiện tại."
            building = false
            progress.visibility = View.GONE
            updatePickers()
            if (valid) status.text = "Đã khởi tạo: $result"
        }
    }

    private fun buildUserAgent(selected: Device): String {
        val platform = when (selected.brand) {
            "Apple" -> if (selected.series.contains("iPad", true))
                "iPad; CPU OS ${os.replace('.', '_')} like Mac OS X; $model"
                else "iPhone; CPU iPhone OS ${os.replace('.', '_')} like Mac OS X; $model"
            "macOS", "Windows" -> model
            else -> "Linux; Android $os; $model"
        }
        val mobile = selected.brand != "macOS" && selected.brand != "Windows"
        val chromeToken = if (selected.brand == "Apple") "CriOS" else "Chrome"
        return "Mozilla/$mozilla ($platform) AppleWebKit/$webkit (KHTML, like Gecko) " +
            "$chromeToken/$chrome ${if (mobile) "Mobile " else ""}Safari/$safari"
    }

    private fun restoreSelection() {
        val saved = store.customUserAgentRow()
        device = devices.firstOrNull { it.row == saved }
        device?.let {
            brand = it.brand; series = it.series; model = it.model
            os = osOptions(it).lastOrNull().orEmpty()
        }
        store.customUserAgentChoices()?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let {
                model = it.optString("model", model)
                os = it.optString("os", os)
                mozilla = it.optString("mozilla", mozilla)
                webkit = it.optString("webkit", webkit)
                chrome = it.optString("chrome", chrome)
                safari = it.optString("safari", safari)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
