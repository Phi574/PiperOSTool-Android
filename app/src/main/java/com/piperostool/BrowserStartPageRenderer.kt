package com.piperostool

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

internal object BrowserStartPageRenderer {
    fun apply(
        context: Context,
        config: BrowserStartConfig,
        openUrl: (String) -> Unit
    ) {
        val activity = context as? PiperBrowserActivity ?: return
        activity.findViewById<TextView>(R.id.browserStartTitle)?.text = config.title
        activity.findViewById<TextView>(R.id.browserStartSubtitle)?.text = config.subtitle
        activity.findViewById<EditText>(R.id.homeSearchInput)?.hint = config.searchHint
        activity.findViewById<TextView>(R.id.browserStorageNote)?.text = config.privacyNote
        val container = activity.findViewById<LinearLayout>(R.id.browserStartDynamicContent) ?: return
        container.removeAllViews()
        config.components.forEach { component ->
            when (component) {
                is BrowserStartComponent.ShortcutGrid ->
                    renderShortcutGrid(activity, component, container, openUrl)
                is BrowserStartComponent.AnnouncementBanner ->
                    renderBanner(activity, component, container, openUrl)
            }
        }
    }

    private fun renderShortcutGrid(
        context: Context,
        grid: BrowserStartComponent.ShortcutGrid,
        parent: LinearLayout,
        openUrl: (String) -> Unit
    ) {
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        grid.items.chunked(grid.columns).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 8) }
            }
            rowItems.forEachIndexed { index, item ->
                val card = shortcutCard(context, item)
                card.layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(context, 58),
                    1f
                ).apply {
                    if (index > 0) marginStart = dp(context, 8)
                }
                card.setOnClickListener { openUrl(item.url) }
                row.addView(card)
            }
            if (rowItems.size < grid.columns) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            rows.addView(row)
        }
        parent.addView(rows)
    }

    private fun shortcutCard(context: Context, item: BrowserStartShortcut): MaterialCardView {
        val card = MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            strokeColor = PiperModernUi.borderColor(context)
            setCardBackgroundColor(PiperModernUi.surfaceColor(context))
            isClickable = true
            isFocusable = true
            contentDescription = item.title
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12))
        }
        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            text = item.badge
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PiperModernUi.accentColor(context))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PiperModernUi.surfaceColor(context))
                setStroke(dp(context, 1), PiperModernUi.borderColor(context))
            }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)))
        val title = TextView(context).apply {
            text = item.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PiperModernUi.textColor(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(context, 10)
        })
        card.addView(row)
        return card
    }

    private fun renderBanner(
        context: Context,
        banner: BrowserStartComponent.AnnouncementBanner,
        parent: LinearLayout,
        openUrl: (String) -> Unit
    ) {
        val card = MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            strokeColor = PiperModernUi.borderColor(context)
            setCardBackgroundColor(PiperModernUi.surfaceColor(context))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14))
        }
        content.addView(TextView(context).apply {
            text = banner.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PiperModernUi.textColor(context))
        })
        content.addView(TextView(context).apply {
            text = banner.message
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(context))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 4) })
        if (!banner.url.isNullOrBlank() && banner.buttonText.isNotBlank()) {
            content.addView(MaterialButton(context).apply {
                text = banner.buttonText
                isAllCaps = false
                cornerRadius = dp(context, 14)
                backgroundTintList = ColorStateList.valueOf(PiperModernUi.accentColor(context))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { openUrl(banner.url) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(context, 44)
            ).apply { topMargin = dp(context, 8) })
        }
        card.addView(content)
        parent.addView(card)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
