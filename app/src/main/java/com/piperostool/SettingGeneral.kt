package com.piperostool

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.ImageViewTarget
import com.bumptech.glide.request.transition.Transition

class SettingGeneral : AppCompatActivity() {
    private lateinit var imgGifPreview: ImageView
    private lateinit var imgGifPreview2: ImageView
    private lateinit var spinnerGifSelection: Spinner
    private lateinit var btnResetToDefault: Button

    private val PREF_NAME = "PiperPrefs"
    private val GIF_SLIDESHOW_INTERVAL = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private var slideshowRunnable: Runnable? = null
    private var currentGifIndex = 0
    private var isSlideshowRunning = false
    private var isSpinnerInitialSelection = true
    private var isActivityInForeground = false

    private val defaultGifs = listOf(
        R.drawable.gif1, R.drawable.gif2fix, R.drawable.gif3, R.drawable.gif4,
        R.drawable.gif5, R.drawable.gif6, R.drawable.gif7, R.drawable.gif8
    )

    private val gifOptions = mutableListOf(
        "Tự động chuyển (Slideshow)",
        "GIF 1", "GIF 2", "GIF 3", "GIF 4", "GIF 5", "GIF 6", "GIF 7", "GIF 8",
        "Tải ảnh lên (Custom)"
    )

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveStringPref("gif_selection", "custom")
                saveStringPref("custom_gif_uri", uri.toString())
                stopSlideshow()
                loadPreview(uri.toString())
                Toast.makeText(this, "Đã cập nhật ảnh bìa!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                loadSavedSettings()
            }
        } else {
            loadSavedSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.general_settings)

        imgGifPreview = findViewById(R.id.imgGifPreview)
        imgGifPreview2 = findViewById(R.id.imgGifPreview2)
        spinnerGifSelection = findViewById(R.id.spinnerGifSelection)
        btnResetToDefault = findViewById(R.id.btnResetToDefault)

        setupSpinner()
        loadSavedSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("gif_selection", "default") == "slideshow" && !isSlideshowRunning) {
            startSlideshow()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityInForeground = false
        stopSlideshow()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, gifOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGifSelection.adapter = adapter
    }

    private fun setupListeners() {
        spinnerGifSelection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitialSelection) {
                    isSpinnerInitialSelection = false
                    return
                }
                stopSlideshow()
                when (position) {
                    0 -> { // Slideshow
                        saveStringPref("gif_selection", "slideshow")
                        startSlideshow()
                        Toast.makeText(this@SettingGeneral, "Đã bật chế độ tự động chuyển GIF", Toast.LENGTH_SHORT).show()
                    }
                    gifOptions.size - 1 -> pickMedia.launch(arrayOf("image/gif", "image/*"))
                    else -> {
                        val gifIndex = position - 1
                        if (gifIndex >= 0 && gifIndex < defaultGifs.size) {
                            saveStringPref("gif_selection", "gif${gifIndex + 1}")
                            loadGifFromResource(defaultGifs[gifIndex])
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnResetToDefault.setOnClickListener {
            stopSlideshow()
            saveStringPref("gif_selection", "default")
            saveStringPref("custom_gif_uri", "")
            spinnerGifSelection.setSelection(0)
            loadGifFromResource(R.drawable.home_gif)
            Toast.makeText(this, "Đã đặt lại ảnh bìa về mặc định!", Toast.LENGTH_SHORT).show()
        }

        // Listener cho Ngôn ngữ (Chỉ hiển thị Toast)
        findViewById<RadioGroup>(R.id.rgLanguage).setOnCheckedChangeListener { _, checkedId ->
            val radioButton = findViewById<RadioButton>(checkedId)
            if (radioButton != null && radioButton.isPressed) {
                Toast.makeText(this, "Tính năng đang được phát triển!", Toast.LENGTH_SHORT).show()
                // Reset lại lựa chọn về trạng thái đã lưu để không thay đổi giao diện
                val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val lang = prefs.getString("app_language", "vi")
                if (lang == "vi") {
                    findViewById<RadioButton>(R.id.rbLangVi).isChecked = true
                } else {
                    findViewById<RadioButton>(R.id.rbLangEn).isChecked = true
                }
            }
        }
    }

    private fun loadSavedSettings() {
        isSpinnerInitialSelection = true
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Load GIF selection
        val gifSelection = prefs.getString("gif_selection", "default")
        val position = when {
            gifSelection == "custom" -> gifOptions.size - 1
            gifSelection == "slideshow" -> 0
            gifSelection!!.startsWith("gif") -> try {
                gifOptions.indexOf("GIF ${gifSelection.substring(3)}").takeIf { it != -1 } ?: 0
            } catch (e: Exception) { 0 }
            else -> 0
        }
        spinnerGifSelection.setSelection(position, false)

        when {
            gifSelection == "custom" -> loadPreview(prefs.getString("custom_gif_uri", ""))
            gifSelection == "slideshow" -> if (!isSlideshowRunning) startSlideshow()
            gifSelection.startsWith("gif") -> try {
                val index = gifSelection.substring(3).toInt() - 1
                if(index >= 0 && index < defaultGifs.size) loadGifFromResource(defaultGifs[index]) else loadGifFromResource(R.drawable.home_gif)
            } catch (e: Exception) { loadGifFromResource(R.drawable.home_gif) }
            else -> loadGifFromResource(R.drawable.home_gif)
        }

        // Load Language selection
        val lang = prefs.getString("app_language", "vi")
        if (lang == "vi") findViewById<RadioButton>(R.id.rbLangVi).isChecked = true else findViewById<RadioButton>(R.id.rbLangEn).isChecked = true
    }

    private fun startSlideshow() {
        if (isSlideshowRunning || !isActivityInForeground || defaultGifs.isEmpty()) return
        isSlideshowRunning = true
        currentGifIndex = 0
        loadGifFromResource(defaultGifs[currentGifIndex])
        slideshowRunnable = Runnable {
            currentGifIndex = (currentGifIndex + 1) % defaultGifs.size
            crossfadeToNewGif(defaultGifs[currentGifIndex])
            handler.postDelayed(slideshowRunnable!!, GIF_SLIDESHOW_INTERVAL)
        }
        handler.postDelayed(slideshowRunnable!!, GIF_SLIDESHOW_INTERVAL)
    }

    private fun stopSlideshow() {
        isSlideshowRunning = false
        slideshowRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun crossfadeToNewGif(newGifResId: Int) {
        if (!isActivityInForeground) return
        Glide.with(this).asGif().load(newGifResId).into(object : ImageViewTarget<GifDrawable>(imgGifPreview2) {
            override fun onResourceReady(resource: GifDrawable, transition: Transition<in GifDrawable>?) {
                super.onResourceReady(resource, transition)
                if (!isActivityInForeground) return
                imgGifPreview2.alpha = 0f
                imgGifPreview2.visibility = View.VISIBLE
                val fadeOut = ObjectAnimator.ofFloat(imgGifPreview, "alpha", 1f, 0f).setDuration(500)
                val fadeIn = ObjectAnimator.ofFloat(imgGifPreview2, "alpha", 0f, 1f).setDuration(500)
                fadeOut.start()
                fadeIn.start()
                handler.postDelayed({
                    if (isActivityInForeground) {
                        Glide.with(this@SettingGeneral).asGif().load(newGifResId).into(imgGifPreview)
                        imgGifPreview.alpha = 1f
                        imgGifPreview2.visibility = View.INVISIBLE
                    }
                }, 500)
            }
            override fun setResource(resource: GifDrawable?) { imgGifPreview2.setImageDrawable(resource) }
        })
    }

    private fun loadPreview(uriString: String?) {
        imgGifPreview.scaleType = ImageView.ScaleType.CENTER_CROP // Luôn dùng CenterCrop cho Preview
        if (!uriString.isNullOrEmpty()) {
            Glide.with(this).load(Uri.parse(uriString)).placeholder(R.drawable.home_gif).into(imgGifPreview)
        } else {
            loadGifFromResource(R.drawable.home_gif)
        }
    }

    private fun loadGifFromResource(resId: Int) {
        imgGifPreview.scaleType = ImageView.ScaleType.CENTER_CROP // Luôn dùng CenterCrop cho Preview
        Glide.with(this).asGif().load(resId).into(imgGifPreview)
    }

    private fun saveStringPref(key: String, value: String) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }
}
