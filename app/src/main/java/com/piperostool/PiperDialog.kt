package com.piperostool

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

object PiperDialog {
    fun showMessage(
        context: Context,
        title: String,
        message: String,
        icon: Int? = null,
        positiveLabel: String = context.getString(android.R.string.ok),
        onPositive: (() -> Unit)? = null
    ): Dialog = showCustom(
        context = context,
        title = title,
        message = message,
        icon = icon,
        positiveLabel = positiveLabel,
        negativeLabel = null,
        onPositive = {
            onPositive?.invoke()
            true
        }
    )

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveLabel: String,
        negativeLabel: String = context.getString(android.R.string.cancel),
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog = showCustom(
        context = context,
        title = title,
        message = message,
        positiveLabel = positiveLabel,
        negativeLabel = negativeLabel,
        destructive = destructive,
        onPositive = {
            onConfirm()
            true
        }
    )

    fun showCustom(
        context: Context,
        title: String,
        message: String? = null,
        icon: Int? = null,
        content: View? = null,
        positiveLabel: String,
        negativeLabel: String? = context.getString(android.R.string.cancel),
        neutralLabel: String? = null,
        destructive: Boolean = false,
        onPositive: () -> Boolean,
        onNeutral: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(PiperModernUi.surfaceColor(context))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), PiperModernUi.borderColor(context))
            }
        }
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            icon?.let { resource ->
                addView(ImageView(context).apply {
                    setImageResource(resource)
                    imageTintList = if (resource in setOf(R.drawable.a2tn, R.drawable.a3tn, R.drawable.browser, R.drawable.nhacvideo)) {
                        null
                    } else ColorStateList.valueOf(PiperModernUi.accentColor(context))
                    contentDescription = null
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)).apply {
                    marginEnd = dp(context, 12)
                })
            }
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTextColor(PiperModernUi.textColor(context))
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        message?.let { value ->
            root.addView(ScrollView(context).apply {
                addView(TextView(context).apply {
                    text = value
                    textSize = 14f
                    setLineSpacing(0f, 1.15f)
                    setTextColor(PiperModernUi.secondaryTextColor(context))
                    setPadding(0, dp(context, 16), 0, dp(context, 4))
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (value.length > 320) {
                    (context.resources.displayMetrics.heightPixels * 0.38f).toInt()
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            ))
        }
        content?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            root.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 14) })
        }
        val buttons = LinearLayout(context).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 14), 0, 0)
        }
        neutralLabel?.let { label ->
            buttons.addView(dialogButton(context, label, false) {
                dialog.dismiss()
                onNeutral?.invoke()
            })
        }
        negativeLabel?.let { label ->
            buttons.addView(dialogButton(context, label, false) {
                dialog.dismiss()
                onNegative?.invoke()
            })
        }
        buttons.addView(dialogButton(context, positiveLabel, true, destructive) {
            if (onPositive()) dialog.dismiss()
        })
        root.addView(buttons)
        PiperModernUi.apply(root)
        PiperAutoFont.apply(root)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.58f }
                setLayout(
                    (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.CENTER)
            }
        }
        dialog.show()
        return dialog
    }

    private fun dialogButton(
        context: Context,
        label: String,
        primary: Boolean,
        destructive: Boolean = false,
        click: () -> Unit
    ) = MaterialButton(context).apply {
        text = label
        isAllCaps = false
        minWidth = dp(context, 84)
        minimumHeight = dp(context, 44)
        cornerRadius = dp(context, 8)
        val fill = when {
            destructive -> Color.rgb(190, 52, 60)
            primary -> PiperModernUi.accentColor(context)
            else -> PiperModernUi.surfaceColor(context)
        }
        backgroundTintList = ColorStateList.valueOf(fill)
        strokeColor = ColorStateList.valueOf(
            if (primary || destructive) fill else PiperModernUi.borderColor(context)
        )
        strokeWidth = dp(context, 1)
        setTextColor(if (primary || destructive) Color.WHITE else PiperModernUi.textColor(context))
        setOnClickListener { click() }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
