package com.piperostool

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class WhatsNewActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var filters: MaterialButtonToggleGroup
    private var releases = emptyList<WhatsNewRelease>()
    private val filterIds = mutableMapOf<AppUpdateRepository.Channel?, Int>()
    private var selectedChannel: AppUpdateRepository.Channel? = null

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
        toolbar.addView(MaterialButton(this).apply {
            text = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(92), dp(46)))
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
        releases = readReleaseNotes()
        render()
        refreshReleaseNotes()
    }

    private fun refreshReleaseNotes() {
        lifecycleScope.launch {
            try {
                val merged = WhatsNewCatalog.mergeLocalAndRemote(releases, AppUpdateRepository.releases())
                if (merged != releases) {
                    releases = merged
                    render()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the bundled changelog available when GitHub is offline or rate-limited.
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        filterIds.clear()
        val current = AppVersion.name(this)
        val currentRelease = WhatsNewCatalog.currentRelease(releases, current)

        content.addView(label(getString(R.string.whats_new_current_version, current), 13f, true).apply {
            setTextColor(PiperModernUi.accentColor(this@WhatsNewActivity))
        }, margin(bottom = 8))
        if (currentRelease != null) {
            content.addView(releaseCard(currentRelease, initiallyExpanded = true), margin(bottom = 16))
        } else {
            content.addView(bodyCard(getString(R.string.whats_new_current_fallback, current)), margin(bottom = 16))
        }

        content.addView(label("Lịch sử phiên bản", 18f, true).apply {
            setTextColor(PiperModernUi.textColor(this@WhatsNewActivity))
        }, margin(bottom = 8))
        filters = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, dp(4))
        }
        addFilter("Tất cả", null)
        addFilter("Preview", AppUpdateRepository.Channel.PREVIEW)
        addFilter("Beta", AppUpdateRepository.Channel.BETA)
        addFilter("Stable", AppUpdateRepository.Channel.STABLE)
        content.addView(filters, margin(bottom = 10))
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyContainer)
        filters.check(filterIds[selectedChannel] ?: filterIds[null]!!)
        filters.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedChannel = filterIds.entries.firstOrNull { it.value == checkedId }?.key
                renderHistory(WhatsNewCatalog.currentRelease(releases, current))
            }
        }
        renderHistory(currentRelease)
    }

    private fun addFilter(title: String, channel: AppUpdateRepository.Channel?) {
        val button = MaterialButton(this).apply {
            id = View.generateViewId()
            text = title
            isCheckable = true
            isAllCaps = false
            textSize = 12f
            minHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(10), 0, dp(10), 0)
        }
        filterIds[channel] = button.id
        filters.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f))
    }

    private fun renderHistory(currentRelease: WhatsNewRelease?) {
        historyContainer.removeAllViews()
        val history = releases.filterNot { note ->
            currentRelease != null && note.parsedVersion == currentRelease.parsedVersion
        }.filter { selectedChannel == null || it.channel == selectedChannel }

        if (history.isEmpty()) {
            historyContainer.addView(bodyCard("Không có phiên bản nào trong bộ lọc này."))
            return
        }
        history.forEach { note ->
            historyContainer.addView(releaseCard(note), margin(bottom = 10))
        }
    }

    private fun releaseCard(note: WhatsNewRelease, initiallyExpanded: Boolean = false): MaterialCardView {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val (background, border, badgeBackground, badgeForeground) = when (note.channel) {
            AppUpdateRepository.Channel.PREVIEW -> if (dark) {
                Quad(Color.rgb(23, 54, 61), Color.rgb(46, 103, 113), Color.rgb(29, 72, 80), Color.rgb(157, 225, 234))
            } else {
                Quad(Color.rgb(224, 247, 250), Color.rgb(167, 219, 225), Color.rgb(206, 239, 243), Color.rgb(20, 112, 125))
            }
            AppUpdateRepository.Channel.BETA -> if (dark) {
                Quad(Color.rgb(27, 43, 68), Color.rgb(60, 87, 130), Color.rgb(39, 61, 96), Color.rgb(170, 199, 255))
            } else {
                Quad(Color.rgb(231, 239, 255), Color.rgb(185, 204, 239), Color.rgb(211, 226, 255), Color.rgb(37, 87, 166))
            }
            AppUpdateRepository.Channel.STABLE -> if (dark) {
                Quad(Color.rgb(32, 57, 39), Color.rgb(63, 112, 75), Color.rgb(40, 76, 49), Color.rgb(173, 225, 180))
            } else {
                Quad(Color.rgb(232, 245, 233), Color.rgb(187, 218, 190), Color.rgb(214, 237, 216), Color.rgb(44, 112, 54))
            }
        }
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        heading.addView(TextView(this).apply {
            text = note.version
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@WhatsNewActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (note.channel == AppUpdateRepository.Channel.BETA) {
            heading.addView(betaBadge(badgeBackground, badgeForeground))
        } else {
            heading.addView(TextView(this).apply {
                text = channelTitle(note.channel)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(badgeForeground)
                setBackground(pillDrawable(badgeBackground))
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
        }
        val detail = label(note.details.ifBlank { getString(R.string.whats_new_no_details) }, 14f, false).apply {
            setPadding(0, dp(10), 0, 0)
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        cardContent.addView(heading)
        cardContent.addView(detail)
        return MaterialCardView(this).apply {
            setTag(R.id.piper_preserve_modern_colors, true)
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(background)
            strokeColor = border
            isClickable = !initiallyExpanded
            isFocusable = !initiallyExpanded
            if (!initiallyExpanded) setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            addView(cardContent)
        }
    }

    private fun betaBadge(backgroundColor: Int, foregroundColor: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        setBackground(pillDrawable(backgroundColor))
        setPadding(dp(9), dp(5), dp(10), dp(5))
        addView(ImageView(this@WhatsNewActivity).apply {
            setImageResource(R.drawable.ic_beta)
            imageTintList = android.content.res.ColorStateList.valueOf(foregroundColor)
        }, LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(TextView(this@WhatsNewActivity).apply {
            text = "BETA"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(foregroundColor)
            setPadding(dp(4), 0, 0, 0)
        })
    }

    private data class Quad(val background: Int, val border: Int, val badgeBackground: Int, val badgeForeground: Int)

    private fun pillDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(50).toFloat()
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
        AppUpdateRepository.Channel.STABLE -> "Ổn định"
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

    private fun readReleaseNotes(): List<WhatsNewRelease> = runCatching {
        assets.open("changelog.md").bufferedReader().use { WhatsNewCatalog.parse(it.readText()) }
    }.getOrDefault(emptyList())

    private fun margin(bottom: Int) = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
