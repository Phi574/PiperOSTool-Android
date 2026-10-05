package com.piperostool

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

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
    fun showSearchSelect(
        context: Context,
        title: String,
        choices: List<PiperSheetChoice>,
        onSelect: (String) -> Unit
    ): android.app.Dialog {
        val dialog = android.app.Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun choiceBackground() = sheetItemBackground(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 16))
        }
        root.addView(titleView(context, title))
        val search = EditText(context).apply {
            hint = "Tìm trong ${choices.size} lựa chọn"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7))
            background = choiceBackground()
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(context, 48)).apply {
            bottomMargin = dp(context, 10)
        })
        val count = TextView(context).apply {
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(context))
            setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 8))
        }
        root.addView(count)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render(query: String) {
            list.removeAllViews()
            val filtered = choices.filter { it.label.contains(query, ignoreCase = true) }
            count.text = "${filtered.size} mục"
            filtered.forEach { choice ->
                list.addView(TextView(context).apply {
                    text = if (choice.selected) "✓  ${choice.label}" else choice.label
                    textSize = 15f
                    setTextColor(PiperModernUi.textColor(context))
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 52)
                    setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
                    background = choiceBackground()
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 7) }
                    setOnClickListener { dialog.dismiss(); onSelect(choice.key) }
                })
            }
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(MaterialButton(context).apply {
            text = "Đóng"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(context, 48)).apply { topMargin = dp(context, 10) })
        PiperModernUi.apply(root)
        PiperAutoFont.watch(root)
        val panel = MaterialCardView(android.view.ContextThemeWrapper(context, R.style.Theme_PiperOSTool)).apply {
            radius = dp(context, 24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            panel.setCardBackgroundColor(PiperModernUi.surfaceColor(context))
            panel.strokeColor = PiperModernUi.borderColor(context)
            panel.strokeWidth = dp(context, 1)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.42f }
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setLayout((context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    (context.resources.displayMetrics.heightPixels * 0.68f).toInt())
                setGravity(Gravity.CENTER)
            }
        }
        dialog.show()
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.post { (list.parent as? ScrollView)?.scrollTo(0, 0) }
        return dialog
    }

    fun show(context: Context, title: String, actions: List<PiperSheetAction>) {
        val dialog = BottomSheetDialog(context)
        val content = compactSheetRoot(context, actions.size)
        content.addView(titleView(context, title))
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.forEach { action ->
            list.addView(actionRow(context, action) {
                dialog.dismiss()
                action.onClick()
            })
        }
        content.addView(
            ScrollView(context).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(list)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        PiperModernUi.apply(content)
        setSheetContent(dialog, content)
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
        val root = compactSheetRoot(context, options.size)
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
        setSheetContent(dialog, root)
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
        setSheetContent(dialog, root)
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

    private fun setSheetContent(dialog: BottomSheetDialog, content: View) {
        dialog.setContentView(content)
    }

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
            minimumHeight = dp(context, 56)
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
                scaleType = ImageView.ScaleType.CENTER_INSIDE
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
