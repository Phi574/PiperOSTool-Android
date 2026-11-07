package com.piperostool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AuthScreenRule(
    val id: String,
    val file: String,
    val enabled: Boolean,
    val requiresNetwork: Boolean,
    val requiresSignedIn: Boolean
)

data class AuthConfig(
    val signedInOfflinePolicy: String,
    val signedOutOfflinePolicy: String,
    val unavailableTitle: String,
    val unavailableMessage: String,
    val unavailableIcon: String,
    val unavailableButton: String,
    val screens: List<AuthScreenRule>
)

object AuthConfigRepository {

    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Phi574/PiperOSTool-Android/main/config/auth.json"
    private const val SCREEN_BASE_URL =
        "https://raw.githubusercontent.com/Phi574/PiperOSTool-Android/main/config/auth/"
    private const val PREFS_NAME = "auth_json_cache_v1"
    private const val MANIFEST_CACHE_KEY = "manifest"

    private val allowedFiles = mapOf(
        "welcome" to "welcome.json",
        "login" to "login_screen.json",
        "signup" to "activity_signup.json",
        "lock_screen" to "activity_lock_screen.json",
        "forgot_password" to "activity_forgotpassword.json",
        "phone_auth" to "activity_phone_auth.json"
    )

    suspend fun loadManifest(context: Context): AuthConfig = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (hasValidatedInternet(appContext)) {
            try {
                val source = fetchText("$MANIFEST_URL?ts=${System.currentTimeMillis()}")
                val config = parseManifest(source)
                prefs.edit().putString(MANIFEST_CACHE_KEY, source).apply()
                return@withContext config
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the last known configuration, then the bundled fallback.
            }
        }

        val cached = prefs.getString(MANIFEST_CACHE_KEY, null)
        if (!cached.isNullOrBlank()) {
            try {
                return@withContext parseManifest(cached)
            } catch (_: Exception) {
                // Ignore a damaged cache and use the bundled fallback.
            }
        }

        val bundled = readAsset(appContext, "auth/auth.json")
        if (!bundled.isNullOrBlank()) return@withContext parseManifest(bundled)

        throw IOException("Không có cấu hình auth.json hợp lệ.")
    }

    suspend fun loadScreen(
        context: Context,
        screenId: String,
        allowOfflineDefinition: Boolean = false,
        knownManifest: AuthConfig? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val config = knownManifest ?: loadManifest(appContext)
        val rule = config.screens.firstOrNull { it.id == screenId }
            ?: throw IOException("Không tìm thấy màn hình $screenId trong auth.json.")

        if (rule.requiresNetwork && !allowOfflineDefinition && !hasValidatedInternet(appContext)) {
            throw IOException("AUTH_NETWORK_REQUIRED")
        }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = "screen_$screenId"
        if (hasValidatedInternet(appContext)) {
            try {
                val source = fetchText(
                    "$SCREEN_BASE_URL${rule.file}?ts=${System.currentTimeMillis()}"
                )
                val parsed = parseScreen(source, screenId)
                prefs.edit().putString(cacheKey, source).apply()
                return@withContext parsed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fall back to the last known screen or the bundled JSON.
            }
        }

        val cached = prefs.getString(cacheKey, null)
        if (!cached.isNullOrBlank()) {
            try {
                return@withContext parseScreen(cached, screenId)
            } catch (_: Exception) {
                // Ignore invalid cached screen JSON.
            }
        }

        val bundled = readAsset(appContext, "auth/${rule.file}")
        if (!bundled.isNullOrBlank()) return@withContext parseScreen(bundled, screenId)

        throw IOException("Không có JSON dự phòng cho màn hình $screenId.")
    }

    fun parseManifest(source: String): AuthConfig {
        val root = JSONObject(source)
        require(root.optInt("schemaVersion", -1) == 1) {
            "Phiên bản auth.json không được hỗ trợ."
        }

        val offline = root.optJSONObject("offlinePolicy")
            ?: throw IOException("Thiếu offlinePolicy trong auth.json.")
        val unavailable = root.optJSONObject("unavailable")
            ?: throw IOException("Thiếu unavailable trong auth.json.")
        val screens = parseScreens(root.optJSONArray("screens"))

        require(offline.optString("signedIn") == "use_cached_session") {
            "Chính sách offline cho tài khoản đăng nhập không hợp lệ."
        }
        require(offline.optString("signedOut") == "show_unavailable") {
            "Chính sách offline cho khách chưa đăng nhập không hợp lệ."
        }
        require(screens.any { it.id == "welcome" }) {
            "auth.json phải có màn hình welcome."
        }

        return AuthConfig(
            signedInOfflinePolicy = offline.optString("signedIn"),
            signedOutOfflinePolicy = offline.optString("signedOut"),
            unavailableTitle = unavailable.optString("title", "Tính năng hiện không khả dụng"),
            unavailableMessage = unavailable.optString(
                "message",
                "Tính năng hiện tại không khả dụng.\nVui lòng kiểm tra mạng hoặc thử lại sau."
            ),
            unavailableIcon = unavailable.optString("icon", "error_bug"),
            unavailableButton = unavailable.optString("button", "Đóng"),
            screens = screens
        )
    }

    private fun parseScreens(array: JSONArray?): List<AuthScreenRule> {
        require(array != null) { "Thiếu danh sách screens trong auth.json." }
        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IOException("Phần tử screens[$index] không hợp lệ.")
                val id = item.optString("id")
                val allowedFile = allowedFiles[id]
                    ?: throw IOException("ID màn hình không được hỗ trợ: $id")
                require(item.optString("file") == allowedFile) {
                    "Tên file không hợp lệ cho màn hình $id."
                }
                require(seen.add(id)) { "ID màn hình bị lặp: $id" }

                add(
                    AuthScreenRule(
                        id = id,
                        file = allowedFile,
                        enabled = item.optBoolean("enabled", false),
                        requiresNetwork = item.optBoolean("requiresNetwork", true),
                        requiresSignedIn = item.optBoolean("requiresSignedIn", false)
                    )
                )
            }
        }
    }

    fun parseScreen(source: String, expectedId: String): JSONObject {
        val screen = JSONObject(source)
        require(screen.optInt("schemaVersion", -1) == 1) {
            "Phiên bản JSON màn hình không được hỗ trợ."
        }
        require(screen.optString("screenId") == expectedId) {
            "screenId trong JSON không khớp với màn hình được yêu cầu."
        }
        require(screen.optJSONObject("layout") != null) {
            "JSON màn hình chưa có cây layout."
        }
        return screen
    }

    fun hasInternet(context: Context): Boolean = hasValidatedInternet(context.applicationContext)

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun readAsset(context: Context, path: String): String? = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: IOException) {
        null
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
