package com.piperostool

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import java.util.ArrayDeque
import java.lang.ref.WeakReference

object PiperAutoFont {
    private enum class TextLanguage { VIETNAMESE, ENGLISH }

    private lateinit var vt323: Typeface
    private lateinit var silkscreen: Typeface
    private lateinit var inter: Typeface
    private var cachedCustomKey: String? = null
    private var cachedCustomTypeface: Typeface? = null

    fun initialize(context: Context) {
        vt323 = checkNotNull(ResourcesCompat.getFont(context, R.font.vt323))
        silkscreen = checkNotNull(ResourcesCompat.getFont(context, R.font.silkscreen))
        inter = checkNotNull(ResourcesCompat.getFont(context, R.font.inter))
    }

    fun clearTypefaceCache() {
        cachedCustomKey = null
        cachedCustomTypeface = null
    }

    fun watch(root: View) {
        if (!::vt323.isInitialized || root.getTag(R.id.piper_auto_font_watcher) == true) {
            return
        }
        root.setTag(R.id.piper_auto_font_watcher, true)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            applyToTree(root)
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.post { applyToTree(root) }
    }

    fun apply(textView: TextView) {
        if (
            !::vt323.isInitialized ||
            textView.getTag(R.id.piper_auto_font_ignore) == true
        ) return
        PiperUiText.apply(textView)
        ensureTextWatcher(textView)
        configureCompactTextFit(textView)
        val fontKey = PiperFontPreferences.selectedKey(textView.context)
        val language = if (fontKey == PiperFontPreferences.BILINGUAL) {
            detectLanguage(textView)
        } else {
            null
        }
        val requestedStyle = textView.typeface?.style ?: Typeface.NORMAL
        val signature = "$fontKey|${language?.name}|${textView.text}|$requestedStyle"
        if (textView.getTag(R.id.piper_auto_font_signature) == signature) return

        val family = when {
            fontKey == PiperFontPreferences.SYSTEM -> Typeface.DEFAULT
            fontKey == PiperFontPreferences.INTER -> inter
            fontKey == PiperFontPreferences.BILINGUAL && language == TextLanguage.VIETNAMESE -> vt323
            fontKey == PiperFontPreferences.BILINGUAL -> silkscreen
            else -> customTypeface(textView.context, fontKey) ?: Typeface.DEFAULT
        }
        textView.typeface = Typeface.create(family, requestedStyle)
        textView.setTag(R.id.piper_auto_font_signature, signature)
    }

    private fun customTypeface(context: Context, key: String): Typeface? {
        if (cachedCustomKey == key) return cachedCustomTypeface
        val loaded = PiperFontPreferences.customFontFile(context, key)?.let { file ->
            runCatching { Typeface.createFromFile(file) }.getOrNull()
        }
        cachedCustomKey = key
        cachedCustomTypeface = loaded
        return loaded
    }

    private fun applyToTree(root: View) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            when (val view = pending.removeFirst()) {
                is TextView -> apply(view)
                is ViewGroup -> {
                    PiperUiText.apply(view)
                    for (index in 0 until view.childCount) {
                        pending.addLast(view.getChildAt(index))
                    }
                }
                else -> PiperUiText.apply(view)
            }
        }
    }

    private fun ensureTextWatcher(textView: TextView) {
        if (textView.getTag(R.id.piper_auto_font_text_watcher) != null) return
        val watcher = textView.doAfterTextChanged {
            textView.setTag(R.id.piper_auto_font_signature, null)
            apply(textView)
        }
        textView.setTag(R.id.piper_auto_font_text_watcher, watcher)
    }

    private fun configureCompactTextFit(textView: TextView) {
        if (
            textView is EditText ||
            textView.getTag(R.id.piper_auto_font_compact_fit) == true
        ) {
            return
        }
        val isCompactLabel = textView is Button || textView.maxLines == 1
        if (!isCompactLabel) return

        textView.setTag(R.id.piper_auto_font_compact_fit, true)
        if (textView is Button) textView.maxLines = 1
        if (TextViewCompat.getAutoSizeTextType(textView) != TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE) {
            return
        }

        val metrics = textView.resources.displayMetrics
        val scaledDensity = metrics.density * textView.resources.configuration.fontScale
        val declaredSizeSp = textView.textSize / scaledDensity
        val maximumSizeSp = declaredSizeSp.toInt().coerceAtLeast(1)
        val absoluteMinimumSp = COMPACT_ABSOLUTE_MIN_SP.coerceAtMost(maximumSizeSp)
        val minimumSizeSp = (declaredSizeSp * COMPACT_MIN_SIZE_RATIO)
            .toInt()
            .coerceIn(absoluteMinimumSp, maximumSizeSp)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            textView,
            minimumSizeSp,
            maximumSizeSp,
            1,
            android.util.TypedValue.COMPLEX_UNIT_SP
        )
    }

    private fun detectLanguage(textView: TextView): TextLanguage {
        val ownText = textView.text?.toString().orEmpty()
        languageOf(ownText)?.let { return it }

        var parent: ViewParent? = textView.parent
        repeat(NEUTRAL_PARENT_DEPTH) {
            val group = parent as? ViewGroup ?: return@repeat
            var englishFound = false
            for (index in 0 until group.childCount) {
                val sibling = group.getChildAt(index)
                if (sibling === textView || sibling !is TextView) continue
                when (languageOf(sibling.text?.toString().orEmpty())) {
                    TextLanguage.VIETNAMESE -> return TextLanguage.VIETNAMESE
                    TextLanguage.ENGLISH -> englishFound = true
                    null -> Unit
                }
            }
            if (englishFound) return TextLanguage.ENGLISH
            parent = group.parent
        }

        return if (PiperUiPreferences.language(textView.context) == "vi") {
            TextLanguage.VIETNAMESE
        } else {
            TextLanguage.ENGLISH
        }
    }

    private fun languageOf(text: String): TextLanguage? {
        if (text.any(VIETNAMESE_CHARACTERS::contains)) return TextLanguage.VIETNAMESE
        if (text.any { it in 'A'..'Z' || it in 'a'..'z' }) return TextLanguage.ENGLISH
        return null
    }

    private const val NEUTRAL_PARENT_DEPTH = 3
    private const val COMPACT_MIN_SIZE_RATIO = 0.68f
    private const val COMPACT_ABSOLUTE_MIN_SP = 7
    private const val VIETNAMESE_CHARACTERS =
        "ÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠ" +
            "ÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ" +
            "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡ" +
            "ùúụủũưừứựửữỳýỵỷỹđ"
}

class PiperOsApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedActivity = WeakReference<Activity>(null)
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onCreate() {
        super.onCreate()
        PiperUiPreferences.initialize(this)
        PiperAutoFont.initialize(this)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        registerActivityLifecycleCallbacks(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val systemThemeChanged = newNightMode != lastNightMode
        lastNightMode = newNightMode
        if (systemThemeChanged && PiperUiPreferences.colorMode(this) == PiperColorMode.SYSTEM) {
            Handler(Looper.getMainLooper()).post {
                resumedActivity.get()
                    ?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?.recreate()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        PiperAutoFont.watch(activity.window.decorView)
        PiperModernUi.watch(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        PiperAutoFont.watch(activity.window.decorView)
        PiperModernUi.watch(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
