package com.piperostool

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

data class PiperSheetAction(
    val label: String,
    val subtitle: String? = null,
    val icon: Int,
    val onClick: () -> Unit
)

data class PiperSheetChoice(
    val key: String,
    val label: String,
    val selected: Boolean = false,
    val removable: Boolean = false
)

object PiperActionSheet {
    fun show(context: Context, title: String, actions: List<PiperSheetAction>) {
        val dialog = BottomSheetDialog(context)
        val content = sheetRoot(context)
        content.addView(titleView(context, title))
        actions.forEach { action ->
            content.addView(actionRow(context, action) {
                dialog.dismiss()
                action.onClick()
            })
        }
        PiperModernUi.apply(content)
        dialog.setContentView(content)
        styleBottomSheet(dialog, context)
        dialog.show()
    }

    fun showMultiSelect(
        context: Context,
        title: String,
        options: List<String>,
        selected: Set<Int>,
        onApply: (Set<Int>) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val pending = selected.toMutableSet()
        val root = sheetRoot(context)
        root.addView(titleView(context, title))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, label ->
            list.addView(CheckBox(context).apply {
                text = label
                isChecked = index in pending
                minimumHeight = dp(context, 54)
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) pending += index else pending -= index
                }
            })
        }
        root.addView(
            ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        val actions = LinearLayout(context).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(MaterialButton(context).apply {
            text = context.getString(android.R.string.cancel)
            setOnClickListener { dialog.dismiss() }
        })
        val applyButton = MaterialButton(context).apply {
            text = context.getString(android.R.string.ok)
            setOnClickListener {
                dialog.dismiss()
                onApply(pending)
            }
        }
        actions.addView(applyButton)
        root.addView(actions)
        PiperModernUi.apply(root)
        applyButton.backgroundTintList = ColorStateList.valueOf(PiperModernUi.accentColor(context))
        applyButton.setTextColor(Color.WHITE)
        dialog.setContentView(root)
        styleBottomSheet(dialog, context)
        dialog.show()
    }

    fun showSingleSelect(
        context: Context,
        title: String,
        choices: List<PiperSheetChoice>,
        addLabel: String? = null,
        onSelect: (String) -> Unit,
        onRemove: (String) -> Unit,
        onAdd: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val root = compactSheetRoot(context, choices.size)
        root.addView(titleView(context, title))

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        choices.forEach { choice ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 60)
                setPadding(dp(context, 10), 0, dp(context, 6), 0)
                background = sheetItemBackground(context)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 8) }
            }
            val radio = RadioButton(context).apply {
                text = choice.label
                isChecked = choice.selected
                gravity = Gravity.CENTER_VERTICAL
                textSize = 15f
                setTextColor(PiperModernUi.textColor(context))
                buttonTintList = choiceTint(context)
                setPadding(dp(context, 8), 0, dp(context, 8), 0)
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(choice.key)
                }
            }
            row.addView(
                radio,
                LinearLayout.LayoutParams(0, dp(context, 60), 1f)
            )
            if (choice.removable) {
                row.addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_browser_close)
                    imageTintList = ColorStateList.valueOf(Color.rgb(220, 78, 85))
                    background = null
                    contentDescription = context.getString(R.string.settings_font_remove)
                    setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13))
                    setOnClickListener {
                        dialog.dismiss()
                        onRemove(choice.key)
                    }
                }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            }
            row.setOnClickListener { radio.performClick() }
            list.addView(row)
        }

        root.addView(
            ScrollView(context).apply {
                isFillViewport = false
                addView(list)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        addLabel?.let { label ->
            root.addView(MaterialButton(context).apply {
                text = label
                setIconResource(R.drawable.ic_browser_add)
                iconTint = ColorStateList.valueOf(PiperModernUi.accentColor(context))
                setTextColor(PiperModernUi.textColor(context))
                backgroundTintList = ColorStateList.valueOf(PiperModernUi.surfaceColor(context))
                strokeColor = ColorStateList.valueOf(PiperModernUi.borderColor(context))
                strokeWidth = dp(context, 1)
                cornerRadius = dp(context, 8)
                setOnClickListener {
                    dialog.dismiss()
                    onAdd()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 54)
            ).apply { topMargin = dp(context, 4) })
        }

        PiperModernUi.apply(root)
        PiperAutoFont.watch(root)
        dialog.setContentView(root)
        styleBottomSheet(dialog, context)
        dialog.show()
    }

    private fun sheetRoot(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 22))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
        PiperAutoFont.watch(this)
    }

    private fun compactSheetRoot(context: Context, choiceCount: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 22))
        val desired = dp(context, 104 + choiceCount * 68 + 62)
        val maximum = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            desired.coerceAtMost(maximum)
        )
    }

    private fun sheetItemBackground(context: Context) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(PiperModernUi.surfaceColor(context))
        cornerRadius = dp(context, 8).toFloat()
        setStroke(dp(context, 1), PiperModernUi.borderColor(context))
    }

    private fun choiceTint(context: Context) = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(
            PiperModernUi.accentColor(context),
            PiperModernUi.secondaryTextColor(context)
        )
    )

    private fun styleBottomSheet(dialog: BottomSheetDialog, context: Context) {
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(PiperModernUi.surfaceColor(context))
                    cornerRadii = floatArrayOf(
                        dp(context, 8).toFloat(), dp(context, 8).toFloat(),
                        dp(context, 8).toFloat(), dp(context, 8).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                    setStroke(dp(context, 1), PiperModernUi.borderColor(context))
                }
        }
    }

    private fun titleView(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 16))
    }

    private fun actionRow(context: Context, action: PiperSheetAction, click: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(PiperModernUi.surfaceColor(context))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), PiperModernUi.borderColor(context))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
            addView(ImageView(context).apply {
                setImageResource(action.icon)
                imageTintList = ColorStateList.valueOf(PiperModernUi.accentColor(context))
                setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
            }, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), 0, 0, 0)
                addView(TextView(context).apply {
                    text = action.label
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                })
                action.subtitle?.let { detail ->
                    addView(TextView(context).apply {
                        text = detail
                        textSize = 11f
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { click() }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
