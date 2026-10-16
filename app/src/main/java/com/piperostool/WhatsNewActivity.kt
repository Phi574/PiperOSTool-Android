package com.piperostool

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class WhatsNewActivity : AppCompatActivity() {
    private data class ReleaseNote(val version: String, val details: String) {
        val channel: AppUpdateRepository.Channel get() = AppUpdateRepository.channel(version)
    }

    private lateinit var content: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyButton: MaterialButton
    private var historyVisible = false
    private val releases by lazy { readReleaseNotes() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(12))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val back = MaterialButton(this).apply {
            text = getString(R.string.back)
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(92), dp(46)))
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.home_whats_new)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@WhatsNewActivity))
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        PiperModernUi.watch(this)
        PiperAutoFont.watch(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            root.setPadding(dp(18), safe.top, dp(18), nav.bottom + dp(12))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val current = AppVersion.name(this)
        content.addView(label(getString(R.string.whats_new_current_version, current), 13f, true).apply {
            setTextColor(PiperModernUi.accentColor(this@WhatsNewActivity))
        }, margin(bottom = 8))
        val currentRelease = releases.firstOrNull { it.version.equals(current, ignoreCase = true) }
        if (currentRelease != null) {
            content.addView(releaseCard(currentRelease, initiallyExpanded = true), margin(bottom = 16))
        } else {
            content.addView(bodyCard(getString(R.string.whats_new_current_fallback, current)), margin(bottom = 16))
        }

        historyButton = MaterialButton(this).apply {
            text = getString(R.string.whats_new_show_all)
            setOnClickListener {
                historyVisible = !historyVisible
                historyContainer.visibility = if (historyVisible) View.VISIBLE else View.GONE
                text = getString(if (historyVisible) R.string.whats_new_hide_all else R.string.whats_new_show_all)
            }
        }
        content.addView(historyButton, margin(bottom = 10))
        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (historyVisible) View.VISIBLE else View.GONE
        }
        val previous = releases.filterNot { it.version.equals(currentRelease?.version, ignoreCase = true) }
        listOf(AppUpdateRepository.Channel.PREVIEW, AppUpdateRepository.Channel.BETA, AppUpdateRepository.Channel.STABLE)
            .forEach { channel ->
                val group = previous.filter { it.channel == channel }
                if (group.isNotEmpty()) {
                    historyContainer.addView(label(channelTitle(channel), 13f, true).apply {
                        setTextColor(PiperModernUi.accentColor(this@WhatsNewActivity))
                    }, margin(bottom = 8))
                    group.forEach { release ->
                        historyContainer.addView(releaseCard(release), margin(bottom = 10))
                    }
                }
            }
        content.addView(historyContainer)
    }

    private fun releaseCard(note: ReleaseNote, initiallyExpanded: Boolean = false): MaterialCardView {
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val title = TextView(this).apply {
            text = "${note.version} · ${channelTitle(note.channel)}"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@WhatsNewActivity))
        }
        val detail = label(note.details.ifBlank { getString(R.string.whats_new_no_details) }, 14f, false).apply {
            setPadding(0, dp(10), 0, 0)
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        cardContent.addView(title)
        cardContent.addView(detail)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@WhatsNewActivity))
            strokeColor = PiperModernUi.borderColor(this@WhatsNewActivity)
            isClickable = !initiallyExpanded
            isFocusable = !initiallyExpanded
            if (!initiallyExpanded) setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            addView(cardContent)
        }
        return card
    }

    private fun bodyCard(text: String) = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        setCardBackgroundColor(PiperModernUi.surfaceColor(this@WhatsNewActivity))
        strokeColor = PiperModernUi.borderColor(this@WhatsNewActivity)
        addView(label(text, 14f, false).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) })
    }

    private fun channelTitle(channel: AppUpdateRepository.Channel): String = when (channel) {
        AppUpdateRepository.Channel.STABLE -> "Ổn định chính thức"
        AppUpdateRepository.Channel.BETA -> "Beta"
        AppUpdateRepository.Channel.PREVIEW -> "Preview / PRE"
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(PiperModernUi.secondaryTextColor(this@WhatsNewActivity))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun readReleaseNotes(): List<ReleaseNote> = runCatching {
        assets.open("changelog.md").bufferedReader().use { it.readText() }
            .split(Regex("(?m)^##\\s+"))
            .drop(1)
            .mapNotNull { section ->
                val lines = section.lines()
                val version = lines.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val details = lines.drop(1).joinToString("\n") { line ->
                    line.trim().removePrefix("- ").replace(Regex("\\*\\*(.*?)\\*\\*"), "$1").trim()
                }.trim()
                ReleaseNote(version, details)
            }
    }.getOrDefault(emptyList())

    private fun margin(bottom: Int) = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
