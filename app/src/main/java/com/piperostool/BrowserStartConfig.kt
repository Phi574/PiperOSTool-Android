package com.piperostool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class BrowserStartConfig(
    val configVersion: Long,
    val title: String,
    val subtitle: String,
    val searchHint: String,
    val privacyNote: String,
    val components: List<BrowserStartComponent>,
    val browserOptions: BrowserOptionsConfig
)

internal data class BrowserOptionsConfig(
    val title: String,
    val items: List<BrowserOptionItem>
)

internal data class BrowserOptionItem(
    val action: String,
    val title: String,
    val subtitle: String,
    val summaryKey: String,
    val icon: String,
    val visible: Boolean,
    val enabled: Boolean
)

internal sealed interface BrowserStartComponent {
    data class ShortcutGrid(
        val columns: Int,
        val items: List<BrowserStartShortcut>
    ) : BrowserStartComponent

    data class AnnouncementBanner(
        val title: String,
        val message: String,
        val buttonText: String,
        val url: String?
    ) : BrowserStartComponent
}

internal data class BrowserStartShortcut(
    val title: String,
    val badge: String,
    val url: String
)

internal object BrowserStartConfigRepository {
    private const val REMOTE_URL =
        "https://phi574.github.io/PiperOSTool-Android/config/browser-start.json"
    private const val PREFS = "browser_start_remote_config"
    private const val KEY_CONFIG = "last_valid_config"
    private const val KEY_CONFIG_VERSION = "last_config_version"
    private const val KEY_LAST_ATTEMPT = "last_fetch_attempt"
    private const val MAX_BYTES = 64 * 1024
    private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    fun load(context: Context): BrowserStartConfig? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = preferences.getString(KEY_CONFIG, null)?.let(BrowserStartConfigParser::parse)
        if (cached != null) return cached
        return runCatching {
            context.assets.open("browser-start.json").bufferedReader(StandardCharsets.UTF_8).use {
                BrowserStartConfigParser.parse(it.readText())
            }
        }.getOrNull()
    }

    suspend fun refresh(context: Context): BrowserStartConfig? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(KEY_LAST_ATTEMPT, 0L) < REFRESH_INTERVAL_MS) {
            return@withContext null
        }
        preferences.edit().putLong(KEY_LAST_ATTEMPT, now).apply()

        val connection = runCatching {
            URL(REMOTE_URL + "?v=" + (now / REFRESH_INTERVAL_MS)).openConnection() as HttpURLConnection
        }.getOrNull() ?: return@withContext null
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "PiperOS-Tool-Android")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType !in setOf("application/json", "text/json")) return@withContext null

            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) return@withContext null
                    output.write(buffer, 0, count)
                }
            }
            val json = output.toString(StandardCharsets.UTF_8.name())
            val config = BrowserStartConfigParser.parse(json) ?: return@withContext null
            if (config.configVersion < preferences.getLong(KEY_CONFIG_VERSION, 0L)) {
                return@withContext null
            }
            preferences.edit()
                .putString(KEY_CONFIG, json)
                .putLong(KEY_CONFIG_VERSION, config.configVersion)
                .apply()
            config
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

