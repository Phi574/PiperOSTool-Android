package com.piperostool

import android.content.Context

data class PiperFontChoice(
    val key: String,
    val name: String,
    val removable: Boolean
)

/** System font is the default; users can still choose an included font. */
object PiperFontPreferences {
    const val SYSTEM = "system"
    const val BILINGUAL = "bilingual" // Kept only so old preference data remains readable.
    const val INTER = "inter" // Kept only so old preference data remains readable.

    private const val PREFS = "PiperPrefs"
    private const val KEY_SELECTED = "app_font"

    fun initialize(context: Context) {
        // Installations without an explicit choice use the device font by default.
        val saved = prefs(context).getString(KEY_SELECTED, null)
        if (saved !in builtInKeys) prefs(context).edit().putString(KEY_SELECTED, SYSTEM).apply()
    }

    fun selectedKey(context: Context): String =
        prefs(context).getString(KEY_SELECTED, SYSTEM)?.takeIf { it in builtInKeys } ?: SYSTEM

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
            ?: context.getString(R.string.settings_font_system)

    private val builtInKeys = setOf(SYSTEM, BILINGUAL, INTER)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
