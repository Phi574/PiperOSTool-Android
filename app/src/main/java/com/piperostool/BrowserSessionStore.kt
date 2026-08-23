package com.piperostool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BrowserSavedTab(
    val id: Long,
    val title: String,
    val url: String
)

data class BrowserHistoryEntry(
    val title: String,
    val url: String,
    val visitedAt: Long
)

data class BrowserUserAgent(
    val id: String,
    val label: String,
    val value: String?
)

enum class BrowserThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPreference(value: String?): BrowserThemeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

data class BrowserSearchEngine(
    val id: String,
    val label: String,
    val queryUrl: String
) {
    fun searchUrl(encodedQuery: String): String = queryUrl.replace("%s", encodedQuery)
}

class BrowserSessionStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveTabs(tabs: List<BrowserSavedTab>, activeTabId: Long) {
        val json = JSONArray()
        tabs.forEach { tab ->
            json.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("title", tab.title)
                    .put("url", tab.url)
            )
        }
        preferences.edit()
            .putString(KEY_TABS, json.toString())
            .putLong(KEY_ACTIVE_TAB, activeTabId)
            .apply()
    }

    fun loadTabs(): List<BrowserSavedTab> {
        val source = preferences.getString(KEY_TABS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(source)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        BrowserSavedTab(
                            id = item.optLong("id", System.nanoTime()),
                            title = item.optString("title", DEFAULT_TAB_TITLE),
                            url = item.optString("url", HOME_URL)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadActiveTabId(): Long = preferences.getLong(KEY_ACTIVE_TAB, -1L)

    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val entries = loadHistory().toMutableList()
        entries.removeAll { it.url == url }
        entries.add(
            0,
            BrowserHistoryEntry(
                title = title.ifBlank { url },
                url = url,
                visitedAt = System.currentTimeMillis()
            )
        )
        saveHistory(entries.take(MAX_HISTORY_ITEMS))
    }

    fun loadHistory(): List<BrowserHistoryEntry> {
        val source = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(source)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        BrowserHistoryEntry(
                            title = item.optString("title"),
                            url = item.optString("url"),
                            visitedAt = item.optLong("visitedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearHistory() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    fun isDesktopMode(): Boolean =
        preferences.getBoolean(KEY_DESKTOP_MODE, false)

    fun setDesktopMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
    }

    fun selectedUserAgentId(): String =
        preferences.getString(KEY_USER_AGENT, USER_AGENT_DEFAULT) ?: USER_AGENT_DEFAULT

    fun setSelectedUserAgent(id: String) {
        preferences.edit().putString(KEY_USER_AGENT, id).apply()
    }

    fun preferredVpnPackage(): String? =
        preferences.getString(KEY_VPN_PACKAGE, null)

    fun setPreferredVpnPackage(packageName: String?) {
        preferences.edit().apply {
            if (packageName == null) remove(KEY_VPN_PACKAGE)
            else putString(KEY_VPN_PACKAGE, packageName)
        }.apply()
    }

    fun browserThemeMode(): BrowserThemeMode = BrowserThemeMode.fromPreference(
        preferences.getString(KEY_BROWSER_THEME, BrowserThemeMode.SYSTEM.preferenceValue)
    )

    fun setBrowserThemeMode(mode: BrowserThemeMode) {
        preferences.edit().putString(KEY_BROWSER_THEME, mode.preferenceValue).apply()
    }

    fun selectedSearchEngine(): BrowserSearchEngine {
        val selectedId = preferences.getString(KEY_SEARCH_ENGINE, SEARCH_ENGINE_GOOGLE)
        return searchEngines().firstOrNull { it.id == selectedId } ?: searchEngines().first()
    }

    fun setSelectedSearchEngine(id: String) {
        val safeId = searchEngines().firstOrNull { it.id == id }?.id ?: SEARCH_ENGINE_GOOGLE
        preferences.edit().putString(KEY_SEARCH_ENGINE, safeId).apply()
    }

    private fun saveHistory(entries: List<BrowserHistoryEntry>) {
        val json = JSONArray()
        entries.forEach { entry ->
            json.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("url", entry.url)
                    .put("visitedAt", entry.visitedAt)
            )
        }
        preferences.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    companion object {
        const val HOME_URL = "piperos://home"
        const val DEFAULT_TAB_TITLE = "Tab mới"
        const val USER_AGENT_DEFAULT = "default"

        private const val PREFERENCES_NAME = "PiperBrowserSession"
        private const val KEY_TABS = "tabs"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_HISTORY = "history"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_VPN_PACKAGE = "vpn_package"
        private const val KEY_BROWSER_THEME = "browser_theme"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val MAX_HISTORY_ITEMS = 250
        private const val SEARCH_ENGINE_GOOGLE = "google"

        fun searchEngines(): List<BrowserSearchEngine> = listOf(
            BrowserSearchEngine(SEARCH_ENGINE_GOOGLE, "Google", "https://www.google.com/search?q=%s"),
            BrowserSearchEngine("bing", "Microsoft Bing", "https://www.bing.com/search?q=%s"),
            BrowserSearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
            BrowserSearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
            BrowserSearchEngine("yahoo", "Yahoo", "https://search.yahoo.com/search?p=%s"),
            BrowserSearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
            BrowserSearchEngine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s"),
            BrowserSearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s"),
            BrowserSearchEngine("yandex", "Yandex", "https://yandex.com/search/?text=%s"),
            BrowserSearchEngine("baidu", "Baidu", "https://www.baidu.com/s?wd=%s")
        )

        fun userAgents(): List<BrowserUserAgent> = listOf(
            BrowserUserAgent(USER_AGENT_DEFAULT, "Mặc định của thiết bị", null),
            BrowserUserAgent(
                "android",
                "Android Chrome",
                "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
            ),
            BrowserUserAgent(
                "iphone",
                "iPhone Safari",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 " +
                    "Mobile/15E148 Safari/604.1"
            ),
            BrowserUserAgent(
                "ipad",
                "iPad Safari",
                "Mozilla/5.0 (iPad; CPU OS 18_5 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 " +
                    "Mobile/15E148 Safari/604.1"
            ),
            BrowserUserAgent(
                "mac",
                "macOS Safari",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15"
            ),
            BrowserUserAgent(
                "windows",
                "Windows Chrome",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
            )
        )
    }
}