internal object BrowserStartConfigParser {
    fun parse(raw: String): BrowserStartConfig? {
        if (raw.isBlank() || raw.length > 64 * 1024) return null
        return try {
            val root = JSONObject(raw)
            if (root.optInt("schemaVersion", -1) != 2) return null
            val version = root.optLong("configVersion", 0L)
            if (version < 1L) return null
            val screen = root.optJSONObject("screen") ?: return null
            if (screen.optString("type") != "column") return null
            val title = clean(screen.optString("title"), 64)
            val subtitle = clean(screen.optString("subtitle"), 120)
            val searchHint = clean(screen.optString("searchHint"), 80)
            val privacyNote = clean(screen.optString("privacyNote"), 180)
            if (title.isBlank() || subtitle.isBlank() || searchHint.isBlank()) return null
            val children = screen.optJSONArray("children") ?: JSONArray()
            if (children.length() > 12) return null
            val components = ArrayList<BrowserStartComponent>()
            for (index in 0 until children.length()) {
                val item = children.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "shortcutGrid" -> parseShortcutGrid(item)?.let(components::add)
                    "announcementBanner" -> parseBanner(item)?.let(components::add)
                }
            }
            val browserOptions = parseBrowserOptions(root.optJSONObject("browserOptions"))
                ?: return null
            BrowserStartConfig(
                version,
                title,
                subtitle,
                searchHint,
                privacyNote,
                components,
                browserOptions
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun safeHttpsUrl(raw: String): String? {
        if (raw.length > 2_048 || raw.any { it.isISOControl() }) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (uri.host.length > 253 || uri.port !in -1..65535) return null
        return uri.toASCIIString().takeIf { it.length <= 2_048 }
    }

    private fun parseShortcutGrid(value: JSONObject): BrowserStartComponent.ShortcutGrid? {
        if (!value.optBoolean("visible", true)) return null
        val source = value.optJSONArray("items") ?: JSONArray()
        if (source.length() > 6) return null
        val items = buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val title = clean(item.optString("title"), 32)
                val badge = clean(item.optString("badge"), 3)
                val url = safeHttpsUrl(item.optString("url")) ?: continue
                if (title.isNotBlank()) add(BrowserStartShortcut(title, badge, url))
            }
        }
        if (items.isEmpty()) return null
        return BrowserStartComponent.ShortcutGrid(
            columns = value.optInt("columns", 2).coerceIn(1, 2),
            items = items
        )
    }

    private fun parseBanner(value: JSONObject): BrowserStartComponent.AnnouncementBanner? {
        if (!value.optBoolean("visible", true)) return null
        val title = clean(value.optString("title"), 64)
        val message = clean(value.optString("message"), 240)
        if (title.isBlank() || message.isBlank()) return null
        val buttonText = clean(value.optString("buttonText"), 32)
        val url = value.optString("url").takeIf(String::isNotBlank)?.let(::safeHttpsUrl)
        return BrowserStartComponent.AnnouncementBanner(
            title = title,
            message = message,
            buttonText = buttonText.takeIf { url != null }.orEmpty(),
            url = url
        )
    }

    private fun parseBrowserOptions(value: JSONObject?): BrowserOptionsConfig? {
        value ?: return null
        val title = clean(value.optString("title"), 64)
        val source = value.optJSONArray("items") ?: return null
        if (title.isBlank() || source.length() > 20) return null
        val actions = setOf(
            "privacy_report", "developer_tools", "browser_theme", "search_engine",
            "site_data", "desktop_mode", "history", "downloads", "extensions",
            "user_agent", "notifications", "about"
        )
        val icons = setOf(
            "privacy", "code", "search", "lock", "browser", "reload", "download",
            "add", "notification"
        )
        val summaryKeys = setOf(
            "none", "privacy_report", "developer_tools", "browser_theme",
            "search_engine", "site_data", "extensions", "user_agent"
        )
        val seenActions = mutableSetOf<String>()
        val items = buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val action = item.optString("action")
                val itemTitle = clean(item.optString("title"), 48)
                val icon = item.optString("icon")
                if (action !in actions || action in seenActions || itemTitle.isBlank() || icon !in icons) {
                    continue
                }
                seenActions += action
                val summaryKey = item.optString("summaryKey", "none")
                    .takeIf { it in summaryKeys } ?: "none"
                add(
                    BrowserOptionItem(
                        action = action,
                        title = itemTitle,
                        subtitle = clean(item.optString("subtitle"), 120),
                        summaryKey = summaryKey,
                        icon = icon,
                        visible = item.optBoolean("visible", true),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
        if (items.isEmpty()) return null
        return BrowserOptionsConfig(title, items)
    }

    private fun clean(value: String, maxLength: Int): String =
        value.filterNot { it.isISOControl() && it != '\n' }.trim().take(maxLength)
}
