package com.piperostool

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val matches: List<String>,
    val script: String,
    val enabled: Boolean
)

class BrowserExtensionStore(private val context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<BrowserExtension> {
        val source = preferences.getString(KEY_EXTENSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(source)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BrowserExtension(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            version = item.optString("version", "1.0"),
                            matches = item.optJSONArray("matches").toStringList(),
                            script = item.getString("script"),
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun import(uri: Uri): BrowserExtension {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: error("Không thể đọc tệp")
        require(bytes.size <= MAX_IMPORT_BYTES) { "Gói phần mở rộng vượt quá 2 MB" }

        val parsed = if (isZip(bytes)) parseZip(bytes) else parseJson(bytes.decodeToString())
        require(parsed.script.isNotBlank()) { "Không tìm thấy content script" }
        save(load().filterNot { it.name == parsed.name } + parsed)
        return parsed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(load().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun scriptsFor(url: String): List<String> =
        load().filter { extension ->
            extension.enabled && extension.matches.any { matchPattern(it, url) }
        }.map { it.script }

    private fun parseJson(source: String): BrowserExtension {
        val manifest = JSONObject(source)
        val embeddedScript = manifest.optString("script")
        if (embeddedScript.isNotBlank()) {
            return extensionFrom(
                manifest,
                manifest.optJSONArray("matches").toStringList().ifEmpty { listOf("<all_urls>") },
                embeddedScript
            )
        }
        error("JSON PiperOS cần trường script")
    }

    private fun parseZip(bytes: ByteArray): BrowserExtension {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val value = zip.readBytes()
                    total += value.size
                    require(total <= MAX_IMPORT_BYTES) { "Gói phần mở rộng quá lớn" }
                    entries[entry.name.removePrefix("./")] = value
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifestEntry = entries.entries.firstOrNull {
            it.key.equals("manifest.json", true) || it.key.endsWith("/manifest.json", true)
        } ?: error("Không tìm thấy manifest.json")
        val base = manifestEntry.key.substringBeforeLast("/", "")
        val manifest = JSONObject(manifestEntry.value.decodeToString())
        val contentScripts = manifest.optJSONArray("content_scripts")
            ?: error("Extension không có content_scripts")

        val matches = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        for (index in 0 until contentScripts.length()) {
            val block = contentScripts.getJSONObject(index)
            matches += block.optJSONArray("matches").toStringList()
            val jsFiles = block.optJSONArray("js").toStringList()
            jsFiles.forEach { relativePath ->
                val path = if (base.isBlank()) relativePath else "$base/$relativePath"
                val script = entries[path]?.decodeToString()
                    ?: error("Thiếu tệp script: $relativePath")
                scripts += script
            }
        }
        val combined = scripts.joinToString("\n;\n")
        require(combined.toByteArray().size <= MAX_SCRIPT_BYTES) {
            "Content script vượt quá 1 MB"
        }
        return extensionFrom(manifest, matches.ifEmpty { listOf("<all_urls>") }, combined)
    }

    private fun extensionFrom(
        manifest: JSONObject,
        matches: List<String>,
        script: String
    ) = BrowserExtension(
        id = UUID.randomUUID().toString(),
        name = manifest.optString("name", "PiperOS Extension").take(80),
        version = manifest.optString("version", "1.0").take(24),
        matches = matches,
        script = script,
        enabled = true
    )

    private fun save(items: List<BrowserExtension>) {
        val array = JSONArray()
        items.forEach { extension ->
            array.put(
                JSONObject()
                    .put("id", extension.id)
                    .put("name", extension.name)
                    .put("version", extension.version)
                    .put("matches", JSONArray(extension.matches))
                    .put("script", extension.script)
                    .put("enabled", extension.enabled)
            )
        }
        preferences.edit().putString(KEY_EXTENSIONS, array.toString()).apply()
    }

    private fun matchPattern(pattern: String, url: String): Boolean {
        if (pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
        val regexSource = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        val regex = Regex("^$regexSource$", RegexOption.IGNORE_CASE)
        return regex.matches(url)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte()

    companion object {
        private const val PREFERENCES_NAME = "PiperBrowserExtensions"
        private const val KEY_EXTENSIONS = "extensions"
        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_SCRIPT_BYTES = 1024 * 1024
    }
}
