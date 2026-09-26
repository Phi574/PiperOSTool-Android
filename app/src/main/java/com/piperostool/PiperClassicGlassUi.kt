package com.piperostool

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.example.liquidglass.ScrollEdgeBlurView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayDeque

/** Liquid Glass renderer used by the rebuilt Classic theme. */
object PiperClassicGlassUi {
    private data class Palette(
        val background: Int,
        val glassTop: Int,
        val glassBottom: Int,
        val strongGlass: Int,
        val text: Int,
        val secondaryText: Int,
        val border: Int,
        val accent: Int,
        val onAccent: Int
    )

    fun watch(activity: Activity) {
        val colors = palette(activity)
        applyWindow(activity, colors)
        installBackground(activity)
        val root = activity.window.decorView
        if (root.getTag(R.id.piper_classic_glass_watcher) != true) {
            root.setTag(R.id.piper_classic_glass_watcher, true)
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (!PiperUiPreferences.isModern(activity)) applyTree(root, palette(activity))
            }
        }
        root.post { applyTree(root, colors) }
    }

    fun apply(root: View) {
        val colors = palette(root.context)
        root.backgroundTintList = null
        root.background = glassDrawable(root, colors, 24f, strong = true)
        applyTree(root, colors)
    }

    fun textColor(context: Context): Int = palette(context).text
    fun secondaryTextColor(context: Context): Int = palette(context).secondaryText
    fun accentColor(context: Context): Int = palette(context).accent
    fun surfaceColor(context: Context): Int = palette(context).strongGlass
    fun borderColor(context: Context): Int = palette(context).border

    // CardView ignores View.setPadding; use content padding for system insets.
    fun setContainerPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view is MaterialCardView) view.setContentPadding(left, top, right, bottom)
        else view.setPadding(left, top, right, bottom)
    }

    private fun applyWindow(activity: Activity, colors: Palette) {
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        activity.window.decorView.setBackgroundColor(colors.background)
    }

    private fun installBackground(activity: Activity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val forceDarkAuth = activity is WelcomeActivity || activity is LoginActivity ||
            activity is SignupActivity || activity is ForgotPassword
        val customHomeBackground = activity.findViewById<ImageView?>(R.id.homeBackground)
        if (customHomeBackground != null) {
            customHomeBackground.visibility = View.VISIBLE
            customHomeBackground.alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                customHomeBackground.setRenderEffect(
                    RenderEffect.createBlurEffect(3.5f, 3.5f, Shader.TileMode.CLAMP)
                )
            }
            PiperGlassBackdropStore.capture(activity, customHomeBackground)
            return
        }
        val existing = content.findViewWithTag<PiperLiquidGlassBackgroundView>(BACKGROUND_TAG)
        if (existing != null) {
            existing.darkMode = forceDarkAuth || isDark(activity)
            return
        }
        content.addView(
            PiperLiquidGlassBackgroundView(activity).apply {
                tag = BACKGROUND_TAG
                darkMode = forceDarkAuth || isDark(activity)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        PiperGlassBackdropStore.capture(activity, null)
    }

    private fun applyTree(root: View, colors: Palette) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        val signature = colors.hashCode()
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is TextView || view.getTag(R.id.piper_classic_glass_applied) != signature) {
                applyView(view, colors)
                view.setTag(R.id.piper_classic_glass_applied, signature)
                if (view is TextView) {
                    view.post {
                        if (!PiperUiPreferences.isModern(view.context)) applyText(view, palette(view.context))
                    }
                }
            }
            if (view is ViewGroup && !preserveChildren(view)) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
        installNativeGlassSurfaces(root)
        installScrollEdgeEffects(root)
    }

    /**
     * Use the real SDF/AGSL lens for card surfaces while leaving their existing
     * content and layout params untouched. The source is the screen backdrop,
     * so text and icons are never captured into their own glass.
     */
    private fun installNativeGlassSurfaces(root: View) {
        val backdrop = findBackdrop(root) ?: return
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current is ViewGroup && isGlassSurface(current) && current.getTag(R.id.piper_native_glass_installed) != true) {
                // RecyclerView binds cards before they are attached. Installing
                // the lens at that point produces an empty/black first frame;
                // let the card enter the window before capturing the backdrop.
                if (!current.isAttachedToWindow) {
                    current.post { installNativeGlassSurfaces(current) }
                    continue
                }
                val listItem = current.parent is RecyclerView || current.parent is android.widget.AbsListView
                val glass = LiquidGlassView(current.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    material = GlassMaterial.CLEAR
                    cornerRadius = dp(current, 24f).toFloat()
                    refractionHeight = dp(current, 36f).toFloat()
                    bevelWidth = dp(current, 10f).toFloat()
                    dispersionStrength = 0.08f
                    enableSensorHighlight = true
                    // A RecyclerView can hold hundreds of lenses. A static
                    // capture per attached item prevents RenderNode races
                    // while preserving the native refraction and border.
                    enableDynamicBackground = !listItem
                    backdropSource = backdrop
                    // The lens is visual-only; the card below owns input.
                    isClickable = false
                    isFocusable = false
                    isEnabled = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                if (current is MaterialCardView) {
                    current.setCardBackgroundColor(Color.TRANSPARENT)
                    current.background = null
                    current.strokeWidth = 0
                } else {
                    current.background = null
                }
                current.addView(glass, 0)
                current.setTag(R.id.piper_native_glass_installed, true)
                if (!listItem) {
                    glass.post { glass.invalidate() }
                }
            } else if (
                current is FrameLayout &&
                resourceName(current).endsWith("FeatureIcon", ignoreCase = true) &&
                current.getTag(R.id.piper_native_glass_installed) != true
            ) {
                val glass = LiquidGlassView(current.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    material = GlassMaterial.CLEAR
                    cornerRadius = dp(current, 18f).toFloat()
                    refractionHeight = dp(current, 22f).toFloat()
                    bevelWidth = dp(current, 7f).toFloat()
                    dispersionStrength = 0.06f
                    enableSensorHighlight = true
                    enableDynamicBackground = true
                    backdropSource = backdrop
                    isClickable = false
                    isFocusable = false
                    isEnabled = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                current.background = null
                current.addView(glass, 0)
                current.setTag(R.id.piper_native_glass_installed, true)
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) pending.addLast(current.getChildAt(index))
            }
        }
    }

    private fun isGlassSurface(view: View): Boolean {
        // The native lens uses FrameLayout params and is safe on cards. Plain
        // LinearLayouts keep their layout-managed glass drawable instead.
        return view is MaterialCardView && resourceName(view) !in drawableGlassOnlySurfaces
    }

    private fun findBackdrop(root: View): View? {
        root.findViewById<View?>(R.id.homeBackground)?.let { return it }
        // Fragments live below HomeActivity's content root, so their local
        // search cannot see the shared background. Never fall back to the
        // DecorView when that activity background is available: capturing the
        // DecorView from a lens creates a recursive RenderNode draw.
        root.rootView.findViewById<View?>(R.id.homeBackground)?.let { return it }
        root.rootView.findViewWithTag<View>(BACKGROUND_TAG)?.let { return it }
        var context = root.context
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context.findViewById<View>(R.id.homeBackground)
                    ?: context.window.decorView.findViewWithTag<View>(BACKGROUND_TAG)
            }
            context = context.baseContext
        }
        return null
    }

    /** Adds a lightweight progressive blur only to the edge of a scrolling menu. */
    private fun installScrollEdgeEffects(root: View) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val scroll = pending.removeFirst()
            if (scroll is NestedScrollView || scroll is android.widget.ScrollView || scroll is RecyclerView) {
                val host = scroll.parent as? FrameLayout
                if (host != null && host.findViewWithTag<ScrollEdgeBlurView>(SCROLL_TOP_TAG) == null) {
                    val height = dp(scroll, 48f)
                    host.addView(ScrollEdgeBlurView(scroll.context).apply {
                        tag = SCROLL_TOP_TAG
                        edge = ScrollEdgeBlurView.Edge.TOP
                        maxBlurRadius = dp(scroll, 30f).toFloat()
                        bindScrollView(scroll)
                        translationZ = dp(scroll, 2f).toFloat()
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        height
                    ).apply { gravity = android.view.Gravity.TOP })
                    host.addView(ScrollEdgeBlurView(scroll.context).apply {
                        tag = SCROLL_BOTTOM_TAG
                        edge = ScrollEdgeBlurView.Edge.BOTTOM
                        maxBlurRadius = dp(scroll, 30f).toFloat()
                        bindScrollView(scroll)
                        translationZ = dp(scroll, 2f).toFloat()
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        height
                    ).apply { gravity = android.view.Gravity.BOTTOM })
                }
                installScrollMotionCoordinator(scroll, root)
            }
            if (scroll is ViewGroup) {
                for (index in 0 until scroll.childCount) pending.addLast(scroll.getChildAt(index))
            }
        }
    }

    private fun installScrollMotionCoordinator(scroll: View, root: View) {
        if (scroll.getTag(R.id.piper_classic_glass_watcher) != null) return
        // Keep the full-quality live lens enabled for this release. The later
        // performance pass can switch this to an idle cache on low-end devices.
        val settle = Runnable { setNativeDynamic(root, true) }
        val listener = ViewTreeObserver.OnScrollChangedListener {
            setNativeDynamic(root, true)
            scroll.removeCallbacks(settle)
            scroll.postDelayed(settle, 160L)
        }
        scroll.viewTreeObserver.addOnScrollChangedListener(listener)
        scroll.setTag(R.id.piper_classic_glass_watcher, listener)
    }

    private fun setNativeDynamic(root: View, enabled: Boolean) {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current is LiquidGlassView && current.getTag(R.id.piper_native_glass_installed) != true) {
                current.enableDynamicBackground = enabled
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) pending.addLast(current.getChildAt(index))
            }
        }
    }

    private fun applyView(view: View, colors: Palette) {
        val name = resourceName(view)
        if (name == "homeBackground") return
        if (view.tag == "piper_glass_content") {
            view.background = null
            return
        }
        if (view is ImageButton) view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (view is TextView) {
            val size = dp(view, 20f)
            val icons = view.compoundDrawablesRelative
            if (icons.any { it != null && (it.bounds.width() != size || it.bounds.height() != size) }) {
                icons.forEach { it?.setBounds(0, 0, size, size) }
                view.setCompoundDrawablesRelative(icons[0], icons[1], icons[2], icons[3])
            }
        }
        if (isPageRoot(name)) {
            view.backgroundTintList = null
            view.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        if (name == "fragment_container" || isNavigationItem(name) || isSpecialSurface(name)) return
        if (name in transparentSettingsRows) {
            view.backgroundTintList = null
            view.setBackgroundColor(Color.TRANSPARENT)
            view.elevation = 0f
            return
        }

        when (view) {
            is android.widget.Spinner -> {
                view.backgroundTintList = null
                view.background = glassDrawable(view, colors, 18f)
                view.setPopupBackgroundDrawable(glassDrawable(view, colors, 20f, strong = true))
            }
            is MaterialCardView -> {
                view.backgroundTintList = null
                view.setCardBackgroundColor(Color.TRANSPARENT)
                view.strokeWidth = 0
                view.radius = dp(view, 24f).toFloat()
                // Native LiquidGlassView is installed immediately after this
                // pass. Keep the card transparent during that hand-off so the
                // old drawable cannot flash before the refractive lens arrives.
                view.background = if (isGlassSurface(view)) {
                    null
                } else {
                    glassDrawable(view, colors, 24f, strong = true)
                }
                view.cardElevation = dp(view, 3f).toFloat()
            }
            is MaterialButton -> {
                val primary = isPrimaryAction(name)
                val authScreen = isAuthScreen(view)
                view.isAllCaps = false
                view.setTextColor(if (primary) colors.onAccent else colors.text)
                // MaterialButtonToggleGroup reads the button shape during
                // measure. Keep its managed background intact; replacing it
                // makes Material Components throw on the next layout pass.
                if (view.parent is com.google.android.material.button.MaterialButtonToggleGroup) {
                    view.backgroundTintList = null
                    view.iconTint = ColorStateList.valueOf(if (primary) colors.onAccent else colors.text)
                    return
                }
                view.backgroundTintList = null
                view.background = if (primary && !authScreen && !isFeatureScreen(view)) {
                    solidDrawable(view, colors.accent, colors.border, 22f)
                } else {
                    glassDrawable(view, colors, 22f, strong = true)
                }
                view.strokeWidth = 0
                view.cornerRadius = dp(view, 22f)
                view.iconTint = ColorStateList.valueOf(if (primary) colors.onAccent else colors.text)
                view.elevation = dp(view, 2f).toFloat()
            }
            is TextInputLayout -> {
                view.boxBackgroundColor = Color.TRANSPARENT
                view.boxStrokeColor = colors.border
                view.defaultHintTextColor = ColorStateList.valueOf(colors.secondaryText)
                val radius = dp(view, 18f).toFloat()
                view.setBoxCornerRadii(radius, radius, radius, radius)
                view.background = glassDrawable(view, colors, 18f, strong = true)
            }
            is CompoundButton -> {
                view.setTextColor(colors.text)
                view.buttonTintList = ColorStateList.valueOf(colors.accent)
            }
            is EditText -> {
                view.setTextColor(colors.text)
                view.setHintTextColor(colors.secondaryText)
                view.compoundDrawableTintList = ColorStateList.valueOf(colors.secondaryText)
                val parentName = (view.parent as? View)?.let(::resourceName).orEmpty()
                if (view.parent !is TextInputLayout && parentName !in transparentInputParents) {
                    view.backgroundTintList = null
                    view.background = glassDrawable(view, colors, 18f)
                }
            }
            is Button -> {
                val primary = isPrimaryAction(name)
                val authScreen = isAuthScreen(view)
                view.isAllCaps = false
                view.setTextColor(if (primary) colors.onAccent else colors.text)
                view.backgroundTintList = null
                view.background = if (primary && !authScreen && !isFeatureScreen(view)) {
                    solidDrawable(view, colors.accent, colors.border, 22f)
                } else {
                    glassDrawable(view, colors, 22f, strong = true)
                }
                view.elevation = dp(view, 2f).toFloat()
            }
            is TextView -> {
                applyText(view, colors)
                if (view.isClickable && view.background != null && !isNavigationItem(name)) {
                    view.backgroundTintList = null
                    view.background = glassDrawable(view, colors, 22f)
                    view.elevation = dp(view, 1f).toFloat()
                }
            }
            is ImageButton -> {
                view.imageTintList = if (name == "btnExitBrowser") {
                    null
                } else {
                    ColorStateList.valueOf(
                        if (name.contains("delete", true)) Color.rgb(255, 151, 158) else colors.text
                    )
                }
                view.backgroundTintList = null
                view.background = ovalGlassDrawable(view, colors)
                view.elevation = dp(view, 2f).toFloat()
            }
            is ImageView -> {
                val parentName = (view.parent as? View)?.let(::resourceName).orEmpty()
                if (name in preservedImages || name.contains("artwork", true) || name.contains("thumbnail", true)) {
                    view.imageTintList = null
                } else if (
                    name.startsWith("icon", true) || name.endsWith("Arrow", true) ||
                    parentName.endsWith("FeatureIcon", true)
                ) {
                    view.imageTintList = ColorStateList.valueOf(colors.text)
                }
            }
            is ViewGroup -> {
                if (view.background != null && view.parent != null && shouldGlassContainer(name)) {
                    view.backgroundTintList = null
                    view.background = glassDrawable(view, colors, containerRadius(name), strong = true)
                    view.elevation = dp(view, if (name.contains("bottomNav", true)) 5f else 2f).toFloat()
                }
            }
            else -> {
                val dividerHeight = dp(view, 2f)
                if (view.layoutParams?.height in 1..dividerHeight) {
                    view.setBackgroundColor(ColorUtils.setAlphaComponent(colors.border, 100))
                }
            }
        }
    }

    private fun applyText(view: TextView, colors: Palette) {
        if (resourceName(view) in setOf("tvTerminalOutput", "etTerminalCommand", "tvTerminalPromptMode")) return
        val currentAlpha = Color.alpha(view.currentTextColor)
        if (currentAlpha < 70) return
        val sizeSp = view.textSize / view.resources.displayMetrics.scaledDensity
        val heading = sizeSp >= 16f || view.typeface?.style == Typeface.BOLD
        view.setTextColor(if (heading) colors.text else colors.secondaryText)
        view.compoundDrawableTintList = ColorStateList.valueOf(colors.text)
    }

    private fun shouldGlassContainer(name: String): Boolean {
        if (name.isBlank()) return true
        return glassNameHints.any { name.contains(it, true) }
    }

    private fun containerRadius(name: String): Float = when {
        name.contains("bottomNav", true) -> 32f
        name.contains("toolbar", true) || name.contains("bar", true) -> 22f
        name.contains("row", true) -> 18f
        else -> 24f
    }

    private fun isPageRoot(name: String): Boolean =
        name.endsWith("Root", true) || name in pageRoots

    private fun isPrimaryAction(name: String): Boolean = primaryHints.any { name.contains(it, true) }

    private fun isAuthScreen(view: View): Boolean {
        var context: Context? = view.context
        while (context is ContextWrapper) {
            if (context is AccountProfileActivity || context is WelcomeActivity || context is LoginActivity ||
                context is SignupActivity || context is ForgotPassword
            ) return true
            context = context.baseContext
        }
        return context is AccountProfileActivity || context is WelcomeActivity || context is LoginActivity ||
            context is SignupActivity || context is ForgotPassword
    }

    private fun isFeatureScreen(view: View): Boolean {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is PiperMediaActivity || context is PiperTerminalActivity ||
                context is FakeMapActivity || context is PiperFileManagerActivity ||
                context is PiperRemoteActivity || context is PiperAppleMirrorActivity) return true
            context = context.baseContext
        }
        return false
    }

    private fun isNavigationItem(name: String): Boolean = name in navigationItems

    private fun isSpecialSurface(name: String): Boolean = name in setOf(
        "fakeMapView", "mediaPlayerView", "mediaStage", "mediaDiscContainer",
        "terminalScroll", "remoteViewerPanel", "remoteFrameView", "remoteVideoSurface",
        "appleMirrorViewer", "appleMirrorSurface"
    ) || name.contains("progress", true) || name.contains("camera", true) || name.contains("previewSurface", true)

    private fun preserveChildren(view: View): Boolean {
        val className = view.javaClass.name
        val name = resourceName(view)
        return className.contains("WebView") || className.contains("PlayerView") ||
            className.contains("osmdroid.views.MapView") || name == "terminalScroll"
    }

    private fun glassDrawable(view: View, colors: Palette, radiusDp: Float, strong: Boolean = false) =
        PiperRefractiveGlassDrawable(
            target = view,
            radiusPx = dp(view, radiusDp).toFloat(),
            tintColor = if (strong) colors.strongGlass else colors.glassBottom,
            borderColor = colors.border
        )

    private fun ovalGlassDrawable(view: View, colors: Palette) = PiperRefractiveGlassDrawable(
        target = view,
        radiusPx = minOf(view.width, view.height).takeIf { it > 0 }?.div(2f)
            ?: dp(view, 24f).toFloat(),
        tintColor = colors.glassBottom,
        borderColor = colors.border,
        oval = true
    )

    private fun solidDrawable(view: View, fill: Int, stroke: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(view, radiusDp).toFloat()
            setStroke(dp(view, 1f), stroke)
        }

    private fun resourceName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun dp(view: View, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            view.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

    private fun palette(context: Context): Palette = if (isDark(context)) darkPalette else lightPalette

    private fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private val darkPalette = Palette(
        background = Color.rgb(7, 10, 17),
        glassTop = Color.argb(64, 56, 64, 78),
        glassBottom = Color.argb(34, 12, 16, 24),
        strongGlass = Color.argb(52, 20, 26, 36),
        text = Color.rgb(247, 249, 252),
        secondaryText = Color.rgb(194, 202, 214),
        border = Color.argb(112, 232, 240, 250),
        accent = Color.rgb(63, 143, 245),
        onAccent = Color.WHITE
    )

    private val lightPalette = Palette(
        background = Color.rgb(7, 10, 17),
        glassTop = Color.argb(60, 48, 57, 72),
        glassBottom = Color.argb(32, 9, 13, 21),
        strongGlass = Color.argb(48, 18, 23, 33),
        text = Color.rgb(248, 250, 253),
        secondaryText = Color.rgb(204, 212, 224),
        border = Color.argb(112, 235, 243, 252),
        accent = Color.rgb(45, 126, 235),
        onAccent = Color.WHITE
    )

    private val transparentInputParents = setOf(
        "browserSearchBox", "browserAddressRow", "mediaSearchBar"
    )
    private val preservedImages = setOf(
        "homeBackground", "mediaArtwork", "browserStartLogo", "appIcon", "apkIcon"
    )
    private val glassNameHints = listOf(
        "card", "panel", "container", "content", "section", "toolbar", "header",
        "bottomNav", "searchBox", "addressRow", "feature", "status", "row", "sheet", "btn"
    )
    private val primaryHints = listOf(
        "start", "login", "signup", "send", "run", "install", "save", "connect", "apply"
    )
    private val specialHints = listOf(
        "video", "player", "progress", "terminalScroll", "map", "camera", "previewSurface"
    )
    private val pageRoots = setOf(
        "fragment_container", "browserRoot", "homeRoot", "mediaRoot", "fileManagerRoot",
        "apkEditorRoot", "terminalRoot", "fakeMapRoot", "permissionRoot"
    )
    private val navigationItems = setOf(
        "navHome", "navBeta", "navApps", "navSettings", "navDevices",
        "iconHome", "iconNews", "iconApps", "iconSettings", "iconDevices",
        "txtHome", "txtNews", "txtApps", "txtSettings", "txtDevices"
    )
    // Every settings section is a native lens surface. Keeping this list empty
    // makes Security & Permissions use the same refractive treatment as Appearance.
    private val drawableGlassOnlySurfaces = emptySet<String>()
    private val transparentSettingsRows = setOf(
        "layoutDeviceAdmin",
        "layoutFingerprint",
        "layoutPasswordToggle",
        "btnChangeLock",
        "btnPermissions",
        "layoutUiStyle",
        "layoutColorMode",
        "layoutLanguage",
        "layoutFont",
        "layoutChangeBackground",
        "layoutResetBackground",
        "btnSettingLogout",
        "btnAndroidSource",
        "btnRuntimeSource",
        "infoSectionHeader"
    )
    private const val BACKGROUND_TAG = "piper_classic_liquid_glass_background"
    private const val SCROLL_TOP_TAG = "piper_classic_scroll_edge_top"
    private const val SCROLL_BOTTOM_TAG = "piper_classic_scroll_edge_bottom"
}
