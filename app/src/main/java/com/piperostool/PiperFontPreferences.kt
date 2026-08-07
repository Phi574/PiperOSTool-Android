package com.piperostool

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PiperFontChoice(
    val key: String,
    val name: String,
    val removable: Boolean
)

data class PiperCustomFont(
    val id: String,
    val name: String,
    val fileName: String
) {
    val key: String get() = "$CUSTOM_PREFIX$id"

    companion object {
        const val CUSTOM_PREFIX = "custom:"
    }
}

object PiperFontPreferences {
    const val SYSTEM = "system"
    const val BILINGUAL = "bilingual"
    const val INTER = "inter"

    private const val PREFS = "PiperPrefs"
    private const val KEY_SELECTED = "app_font"
    private const val KEY_CUSTOM_FONTS = "custom_fonts"
    private const val MAX_FONT_BYTES = 30L * 1024L * 1024L

    fun selectedKey(context: Context): String {
        val saved = prefs(context).getString(KEY_SELECTED, BILINGUAL).orEmpty()
        return if (isValidKey(context, saved)) saved else BILINGUAL
    }

    fun select(context: Context, key: String): Boolean {
        if (!isValidKey(context, key)) return false
        prefs(context).edit().putString(KEY_SELECTED, key).apply()
        PiperAutoFont.clearTypefaceCache()
        return true
    }

    fun choices(context: Context): List<PiperFontChoice> = listOf(
        PiperFontChoice(SYSTEM, context.getString(R.string.settings_font_system), false),
        PiperFontChoice(BILINGUAL, context.getString(R.string.settings_font_bilingual), false),
        PiperFontChoice(INTER, context.getString(R.string.settings_font_inter), false)
    ) + customFonts(context).map { PiperFontChoice(it.key, it.name, true) }

    fun selectedName(context: Context): String =
        choices(context).firstOrNull { it.key == selectedKey(context) }?.name
            ?: context.getString(R.string.settings_font_bilingual)

    fun customFontFile(context: Context, key: String): File? {
        val id = key.removePrefix(PiperCustomFont.CUSTOM_PREFIX)
        val entry = customFonts(context).firstOrNull { it.id == id } ?: return null
        return File(fontDirectory(context), entry.fileName).takeIf(File::isFile)
    }

    fun importFont(context: Context, uri: Uri): Result<PiperFontChoice> = runCatching {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "Custom font"
        val extension = displayName.substringAfterLast('.', "ttf").lowercase()
            .takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val id = UUID.randomUUID().toString()
        val output = File(fontDirectory(context), "$id.$extension")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open font file" }
                output.outputStream().use { stream ->
                    val copied = input.copyTo(stream)
                    require(copied in 1..MAX_FONT_BYTES) {
                        "Font file is empty or larger than 30 MB"
                    }
                }
            }
            Typeface.createFromFile(output)
        } catch (error: Throwable) {
            output.delete()
            throw IllegalArgumentException("Unsupported or damaged font file", error)
        }
        val font = PiperCustomFont(
            id = id,
            name = displayName.substringBeforeLast('.').ifBlank { displayName },
            fileName = output.name
        )
        saveCustomFonts(context, customFonts(context) + font)
        select(context, font.key)
        PiperFontChoice(font.key, font.name, true)
    }

    fun delete(context: Context, key: String): Boolean {
        if (!key.startsWith(PiperCustomFont.CUSTOM_PREFIX)) return false
        val wasSelected = prefs(context).getString(KEY_SELECTED, BILINGUAL) == key
        val fonts = customFonts(context).toMutableList()
        val id = key.removePrefix(PiperCustomFont.CUSTOM_PREFIX)
        val removed = fonts.firstOrNull { it.id == id } ?: return false
        fonts.remove(removed)
        File(fontDirectory(context), removed.fileName).delete()
        saveCustomFonts(context, fonts)
        if (wasSelected) select(context, BILINGUAL)
        PiperAutoFont.clearTypefaceCache()
        return true
    }

    private fun isValidKey(context: Context, key: String): Boolean =
        key in setOf(SYSTEM, BILINGUAL, INTER) || customFontFile(context, key) != null

    private fun customFonts(context: Context): List<PiperCustomFont> {
        val raw = prefs(context).getString(KEY_CUSTOM_FONTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val font = PiperCustomFont(
                        item.getString("id"),
                        item.getString("name"),
                        item.getString("file")
                    )
                    if (File(fontDirectory(context), font.fileName).isFile) add(font)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustomFonts(context: Context, fonts: List<PiperCustomFont>) {
        val array = JSONArray()
        fonts.forEach { font ->
            array.put(JSONObject().apply {
                put("id", font.id)
                put("name", font.name)
                put("file", font.fileName)
            })
        }
        prefs(context).edit().putString(KEY_CUSTOM_FONTS, array.toString()).apply()
    }

    private fun fontDirectory(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
