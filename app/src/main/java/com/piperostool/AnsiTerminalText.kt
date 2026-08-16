package com.piperostool

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

object AnsiTerminalText {
    private val csi = Regex("\\u001B\\[([0-9;?]*)([ -/]*)([@-~])")

    fun format(source: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        var cursor = 0
        var color: Int? = null
        var bold = false
        csi.findAll(source).forEach { match ->
            appendStyled(result, source.substring(cursor, match.range.first), color, bold)
            if (match.groupValues[3] == "m") {
                val values = match.groupValues[1]
                    .split(';')
                    .map { it.toIntOrNull() ?: 0 }
                var index = 0
                while (index < values.size) {
                    when (val code = values[index]) {
                        0 -> { color = null; bold = false }
                        1 -> bold = true
                        22 -> bold = false
                        39 -> color = null
                        in 30..37 -> color = palette(code - 30, false)
                        in 90..97 -> color = palette(code - 90, true)
                        38 -> when (values.getOrNull(index + 1)) {
                            5 -> {
                                color = indexedColor(values.getOrNull(index + 2) ?: 7)
                                index += 2
                            }
                            2 -> {
                                color = Color.rgb(
                                    values.getOrNull(index + 2)?.coerceIn(0, 255) ?: 255,
                                    values.getOrNull(index + 3)?.coerceIn(0, 255) ?: 255,
                                    values.getOrNull(index + 4)?.coerceIn(0, 255) ?: 255
                                )
                                index += 4
                            }
                        }
                    }
                    index++
                }
            }
            cursor = match.range.last + 1
        }
        appendStyled(result, source.substring(cursor), color, bold)
        return result
    }

    private fun appendStyled(
        target: SpannableStringBuilder,
        value: String,
        color: Int?,
        bold: Boolean
    ) {
        if (value.isEmpty()) return
        val start = target.length
        target.append(value)
        color?.let {
            target.setSpan(
                ForegroundColorSpan(it), start, target.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (bold) {
            target.setSpan(
                StyleSpan(Typeface.BOLD), start, target.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun palette(index: Int, bright: Boolean): Int {
        val normal = intArrayOf(
            0xFF111827.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt(),
            0xFFEAB308.toInt(), 0xFF60A5FA.toInt(), 0xFFC084FC.toInt(),
            0xFF22D3EE.toInt(), 0xFFE5E7EB.toInt()
        )
        val vivid = intArrayOf(
            0xFF6B7280.toInt(), 0xFFFB7185.toInt(), 0xFF86EFAC.toInt(),
            0xFFFDE047.toInt(), 0xFF93C5FD.toInt(), 0xFFD8B4FE.toInt(),
            0xFF67E8F9.toInt(), 0xFFFFFFFF.toInt()
        )
        return (if (bright) vivid else normal)[index.coerceIn(0, 7)]
    }

    private fun indexedColor(index: Int): Int = when {
        index < 8 -> palette(index, false)
        index < 16 -> palette(index - 8, true)
        index < 232 -> {
            val value = index - 16
            val r = value / 36
            val g = value % 36 / 6
            val b = value % 6
            fun channel(component: Int) = if (component == 0) 0 else 55 + component * 40
            Color.rgb(channel(r), channel(g), channel(b))
        }
        else -> Color.rgb(8 + (index - 232) * 10, 8 + (index - 232) * 10, 8 + (index - 232) * 10)
    }
}
