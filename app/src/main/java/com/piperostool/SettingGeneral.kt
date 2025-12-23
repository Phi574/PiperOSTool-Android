package com.piperostool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class SettingGeneral : AppCompatActivity() {
    private lateinit var imgGifPreview: ImageView
    private val PREF_NAME = "PiperPrefs"

    // SỬA: Dùng OpenDocument để có quyền truy cập lâu dài
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                // Lưu URI vào bộ nhớ
                saveStringPref("custom_gif_uri", uri.toString())
                saveBooleanPref("is_using_custom_gif", true)

                // Hiển thị preview
                loadPreview(uri.toString())

                Toast.makeText(this, "Đã cập nhật ảnh bìa! Vui lòng quay lại Home.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Không thể cấp quyền truy cập ảnh này: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    // Dùng onCreate thay vì onCreateView và onViewCreated
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set layout cho Activity
        setContentView(R.layout.general_settings)

        // 1. Ánh xạ View
        imgGifPreview = findViewById(R.id.imgGifPreview)
        val btnUpload = findViewById<Button>(R.id.btnUploadGif)
        val btnSystem = findViewById<Button>(R.id.btnSystemGif)
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)
        val rgScale = findViewById<RadioGroup>(R.id.rgScaleType)
        val rgTheme = findViewById<RadioGroup>(R.id.rgTheme)

        // 2. Load cài đặt cũ
        loadSavedSettings()

        btnUpload.setOnClickListener {
            pickMedia.launch(arrayOf("image/*"))
        }

        btnSystem.setOnClickListener {
            saveBooleanPref("is_using_custom_gif", false)
            imgGifPreview.setImageResource(R.drawable.home_gif)
            imgGifPreview.scaleType = ImageView.ScaleType.CENTER_CROP
            Toast.makeText(this, "Đã về mặc định", Toast.LENGTH_SHORT).show()
        }

        // 4. Xử lý Scale Type
        rgScale.setOnCheckedChangeListener { _, checkedId ->
            val scaleType = if (checkedId == R.id.rbScaleFit) "FIT_CENTER" else "CENTER_CROP"
            saveStringPref("gif_scale_type", scaleType)
            imgGifPreview.scaleType = if (scaleType == "FIT_CENTER")
                ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        }

        // 5. Xử lý Ngôn ngữ (THÊM RECREATE)
        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (findViewById<RadioButton>(checkedId).isPressed) {
                val lang = if (checkedId == R.id.rbLangVi) "vi" else "en"
                saveStringPref("app_language", lang)
                Toast.makeText(this, "Đang đổi ngôn ngữ...", Toast.LENGTH_SHORT).show()
                // Khởi động lại Activity để áp dụng ngôn ngữ
                recreate() // Trong Activity, chỉ cần gọi recreate()
            }
        }

        // 6. Xử lý Theme (THÊM RECREATE)
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            if (findViewById<RadioButton>(checkedId).isPressed) {
                val theme = when (checkedId) {
                    R.id.rbThemeGreen -> "green"
                    R.id.rbThemePurple -> "purple"
                    else -> "system"
                }
                saveStringPref("app_theme", theme)
                Toast.makeText(this, "Đang đổi màu...", Toast.LENGTH_SHORT).show()
                // Khởi động lại Activity để áp dụng màu icon
                recreate()
            }
        }
    }

    // Không cần tham số 'view' nữa vì đang ở trong Activity
    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Load GIF
        val isCustom = prefs.getBoolean("is_using_custom_gif", false)
        if (isCustom) {
            val uriString = prefs.getString("custom_gif_uri", "")
            loadPreview(uriString)
        } else {
            imgGifPreview.setImageResource(R.drawable.home_gif)
        }

        // Load Scale
        val scaleType = prefs.getString("gif_scale_type", "CENTER_CROP")
        imgGifPreview.scaleType = if (scaleType == "FIT_CENTER") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (scaleType == "FIT_CENTER") findViewById<RadioButton>(R.id.rbScaleFit).isChecked = true
        else findViewById<RadioButton>(R.id.rbScaleCrop).isChecked = true

        // Load Language
        val lang = prefs.getString("app_language", "vi")
        if (lang == "vi") findViewById<RadioButton>(R.id.rbLangVi).isChecked = true
        else findViewById<RadioButton>(R.id.rbLangEn).isChecked = true

        // Load Theme
        val theme = prefs.getString("app_theme", "system")
        when (theme) {
            "green" -> findViewById<RadioButton>(R.id.rbThemeGreen).isChecked = true
            "purple" -> findViewById<RadioButton>(R.id.rbThemePurple).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbThemeSystem).isChecked = true
        }
    }

    private fun loadPreview(uriString: String?) {
        if (!uriString.isNullOrEmpty()) {
            try {
                // Dùng 'this' vì đang trong Activity
                Glide.with(this).load(Uri.parse(uriString)).into(imgGifPreview)
            } catch (e: Exception) {
                imgGifPreview.setImageResource(R.drawable.home_gif)
            }
        }
    }

    private fun saveStringPref(key: String, value: String) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    private fun saveBooleanPref(key: String, value: Boolean) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }
}
