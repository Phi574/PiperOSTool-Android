package com.piperostool

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView
import java.util.ArrayDeque

enum class PiperUiStyle(val key: String) {
    CLASSIC("classic"), MODERN("modern")
}

enum class PiperColorMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark")
}

object PiperUiPreferences {
    private const val PREFS = "PiperPrefs"
    private const val KEY_STYLE = "ui_style"
    private const val KEY_COLOR_MODE = "ui_color_mode"
    private const val KEY_LANGUAGE = "app_language"

    fun initialize(context: Context) {
        applyColorMode(context)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language(context))
        )
    }

    fun style(context: Context): PiperUiStyle =
        when (prefs(context).getString(KEY_STYLE, PiperUiStyle.MODERN.key)) {
            PiperUiStyle.CLASSIC.key -> PiperUiStyle.CLASSIC
            else -> PiperUiStyle.MODERN
        }

    fun colorMode(context: Context): PiperColorMode =
        when (prefs(context).getString(KEY_COLOR_MODE, PiperColorMode.SYSTEM.key)) {
            PiperColorMode.LIGHT.key -> PiperColorMode.LIGHT
            PiperColorMode.DARK.key -> PiperColorMode.DARK
            else -> PiperColorMode.SYSTEM
        }

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "vi")?.takeIf { it == "vi" || it == "en" } ?: "vi"

    fun setStyle(context: Context, value: PiperUiStyle) {
        prefs(context).edit().putString(KEY_STYLE, value.key).apply()
    }

    fun setColorMode(context: Context, value: PiperColorMode) {
        prefs(context).edit().putString(KEY_COLOR_MODE, value.key).apply()
        applyColorMode(context)
    }

    fun setLanguage(context: Context, language: String) {
        val safeLanguage = if (language == "en") "en" else "vi"
        prefs(context).edit().putString(KEY_LANGUAGE, safeLanguage).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(safeLanguage))
    }

    fun isModern(context: Context): Boolean = style(context) == PiperUiStyle.MODERN

    private fun applyColorMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (colorMode(context)) {
                PiperColorMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                PiperColorMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                PiperColorMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object PiperModernUi {
    private data class Palette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val border: Int,
        val accent: Int,
        val onAccent: Int
    )

    fun watch(activity: Activity) {
        if (!PiperUiPreferences.isModern(activity)) return
        applyWindow(activity.window, isDark(activity))
        val root = activity.window.decorView
        if (root.getTag(R.id.piper_modern_ui_watcher) != true) {
            root.setTag(R.id.piper_modern_ui_watcher, true)
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (PiperUiPreferences.isModern(activity)) applyTree(root, palette(activity))
            }
        }
        root.post { applyTree(root, palette(activity)) }
    }

    private fun applyWindow(window: Window, dark: Boolean) {
        val palette = if (dark) darkPalette else lightPalette
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.surface
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun applyTree(root: View, palette: Palette) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view.getTag(R.id.piper_modern_ui_applied) != true) {
                applyView(view, palette)
                view.setTag(R.id.piper_modern_ui_applied, true)
            }
            if (view is ViewGroup && !preserveChildren(view)) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    private fun applyView(view: View, palette: Palette) {
        val name = resourceName(view)
        if (name == "homeBackground") {
            view.visibility = View.GONE
            return
        }
        if (name.endsWith("Root") || name == "homeRoot") {
            view.setBackgroundColor(palette.background)
            return
        }

        if (isNavigationItem(name) || name == "fragment_container") return

        when (view) {
            is MaterialCardView -> {
                view.setCardBackgroundColor(palette.surface)
                view.strokeColor = palette.border
                view.strokeWidth = view.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                view.radius = 8f * view.resources.displayMetrics.density
                view.cardElevation = 0f
            }
            is EditText -> {
                view.setTextColor(palette.text)
                view.setHintTextColor(palette.secondaryText)
                view.background = rounded(palette.surface, palette.border, 8f, view)
            }
            is Button -> {
                view.isAllCaps = false
                view.setTextColor(if (isPrimaryAction(name)) palette.onAccent else palette.text)
                view.background = rounded(
                    if (isPrimaryAction(name)) palette.accent else palette.surface,
                    if (isPrimaryAction(name)) palette.accent else palette.border,
                    8f,
                    view
                )
            }
            is TextView -> modernizeText(view, palette)
            is ImageButton -> {
                view.setBackgroundColor(Color.TRANSPARENT)
                view.imageTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
            }
            is ImageView -> {
                if (
                    (view.imageTintList != null || name.startsWith("icon", true)) &&
                    !name.contains("Artwork", true)
                ) {
                    view.imageTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
                }
            }
            is ViewGroup -> {
                if (
                    view.background != null && view.parent != null && !isSpecialSurface(name) &&
                    (!view.isClickable || name.startsWith("feature") || name.contains("Card"))
                ) {
                    view.background = rounded(palette.surface, palette.border, 8f, view)
                    view.elevation = 0f
                }
            }
        }
    }

    private fun modernizeText(view: TextView, palette: Palette) {
        if (preserveTextSurface(resourceName(view))) return
        val current = view.currentTextColor
        val alpha = Color.alpha(current)
        if (alpha < 90) return
        val isHeading = view.textSize / view.resources.displayMetrics.scaledDensity >= 16f ||
            view.typeface?.style == Typeface.BOLD
        view.setTextColor(if (isHeading) palette.text else palette.secondaryText)
        view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
    }

    private fun preserveChildren(view: View): Boolean {
        val name = resourceName(view)
        return name.contains("WebView", true) || name.contains("PlayerView", true) ||
            name.contains("Map", true) && !name.endsWith("Root") ||
            name.contains("TerminalScreen", true) || name.contains("terminalOutput", true)
    }

    private fun preserveTextSurface(name: String): Boolean =
        name.contains("terminalOutput", true) || name.contains("terminalScreen", true)

    private fun isSpecialSurface(name: String): Boolean =
        name.contains("disc", true) || name.contains("video", true) ||
            name.contains("player", true) || name.contains("map", true) ||
            name.contains("progress", true)

    private fun isPrimaryAction(name: String): Boolean =
        name.contains("start", true) || name.contains("login", true) ||
            name.contains("signup", true) || name.contains("send", true) ||
            name.contains("run", true) || name.contains("install", true)

    private fun isNavigationItem(name: String): Boolean = name in setOf(
        "navHome", "navBeta", "navApps", "navSettings", "navDevices",
        "iconHome", "iconNews", "iconApps", "iconSettings", "iconDevices",
        "txtHome", "txtNews", "txtApps", "txtSettings", "txtDevices"
    )

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float, view: View) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * view.resources.displayMetrics.density
            setStroke((view.resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun resourceName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun palette(context: Context): Palette = if (isDark(context)) darkPalette else lightPalette

    private fun isDark(context: Context): Boolean =
        when (PiperUiPreferences.colorMode(context)) {
            PiperColorMode.DARK -> true
            PiperColorMode.LIGHT -> false
            PiperColorMode.SYSTEM ->
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }

    private val lightPalette = Palette(
        background = Color.rgb(247, 247, 245),
        surface = Color.WHITE,
        text = Color.rgb(31, 33, 36),
        secondaryText = Color.rgb(105, 108, 112),
        border = Color.rgb(220, 222, 219),
        accent = Color.rgb(18, 124, 86),
        onAccent = Color.WHITE
    )

    private val darkPalette = Palette(
        background = Color.rgb(17, 19, 21),
        surface = Color.rgb(27, 30, 33),
        text = Color.rgb(241, 243, 244),
        secondaryText = Color.rgb(174, 178, 182),
        border = Color.rgb(59, 63, 67),
        accent = Color.rgb(99, 220, 165),
        onAccent = Color.rgb(9, 45, 31)
    )
}
