package com.piperostool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WindowConfigItem(
    val id: String,
    val enabled: Boolean,
    val title: String,
    val summary: String,
    val icon: String,
    val action: String
)

data class WindowConfig(
    val homeWindow: List<WindowConfigItem>,
    val securityMenu: List<WindowConfigItem>,
    val rawJson: String
)

object WindowConfigRepository {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/Phi574/PiperOSTool-Android/main/config/window.json"

    suspend fun load(context: Context): WindowConfig = withContext(Dispatchers.IO) {
        if (!hasValidatedInternet(context)) {
            throw IOException("Không có kết nối Internet.")
        }

        val requestUrl = URL("$CONFIG_URL?ts=${System.currentTimeMillis()}")
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("Không tải được cấu hình (HTTP $statusCode).")
            }

            val json = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            parse(json)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(
        source: String,
        language: String = Locale.getDefault().language
    ): WindowConfig {
        val root = JSONObject(source)
        require(root.optInt("schemaVersion", -1) == 1) {
            "Phiên bản cấu hình không được hỗ trợ."
        }

        return WindowConfig(
            homeWindow = parseItems(root.optJSONArray("homeWindow"), language),
            securityMenu = parseItems(root.optJSONArray("securityMenu"), language),
            rawJson = source
        )
    }

    private fun parseItems(
        array: JSONArray?,
        language: String
    ): List<WindowConfigItem> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue

                add(
                    WindowConfigItem(
                        id = item.optString("id"),
                        enabled = item.optBoolean("enabled", false),
                        title = localized(item.opt("title"), language),
                        summary = localized(item.opt("summary"), language),
                        icon = item.optString("icon"),
                        action = item.optString("action")
                    )
                )
            }
        }
    }

    private fun localized(value: Any?, language: String): String {
        if (value !is JSONObject) return value?.toString().orEmpty()

        return value.optString(language)
            .takeIf { it.isNotBlank() }
            ?: value.optString("vi").takeIf { it.isNotBlank() }
            ?: value.optString("en")
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
