package com.piperostool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hbb20.CountryCodePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object AuthJsonRenderer {

    fun render(context: Context, screen: JSONObject): View =
        buildNode(context, screen.getJSONObject("layout"), null)

    fun loadingView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(12, 17, 27))
            isFocusableInTouchMode = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 28), dp(context, 24), dp(context, 28), dp(context, 24))
        }
        content.addView(ProgressBar(context), LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)))
        content.addView(
            TextView(context).apply {
                text = "Đang tải giao diện xác thực…"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(context, 18) }
        )
        root.addView(content, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return root
    }

    fun unavailableView(
        context: Context,
        config: AuthConfig,
        onClose: () -> Unit
    ): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(9, 13, 22))
            isClickable = true
            isFocusableInTouchMode = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 26), dp(context, 28), dp(context, 26), dp(context, 24))
            background = rounded(
                Color.rgb(23, 31, 45), dp(context, 24).toFloat(), Color.rgb(67, 79, 98)
            )
            elevation = dp(context, 8).toFloat()
        }
        val icon = TextView(context).apply {
            text = "!"
            gravity = Gravity.CENTER
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(255, 89, 89))
            background = rounded(Color.rgb(61, 31, 39), dp(context, 99).toFloat(), Color.rgb(255, 89, 89))
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(context, 64), dp(context, 64)))
        card.addView(
            TextView(context).apply {
                text = config.unavailableTitle
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 18) }
        )
        card.addView(
            TextView(context).apply {
                text = config.unavailableMessage
                setTextColor(Color.rgb(190, 199, 212))
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(dp(context, 3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 10) }
        )
        card.addView(
            Button(context).apply {
                text = config.unavailableButton.ifBlank { "Đóng" }
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(48, 107, 238), dp(context, 16).toFloat())
                setOnClickListener { onClose() }
            },
            LinearLayout.LayoutParams(-1, dp(context, 50)).apply { topMargin = dp(context, 22) }
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                leftMargin = dp(context, 28)
                rightMargin = dp(context, 28)
            }
        )
        return root
    }

    fun unavailableOverlay(context: Context, config: AuthConfig, onClose: () -> Unit): View {
        val overlay = unavailableView(context, config, onClose)
        overlay.visibility = View.GONE
        overlay.tag = "auth-unavailable-overlay"
        return overlay
    }

    private fun buildNode(context: Context, node: JSONObject, parent: ViewGroup?): View {
        val type = node.optString("type")
        val view: View = when (type) {
            "frame" -> FrameLayout(context)
            "linear" -> LinearLayout(context).apply {
                orientation = if (node.optString("orientation") == "horizontal")
                    LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            "relative" -> RelativeLayout(context)
            "scroll" -> NestedScrollView(context).apply {
                isFillViewport = node.optBoolean("fillViewport", true)
                clipToPadding = false
            }
            "text" -> AppCompatTextView(context)
            "edit" -> if (node.optBoolean("material", false)) TextInputEditText(context)
                else AppCompatEditText(context)
            "button" -> Button(context)
            "image" -> ImageView(context)
            "imageButton" -> ImageButton(context)
            "inputLayout" -> TextInputLayout(context)
            "countryPicker" -> CountryCodePicker(context)
            "view", "space" -> View(context)
            else -> throw IllegalArgumentException("Component auth không hỗ trợ: $type")
        }

        node.optString("id").takeIf { it.isNotBlank() }?.let { name ->
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            if (id != 0) view.id = id
        }
        applyCommonAttributes(context, view, node)

        if (view is TextView) {
            resolveText(context, node.opt("text"))?.let { view.text = it }
            resolveText(context, node.opt("hint"))?.let { view.hint = it }
            node.optString("textColor").takeIf { it.isNotBlank() }?.let {
                view.setTextColor(resolveColor(context, it, Color.WHITE))
            }
            if (node.has("textSize")) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, node.optDouble("textSize").toFloat())
            if (node.optBoolean("bold", false)) view.setTypeface(view.typeface, Typeface.BOLD)
            view.gravity = parseGravity(node.optString("gravity"), view.gravity)
            if (view is Button) view.isAllCaps = node.optBoolean("allCaps", false)
        }
        if (view is EditText) {
            view.inputType = parseInputType(node.optString("inputType"))
            if (node.has("singleLine")) view.isSingleLine = node.optBoolean("singleLine")
            if (node.has("maxLength")) {
                view.filters = arrayOf(android.text.InputFilter.LengthFilter(node.optInt("maxLength")))
            }
            if (node.optBoolean("selectAllOnFocus", false)) view.setSelectAllOnFocus(true)
        }
        if (view is ImageView) {
            val image = node.optString("src")
            if (image.isNotBlank()) resolveDrawable(context, image)?.let(view::setImageDrawable)
            view.scaleType = when (node.optString("scaleType")) {
                "centerCrop" -> ImageView.ScaleType.CENTER_CROP
                "fitCenter" -> ImageView.ScaleType.FIT_CENTER
                "centerInside" -> ImageView.ScaleType.CENTER_INSIDE
                else -> ImageView.ScaleType.CENTER_INSIDE
            }
            node.optString("contentDescription").takeIf { it.isNotBlank() }?.let {
                view.contentDescription = resolveText(context, it)
            }
            node.optString("tint").takeIf { it.isNotBlank() }?.let {
                view.setColorFilter(resolveColor(context, it, Color.WHITE))
            }
        }
        if (view is TextInputLayout) {
            resolveText(context, node.opt("hint"))?.let { view.hint = it }
            view.isHintEnabled = node.optBoolean("hintEnabled", true)
        }
        if (view is CountryCodePicker) {
            node.optString("defaultCountry").takeIf { it.isNotBlank() }?.let {
                view.setDefaultCountryUsingNameCode(it)
            }
        }

        val children = node.optJSONArray("children")
        if (view is ViewGroup && children != null) {
            for (index in 0 until children.length()) {
                val childNode = children.optJSONObject(index) ?: continue
                val child = buildNode(context, childNode, view)
                view.addView(child, makeLayoutParams(context, view, childNode))
            }
        }

        if (parent == null) {
            view.layoutParams = ViewGroup.LayoutParams(
                dimension(context, node.opt("width"), ViewGroup.LayoutParams.MATCH_PARENT),
                dimension(context, node.opt("height"), ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        return view
    }

    private fun applyCommonAttributes(context: Context, view: View, node: JSONObject) {
        val padding = node.optJSONArray("padding")
        if (padding != null) {
            val values = paddingValues(padding)
            view.setPadding(dp(context, values[0]), dp(context, values[1]), dp(context, values[2]), dp(context, values[3]))
        } else if (node.has("paddingAll")) {
            val p = dp(context, node.optInt("paddingAll"))
            view.setPadding(p, p, p, p)
        }
        node.optString("background").takeIf { it.isNotBlank() }?.let {
            applyBackground(context, view, it)
        }
        if (node.has("visibility")) {
            view.visibility = when (node.optString("visibility")) {
                "gone" -> View.GONE
                "invisible" -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        if (node.has("alpha")) view.alpha = node.optDouble("alpha", 1.0).toFloat()
        if (node.has("minHeight")) view.minimumHeight = dp(context, node.optInt("minHeight"))
        if (node.has("clickable")) view.isClickable = node.optBoolean("clickable")
        if (node.has("focusable")) view.isFocusable = node.optBoolean("focusable")
    }

    private fun makeLayoutParams(context: Context, parent: ViewGroup, node: JSONObject): ViewGroup.LayoutParams {
        val width = dimension(context, node.opt("width"), ViewGroup.LayoutParams.WRAP_CONTENT)
        val height = dimension(context, node.opt("height"), ViewGroup.LayoutParams.WRAP_CONTENT)
        val params: ViewGroup.MarginLayoutParams = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(width, height, node.optDouble("weight", 0.0).toFloat())
            is RelativeLayout -> RelativeLayout.LayoutParams(width, height)
            else -> FrameLayout.LayoutParams(width, height)
        }
        val margins = node.optJSONArray("margin")
        if (margins != null) {
            val values = paddingValues(margins)
            params.setMargins(dp(context, values[0]), dp(context, values[1]), dp(context, values[2]), dp(context, values[3]))
        }
        if (params is FrameLayout.LayoutParams) {
            params.gravity = parseGravity(node.optString("layoutGravity"), Gravity.TOP or Gravity.START)
        }
        return params
    }

    private fun dimension(context: Context, value: Any?, default: Int): Int = when (value) {
        is Number -> dp(context, value.toInt())
        is String -> when (value) {
            "match", "match_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
            "wrap", "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
            "0dp" -> 0
            else -> value.removeSuffix("dp").toIntOrNull()?.let { dp(context, it) } ?: default
        }
        else -> default
    }

    private fun paddingValues(array: JSONArray): IntArray {
        val first = array.optInt(0, 0)
        return when (array.length()) {
            1 -> intArrayOf(first, first, first, first)
            2 -> intArrayOf(first, array.optInt(1), first, array.optInt(1))
            3 -> intArrayOf(first, array.optInt(1), array.optInt(2), array.optInt(1))
            else -> intArrayOf(first, array.optInt(1), array.optInt(2), array.optInt(3))
        }
    }

    private fun resolveText(context: Context, value: Any?): CharSequence? {
        if (value == null || value == JSONObject.NULL) return null
        val raw = value.toString()
        if (raw.startsWith("@string/")) {
            val id = context.resources.getIdentifier(raw.removePrefix("@string/"), "string", context.packageName)
            if (id != 0) return context.getText(id)
        }
        return raw
    }

    private fun resolveColor(context: Context, value: String, fallback: Int): Int = try {
        when {
            value.startsWith("@color/") -> {
                val id = context.resources.getIdentifier(value.removePrefix("@color/"), "color", context.packageName)
                if (id != 0) ContextCompat.getColor(context, id) else fallback
            }
            value.startsWith("#") -> Color.parseColor(value)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }

    private fun resolveDrawable(context: Context, value: String): android.graphics.drawable.Drawable? {
        val name = value.removePrefix("@drawable/").removePrefix("drawable/")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return null
        return ContextCompat.getDrawable(context, id)
    }

    private fun applyBackground(context: Context, view: View, value: String) {
        when {
            value == "@null" -> view.background = null
            value.startsWith("?attr/") -> {
                val attribute = when (value.removePrefix("?attr/")) {
                    "selectableItemBackgroundBorderless" -> android.R.attr.selectableItemBackgroundBorderless
                    else -> android.R.attr.selectableItemBackground
                }
                val typedValue = TypedValue()
                if (context.theme.resolveAttribute(attribute, typedValue, true)) {
                    view.setBackgroundResource(typedValue.resourceId)
                }
            }
            value.startsWith("@color/") || value.startsWith("#") ->
                view.setBackgroundColor(resolveColor(context, value, Color.TRANSPARENT))
            else -> resolveDrawable(context, value)?.let { view.background = it }
        }
    }

    private fun parseGravity(value: String, fallback: Int): Int {
        if (value.isBlank()) return fallback
        return value.split('|').fold(0) { result, token ->
            result or when (token.trim()) {
                "center" -> Gravity.CENTER
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "start", "left" -> Gravity.START
                "end", "right" -> Gravity.END
                else -> 0
            }
        }.takeIf { it != 0 } ?: fallback
    }

    private fun parseInputType(value: String): Int = when (value) {
        "textEmailAddress" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        "textPassword" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        "textPersonName" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        "numberPassword" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        "phone" -> android.text.InputType.TYPE_CLASS_PHONE
        "number" -> android.text.InputType.TYPE_CLASS_NUMBER
        else -> android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(1, stroke)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

object AuthJsonHost {

    fun install(
        activity: AppCompatActivity,
        screenId: String,
        onReady: () -> Unit
    ): Job {
        activity.setContentView(AuthJsonRenderer.loadingView(activity))
        return activity.lifecycleScope.launch {
            try {
                val config = AuthConfigRepository.loadManifest(activity)
                val rule = config.screens.firstOrNull { it.id == screenId }
                    ?: throw IllegalStateException("Thiếu màn hình $screenId trong auth.json.")
                val screen = AuthConfigRepository.loadScreen(
                    activity,
                    screenId,
                    allowOfflineDefinition = true,
                    knownManifest = config
                )
                val root = AuthJsonRenderer.render(activity, screen) as? FrameLayout
                    ?: throw IllegalStateException("Root của màn hình auth phải là frame.")
                val userIsSignedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                val overlay = AuthJsonRenderer.unavailableOverlay(activity, config) {
                    activity.finish()
                }
                val initiallyAvailable = isAvailable(
                    rule,
                    config,
                    userIsSignedIn,
                    AuthConfigRepository.hasInternet(activity)
                )
                overlay.visibility = if (initiallyAvailable) View.GONE else View.VISIBLE
                root.addView(
                    overlay,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                activity.setContentView(root)
                AuthScreenUi.apply(activity, root)
                onReady()

                NetworkAccess.observe(activity, activity) { online ->
                    val signedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                    overlay.visibility = if (isAvailable(rule, config, signedIn, online)) View.GONE else View.VISIBLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activity.setContentView(
                    AuthJsonRenderer.fallbackUnavailable(activity) { activity.finish() }
                )
            }
        }
    }

    private fun isAvailable(
        rule: AuthScreenRule,
        config: AuthConfig,
        signedIn: Boolean,
        online: Boolean
    ): Boolean =
        rule.enabled &&
            (!rule.requiresNetwork || online) &&
            (!rule.requiresSignedIn || signedIn) &&
            (signedIn || online || config.signedOutOfflinePolicy != "show_unavailable")
}

private fun AuthJsonRenderer.fallbackUnavailable(context: Context, onClose: () -> Unit): View {
    val fallback = AuthConfig(
        signedInOfflinePolicy = "use_cached_session",
        signedOutOfflinePolicy = "show_unavailable",
        unavailableTitle = "Tính năng hiện không khả dụng",
        unavailableMessage = "Tính năng hiện tại không khả dụng.\nVui lòng kiểm tra mạng hoặc thử lại sau.",
        unavailableIcon = "error_bug",
        unavailableButton = "Đóng",
        screens = emptyList()
    )
    return AuthJsonRenderer.unavailableView(context, fallback, onClose)
}
