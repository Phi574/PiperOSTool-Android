package com.piperostool

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

class TerminalKeyboardController(
    private val context: Context,
    private val input: EditText,
    private val panel: View,
    private val rowsContainer: LinearLayout,
    private val functionKeys: LinearLayout,
    private val modeButton: TextView,
    private val clipboardButton: TextView,
    private val onSubmit: () -> Unit,
    private val onInterrupt: () -> Unit,
    private val onHistory: (Int) -> Unit,
    private val onPageScroll: (Int) -> Unit,
    private val onRawInput: (String) -> Unit
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val clipboardHistory = mutableListOf<String>()
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipboardManager.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.let(::rememberClipboard)
    }

    private var systemKeyboard = preferences.getBoolean(KEY_SYSTEM_KEYBOARD, false)
    private var shifted = false
    private var symbols = false
    private var controlModifier = false
    private var altModifier = false

    init {
        loadClipboardHistory()
        modeButton.setOnClickListener { setSystemKeyboard(!systemKeyboard, true) }
        clipboardButton.setOnClickListener { showClipboardHistory() }
        applyConfiguration()
        buildFunctionKeys()
        buildKeyboard()
        setSystemKeyboard(systemKeyboard, false)
    }

    fun start() {
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        clipboardListener.onPrimaryClipChanged()
    }

    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    fun applyConfiguration() {
        panel.layoutParams = panel.layoutParams.apply {
            height = dp(
                if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    180
                } else {
                    250
                }
            )
        }
    }

    private fun buildFunctionKeys() {
        functionKeys.removeAllViews()
        listOf(
            "ESC" to { onRawInput("\u001B") },
            "TAB" to { insertText("\t") },
            "PASTE" to { pasteLatest() },
            "CLIPS" to { showClipboardHistory() },
            "UP" to { onHistory(-1) },
            "DOWN" to { onHistory(1) },
            "HOME" to { input.setSelection(0) },
            "END" to { input.setSelection(input.text?.length ?: 0) },
            "PGUP" to { onPageScroll(-1) },
            "PGDN" to { onPageScroll(1) },
            "CTRL+C" to { onInterrupt() }
        ).forEach { (label, action) ->
            functionKeys.addView(createKey(label, 64, action))
        }
    }

    private fun buildKeyboard() {
        rowsContainer.removeAllViews()
        val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
        rows.forEach { labels ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            labels.forEach { label ->
                row.addView(
                    createKey(label, 0) { handleKey(label) },
                    LinearLayout.LayoutParams(0, MATCH_PARENT, keyWeight(label)).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                )
            }
            rowsContainer.addView(
                row,
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            )
        }
    }

    private fun handleKey(label: String) {
        when (label) {
            "SHIFT" -> {
                shifted = !shifted
                buildKeyboard()
            }
            "123" -> {
                symbols = true
                shifted = false
                buildKeyboard()
            }
            "ABC" -> {
                symbols = false
                shifted = false
                buildKeyboard()
            }
            "CTRL" -> {
                controlModifier = !controlModifier
                altModifier = false
                buildKeyboard()
            }
            "ALT" -> {
                altModifier = !altModifier
                controlModifier = false
                buildKeyboard()
            }
            "SPACE" -> insertText(" ")
            "BACK" -> backspace()
            "LEFT" -> moveCursor(-1)
            "RIGHT" -> moveCursor(1)
            "ENTER" -> onSubmit()
            else -> insertModifiedText(if (shifted) label.uppercase() else label)
        }
    }

    private fun insertModifiedText(value: String) {
        val refreshKeyboard = shifted || controlModifier || altModifier
        when {
            controlModifier && value.length == 1 && value[0].isLetter() -> {
                val controlCode = (value[0].lowercaseChar().code - 'a'.code + 1)
                    .coerceIn(1, 26)
                onRawInput(controlCode.toChar().toString())
                controlModifier = false
            }
            altModifier -> {
                onRawInput("\u001B$value")
                altModifier = false
            }
            else -> insertText(value)
        }
        if (shifted) shifted = false
        if (refreshKeyboard) buildKeyboard()
    }

    private fun insertText(value: String) {
        val editable = input.text ?: return
        val start = min(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
        val end = max(input.selectionStart, input.selectionEnd).coerceAtLeast(start)
        editable.replace(start, end, value)
        input.setSelection(start + value.length)
        input.requestFocus()
        if (!systemKeyboard) hideSystemKeyboard()
    }

    private fun backspace() {
        val editable = input.text ?: return
        val start = min(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
        val end = max(input.selectionStart, input.selectionEnd).coerceAtLeast(start)
        when {
            end > start -> editable.delete(start, end)
            start > 0 -> editable.delete(start - 1, start)
        }
    }

    private fun moveCursor(direction: Int) {
        val target = (input.selectionStart + direction).coerceIn(0, input.text?.length ?: 0)
        input.setSelection(target)
    }

    private fun setSystemKeyboard(enabled: Boolean, show: Boolean) {
        systemKeyboard = enabled
        preferences.edit().putBoolean(KEY_SYSTEM_KEYBOARD, enabled).apply()
        input.showSoftInputOnFocus = enabled
        panel.visibility = if (enabled) View.GONE else View.VISIBLE
        modeButton.text = if (enabled) "PIPER" else "SYS"
        modeButton.setTextColor(if (enabled) 0xFF8AD6FF.toInt() else 0xFFD9FCE5.toInt())
        if (enabled && show) {
            input.requestFocus()
            input.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            hideSystemKeyboard()
        }
    }

    private fun hideSystemKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun pasteLatest() {
        val current = clipboardManager.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: clipboardHistory.firstOrNull()
        if (current == null) {
            showClipboardHistory()
        } else {
            rememberClipboard(current)
            insertText(current)
        }
    }

    private fun showClipboardHistory() {
        if (clipboardHistory.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.terminal_clipboard)
                .setMessage(R.string.terminal_clipboard_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = clipboardHistory.map { value ->
            value.replace('\n', ' ').take(CLIPBOARD_PREVIEW_LENGTH)
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.terminal_clipboard)
            .setItems(labels) { _, index -> insertText(clipboardHistory[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.terminal_clipboard_clear) { _, _ ->
                clipboardHistory.clear()
                saveClipboardHistory()
            }
            .show()
    }

    private fun rememberClipboard(value: String) {
        val normalized = value.take(MAX_CLIPBOARD_ITEM_LENGTH)
        if (normalized.isBlank()) return
        clipboardHistory.remove(normalized)
        clipboardHistory.add(0, normalized)
        while (clipboardHistory.size > MAX_CLIPBOARD_ITEMS) {
            clipboardHistory.removeAt(clipboardHistory.lastIndex)
        }
        saveClipboardHistory()
    }

    private fun loadClipboardHistory() {
        val encoded = preferences.getString(KEY_CLIPBOARD_HISTORY, null) ?: return
        runCatching {
            val array = JSONArray(encoded)
            repeat(array.length()) { index ->
                array.optString(index).takeIf(String::isNotBlank)?.let(clipboardHistory::add)
            }
        }
    }

    private fun saveClipboardHistory() {
        preferences.edit()
            .putString(KEY_CLIPBOARD_HISTORY, JSONArray(clipboardHistory).toString())
            .apply()
    }

    private fun createKey(label: String, widthDp: Int, action: () -> Unit): TextView =
        TextView(context).apply {
            text = displayLabel(label)
            gravity = Gravity.CENTER
            setTextColor(keyColor(label))
            textSize = if (label.length > 4) 10f else 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(context, R.drawable.bg_terminal_key)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            contentDescription = label
            if (widthDp > 0) {
                layoutParams = LinearLayout.LayoutParams(dp(widthDp), MATCH_PARENT).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            }
        }

    private fun displayLabel(label: String): String = when {
        label.length == 1 && label[0].isLetter() && shifted -> label.uppercase()
        else -> label
    }

    private fun keyColor(label: String): Int = when {
        label == "CTRL" && controlModifier -> 0xFF8AD6FF.toInt()
        label == "ALT" && altModifier -> 0xFFFFC46B.toInt()
        label == "SHIFT" && shifted -> 0xFF8DFFB0.toInt()
        else -> Color.WHITE
    }

    private fun keyWeight(label: String): Float = when (label) {
        "SPACE" -> 4f
        "SHIFT", "BACK", "ENTER" -> 1.7f
        "CTRL", "ALT", "123", "ABC" -> 1.4f
        else -> 1f
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "piperos_terminal_keyboard"
        private const val KEY_SYSTEM_KEYBOARD = "system_keyboard"
        private const val KEY_CLIPBOARD_HISTORY = "clipboard_history"
        private const val MAX_CLIPBOARD_ITEMS = 20
        private const val MAX_CLIPBOARD_ITEM_LENGTH = 4_000
        private const val CLIPBOARD_PREVIEW_LENGTH = 72
        private const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT

        private val LETTER_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="),
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "BACK"),
            listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/"),
            listOf("123", "CTRL", "ALT", "SPACE", "LEFT", "RIGHT", "ENTER")
        )
        private val SYMBOL_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="),
            listOf("!", "@", "#", "\$", "%", "^", "&", "*", "(", ")"),
            listOf("`", "~", "_", "+", "[", "]", "{", "}", "\\", "|"),
            listOf("ABC", "<", ">", "?", ":", ";", "\"", "'", "BACK"),
            listOf("ABC", "CTRL", "ALT", "SPACE", "LEFT", "RIGHT", "ENTER")
        )
    }
}
