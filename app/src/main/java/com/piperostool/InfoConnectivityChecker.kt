package com.piperostool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class InfoHealthState {
    CHECKING,
    HEALTHY,
    SLOW,
    WARNING,
    UNAVAILABLE
}

internal data class InfoHealthItem(
    val id: String,
    val title: String,
    val detail: String,
    val state: InfoHealthState,
    val latencyMs: Long? = null
)

/** Read-only, on-demand connectivity checks for the Info page. */
internal object InfoConnectivityChecker {
    private const val GREEN_LATENCY_MS = 1_200L
    private const val REQUEST_TIMEOUT_MS = 4_000
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun check(context: Context, callback: (List<InfoHealthItem>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val internet = checkInternetBlocking(appContext)
            if (internet.state == InfoHealthState.UNAVAILABLE) {
                val offline = listOf(
                    internet,
                    unavailable("github", "GitHub", "Không có kết nối Internet"),
                    unavailable("firebase", "Firebase Auth", "Không có kết nối Internet")
                )
                mainHandler.post { callback(offline) }
                return@execute
            }

            val githubFuture = executor.submit<InfoHealthItem> { checkGitHubBlocking() }
            val firebaseFuture = executor.submit<InfoHealthItem> { checkFirebaseAuthBlocking() }
            val github = runCatching { githubFuture.get() }
                .getOrElse { unavailable("github", "GitHub", "Không thể kiểm tra lúc này") }
            val firebase = runCatching { firebaseFuture.get() }
                .getOrElse { unavailable("firebase", "Firebase Auth", "Không thể kiểm tra lúc này") }
            val all = listOf(internet, github, firebase)
            mainHandler.post { callback(all) }
        }
    }

    suspend fun checkInternetNow(context: Context): InfoHealthItem = withContext(Dispatchers.IO) {
        checkInternetBlocking(context.applicationContext)
    }

    suspend fun checkGitHubNow(): InfoHealthItem = withContext(Dispatchers.IO) {
        checkGitHubBlocking()
    }

    suspend fun checkFirebaseAuthNow(): InfoHealthItem = withContext(Dispatchers.IO) {
        checkFirebaseAuthBlocking()
    }

    private fun checkInternetBlocking(context: Context): InfoHealthItem {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!hasInternet || !validated) {
            return unavailable("internet", "Internet", "Mạng chưa xác thực được Internet")
        }
        return probe(
            id = "internet",
            title = "Internet",
            url = "https://connectivitycheck.gstatic.com/generate_204",
            expectedStatus = { it == 204 },
            failureDetail = "Không kết nối được tới điểm kiểm tra Internet"
        )
    }

    private fun checkGitHubBlocking(): InfoHealthItem = probe(
        id = "github",
        title = "GitHub",
        url = "https://api.github.com/repos/Phi574/PiperOSTool-Android",
        expectedStatus = { it in 200..299 },
        failureDetail = "GitHub API không phản hồi bình thường"
    )

    private fun checkFirebaseAuthBlocking(): InfoHealthItem {
        val appCheckToken = runCatching {
            Tasks.await(
                FirebaseAppCheck.getInstance().getAppCheckToken(false),
                REQUEST_TIMEOUT_MS.toLong(),
                TimeUnit.MILLISECONDS
            ).token
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: return unavailable(
                "firebase",
                "Firebase Auth",
                "Không lấy được App Check token từ Play Integrity"
            )
        val apiKey = runCatching { FirebaseApp.getInstance().options.apiKey }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return unavailable("firebase", "Firebase Auth", "Thiếu cấu hình Firebase trong ứng dụng")
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:createAuthUri?key=$encodedKey"
        val body = JSONObject()
            .put("identifier", "health-check@piperostool.invalid")
            .put("continueUri", "https://piperostool.invalid")
            .toString()
        return probe(
            id = "firebase",
            title = "Firebase Auth",
            url = url,
            method = "POST",
            body = body,
            headers = mapOf("X-Firebase-AppCheck" to appCheckToken),
            expectedStatus = { it in 200..299 },
            warningStatus = { it in 400..499 },
            warningDetail = { code ->
                "Máy chủ đã phản hồi nhưng từ chối yêu cầu kiểm tra (HTTP $code); chưa thể kết luận trạng thái đăng nhập."
            },
            failureDetail = "Firebase Authentication không phản hồi bình thường"
        )
    }

    private fun probe(
        id: String,
        title: String,
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        expectedStatus: (Int) -> Boolean,
        warningStatus: (Int) -> Boolean = { false },
        warningDetail: (Int) -> String = { "Máy chủ đã phản hồi yêu cầu kiểm tra không hợp lệ (HTTP $it)" },
        failureDetail: String
    ): InfoHealthItem {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PiperOS-Tool-Info-Health")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val statusCode = connection.responseCode
            val latency = (System.nanoTime() - started) / 1_000_000L
            if (warningStatus(statusCode)) {
                InfoHealthItem(id, title, warningDetail(statusCode), InfoHealthState.WARNING, latency)
            } else if (!expectedStatus(statusCode)) {
                unavailable(id, title, "$failureDetail (HTTP $statusCode)", latency)
            } else if (latency > GREEN_LATENCY_MS) {
                InfoHealthItem(id, title, "Kết nối được nhưng phản hồi chậm", InfoHealthState.SLOW, latency)
            } else {
                InfoHealthItem(id, title, "Đang hoạt động · ${latency} ms", InfoHealthState.HEALTHY, latency)
            }
        } catch (_: Exception) {
            unavailable(id, title, failureDetail)
        } finally {
            connection?.disconnect()
        }
    }

    private fun unavailable(
        id: String,
        title: String,
        detail: String,
        latency: Long? = null
    ) = InfoHealthItem(id, title, detail, InfoHealthState.UNAVAILABLE, latency)
}
