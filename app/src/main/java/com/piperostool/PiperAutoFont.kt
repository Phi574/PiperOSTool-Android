package com.piperostool

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
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
import java.util.Locale

object PiperAutoFont {
    private enum class TextLanguage { VIETNAMESE, ENGLISH }

    private lateinit var vt323: Typeface
    private lateinit var silkscreen: Typeface

    fun initialize(context: Context) {
        vt323 = checkNotNull(ResourcesCompat.getFont(context, R.font.vt323))
        silkscreen = checkNotNull(ResourcesCompat.getFont(context, R.font.silkscreen))
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
        ensureTextWatcher(textView)
        configureCompactTextFit(textView)
        val language = detectLanguage(textView)
        val requestedStyle = textView.typeface?.style ?: Typeface.NORMAL
        val signature = "${language.name}|${textView.text}|$requestedStyle"
        if (textView.getTag(R.id.piper_auto_font_signature) == signature) return

        val family = when (language) {
            TextLanguage.VIETNAMESE -> vt323
            TextLanguage.ENGLISH -> silkscreen
        }
        textView.typeface = Typeface.create(family, requestedStyle)
        textView.setTag(R.id.piper_auto_font_signature, signature)
    }

    private fun applyToTree(root: View) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            when (val view = pending.removeFirst()) {
                is TextView -> apply(view)
                is ViewGroup -> {
                    for (index in 0 until view.childCount) {
                        pending.addLast(view.getChildAt(index))
                    }
                }
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

        return if (Locale.getDefault().language.equals("vi", ignoreCase = true)) {
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
    override fun onCreate() {
        super.onCreate()
        PiperAutoFont.initialize(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        PiperAutoFont.watch(activity.window.decorView)
    }

    override fun onActivityResumed(activity: Activity) {
        PiperAutoFont.watch(activity.window.decorView)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
