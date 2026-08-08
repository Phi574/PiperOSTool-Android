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
import android.util.TypedValue
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
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
        val dark = isDark(activity)
        applyWindow(activity.window, dark)
        installAmbientBackground(activity, dark)
        val root = activity.window.decorView
        if (root.getTag(R.id.piper_modern_ui_watcher) != true) {
            root.setTag(R.id.piper_modern_ui_watcher, true)
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (PiperUiPreferences.isModern(activity)) applyTree(root, palette(activity))
            }
        }
        root.post { applyTree(root, palette(activity)) }
    }

    fun apply(root: View) {
        if (PiperUiPreferences.isModern(root.context)) {
            val colors = palette(root.context)
            root.backgroundTintList = null
            root.background = rounded(colors.surface, colors.border, 8f, root)
            applyTree(root, colors)
        }
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
            // Text may be rebound by adapters after inflation, so refresh it on every layout pass.
            if (view is TextView || view.getTag(R.id.piper_modern_ui_applied) != palette.hashCode()) {
                applyView(view, palette)
                view.setTag(R.id.piper_modern_ui_applied, palette.hashCode())
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
        if (isPageRoot(name)) {
            view.setBackgroundColor(Color.TRANSPARENT)
            return
        }

        if (name == "modernAuthOverlay") {
            view.setBackgroundColor(Color.TRANSPARENT)
            return
        }

        if (name.endsWith("FeatureIcon", ignoreCase = true)) {
            view.backgroundTintList = null
            view.background = rounded(
                ColorUtils.blendARGB(palette.surface, palette.accent, 0.07f),
                ColorUtils.blendARGB(palette.border, palette.accent, 0.18f),
                8f,
                view
            )
            return
        }

        if (isSettingsRow(name)) {
            val selectable = TypedValue()
            if (view.context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    selectable,
                    true
                ) && selectable.resourceId != 0
            ) {
                view.setBackgroundResource(selectable.resourceId)
            } else {
                view.setBackgroundColor(Color.TRANSPARENT)
            }
            return
        }

        if (isNavigationItem(name) || name == "fragment_container") return

        when (view) {
            is MaterialCardView -> {
                view.backgroundTintList = null
                view.setCardBackgroundColor(palette.surface)
                view.strokeColor = palette.border
                view.strokeWidth = view.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                view.radius = 8f * view.resources.displayMetrics.density
                view.cardElevation = 0f
            }
            is MaterialButton -> {
                view.isAllCaps = false
                val primary = isPrimaryAction(name)
                view.setTextColor(if (primary) palette.onAccent else palette.text)
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (primary) palette.accent else palette.surface
                )
                view.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (primary) palette.accent else palette.border
                )
                view.strokeWidth = view.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                view.cornerRadius = (8f * view.resources.displayMetrics.density).toInt()
                view.iconTint = android.content.res.ColorStateList.valueOf(
                    if (primary) palette.onAccent else palette.secondaryText
                )
            }
            is TextInputLayout -> {
                view.boxBackgroundColor = palette.surface
                view.boxStrokeColor = palette.border
                view.defaultHintTextColor = android.content.res.ColorStateList.valueOf(palette.secondaryText)
                view.setBoxCornerRadii(8f, 8f, 8f, 8f)
            }
            is CompoundButton -> {
                view.setTextColor(palette.text)
            }
            is EditText -> {
                view.backgroundTintList = null
                view.setTextColor(palette.text)
                view.setHintTextColor(palette.secondaryText)
                view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                    palette.secondaryText
                )
                val parentName = (view.parent as? View)?.let(::resourceName).orEmpty()
                view.background = if (
                    view.parent is TextInputLayout || parentName in setOf(
                        "browserSearchBox", "browserAddressRow", "mediaSearchBar"
                    )
                ) {
                    null
                } else {
                    rounded(palette.surface, palette.border, 8f, view)
                }
            }
            is Button -> {
                view.backgroundTintList = null
                view.isAllCaps = false
                view.setTextColor(if (isPrimaryAction(name)) palette.onAccent else palette.text)
                view.background = rounded(
                    if (isPrimaryAction(name)) palette.accent else palette.surface,
                    if (isPrimaryAction(name)) palette.accent else palette.border,
                    8f,
                    view
                )
            }
            is TextView -> {
                modernizeText(view, palette)
                if (
                    (view.isClickable || name == "tvTerminalPromptMode") &&
                    view.background != null && !isNavigationItem(name)
                ) {
                    view.backgroundTintList = null
                    view.background = rounded(palette.surface, palette.border, 8f, view)
                }
            }
            is ImageButton -> {
                view.setBackgroundColor(Color.TRANSPARENT)
                view.imageTintList = if (name in setOf("btnExitBrowser")) {
                    null
                } else {
                    android.content.res.ColorStateList.valueOf(palette.secondaryText)
                }
            }
            is ImageView -> {
                val parentName = (view.parent as? View)?.let(::resourceName).orEmpty()
                if (name in setOf("mediaArtwork", "browserStartLogo")) {
                    view.imageTintList = null
                    return
                }
                when {
                    parentName in setOf(
                        "fakeMapFeatureIcon",
                        "terminalFeatureIcon",
                        "fileManagerFeatureIcon"
                    ) ->
                        view.imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                    (name.startsWith("icon", true) || name.endsWith("Arrow", true)) &&
                        !name.contains("Artwork", true) ->
                        view.imageTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
                }
            }
            is ViewGroup -> {
                if (
                    view.background != null && view.parent != null && !isSpecialSurface(name)
                ) {
                    view.backgroundTintList = null
                    view.background = rounded(palette.surface, palette.border, 8f, view)
                    view.elevation = 0f
                }
            }
            else -> {
                val maxDividerHeight = (2f * view.resources.displayMetrics.density).toInt()
                if (view.layoutParams?.height in 1..maxDividerHeight) {
                    view.setBackgroundColor(palette.border)
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
        val className = view.javaClass.name
        return className.contains("WebView") || className.contains("PlayerView") ||
            className.contains("osmdroid.views.MapView") || name == "terminalScroll"
    }

    private fun preserveTextSurface(name: String): Boolean =
        name.contains("terminalOutput", true) || name.contains("terminalScreen", true)

    private fun isSpecialSurface(name: String): Boolean =
        name.contains("disc", true) || name.contains("video", true) ||
            name.contains("player", true) ||
            name.contains("progress", true) || name == "terminalScroll"

    private fun isPageRoot(name: String): Boolean = name in setOf(
        "homeRoot", "lockRoot", "loginRoot", "signupRoot", "forgotRoot", "welcomeRoot", "permissionRoot",
        "browserRoot", "mediaRoot", "mediaGalleryRoot", "fileManagerRoot", "filePreviewRoot",
        "fakeMapRoot", "terminalRoot", "apkEditorRoot", "textEditorRoot"
    )

    private fun isSettingsRow(name: String): Boolean = name in setOf(
        "layoutDeviceAdmin", "layoutFingerprint", "layoutPasswordToggle", "btnChangeLock",
        "btnPermissions", "layoutUiStyle", "layoutColorMode", "layoutLanguage", "layoutFont",
        "layoutChangeBackground", "layoutResetBackground", "btnAndroidSource", "btnRuntimeSource"
    )

    private fun installAmbientBackground(activity: Activity, dark: Boolean) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<PiperAmbientBackgroundView>(AMBIENT_TAG)
        if (existing != null) {
            existing.darkMode = dark
            return
        }
        val ambient = PiperAmbientBackgroundView(activity).apply {
            tag = AMBIENT_TAG
            darkMode = dark
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(
            ambient,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun textColor(context: Context): Int = palette(context).text

    fun secondaryTextColor(context: Context): Int = palette(context).secondaryText

    fun accentColor(context: Context): Int = palette(context).accent

    fun surfaceColor(context: Context): Int = palette(context).surface

    fun borderColor(context: Context): Int = palette(context).border

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
        background = Color.rgb(248, 248, 246),
        surface = Color.argb(226, 255, 255, 255),
        text = Color.rgb(31, 33, 36),
        secondaryText = Color.rgb(105, 108, 112),
        border = Color.argb(184, 216, 219, 218),
        accent = Color.rgb(18, 124, 86),
        onAccent = Color.WHITE
    )

    private val darkPalette = Palette(
        background = Color.rgb(18, 18, 22),
        surface = Color.argb(228, 28, 30, 34),
        text = Color.rgb(241, 243, 244),
        secondaryText = Color.rgb(174, 178, 182),
        border = Color.argb(190, 64, 68, 72),
        accent = Color.rgb(99, 220, 165),
        onAccent = Color.rgb(9, 45, 31)
    )

    private const val AMBIENT_TAG = "piper_modern_ambient_background"
}
