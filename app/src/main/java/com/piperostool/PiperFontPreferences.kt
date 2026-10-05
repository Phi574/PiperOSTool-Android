package com.piperostool

import android.content.Context
import android.graphics.Typeface

data class PiperFontChoice(
    val key: String,
    val name: String,
    val removable: Boolean
)

object PiperFontPreferences {
    const val SYSTEM = "system"
    const val BILINGUAL = "bilingual"
    const val INTER = "inter"

    private const val PREFS = "PiperPrefs"
    private const val KEY_SELECTED = "app_font"
    fun selectedKey(context: Context): String {
        val saved = prefs(context).getString(KEY_SELECTED, BILINGUAL).orEmpty()
        return if (saved in builtInKeys) saved else BILINGUAL
    }

    fun select(context: Context, key: String): Boolean {
        if (key !in builtInKeys) return false
        prefs(context).edit().putString(KEY_SELECTED, key).apply()
        PiperAutoFont.clearTypefaceCache()
        return true
    }

    fun choices(context: Context): List<PiperFontChoice> = listOf(
        PiperFontChoice(SYSTEM, context.getString(R.string.settings_font_system), false),
        PiperFontChoice(BILINGUAL, context.getString(R.string.settings_font_bilingual), false),
        PiperFontChoice(INTER, context.getString(R.string.settings_font_inter), false)
    )

    fun selectedName(context: Context): String =
        choices(context).firstOrNull { it.key == selectedKey(context) }?.name
            ?: context.getString(R.string.settings_font_bilingual)

    private val builtInKeys = setOf(SYSTEM, BILINGUAL, INTER)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
