package com.piperostool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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

/** Read-only, on-demand connectivity and Google Play services checks for the Info page. */
internal object InfoConnectivityChecker {
    private const val GREEN_LATENCY_MS = 1_200L
    private const val REQUEST_TIMEOUT_MS = 4_000
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun check(context: Context, callback: (List<InfoHealthItem>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val internet = checkInternetBlocking(appContext)
            val googlePlay = checkGooglePlayServicesBlocking(appContext)
            if (internet.state == InfoHealthState.UNAVAILABLE) {
                val offline = listOf(
                    internet,
                    unavailable(
                        "github",
                        appContext.getString(R.string.info_health_github_title),
                        appContext.getString(R.string.info_health_no_internet)
                    ),
                    unavailable(
                        "firebase",
                        appContext.getString(R.string.info_health_firebase_title),
                        appContext.getString(R.string.info_health_no_internet)
                    ),
                    googlePlay
                )
                mainHandler.post { callback(offline) }
                return@execute
            }

            val githubFuture = executor.submit<InfoHealthItem> { checkGitHubBlocking(appContext) }
            val firebaseFuture = executor.submit<InfoHealthItem> { checkFirebaseAuthBlocking(appContext) }
            val github = runCatching { githubFuture.get() }
                .getOrElse {
                    unavailable(
                        "github",
                        appContext.getString(R.string.info_health_github_title),
                        appContext.getString(R.string.info_health_cannot_check)
                    )
                }
            val firebase = runCatching { firebaseFuture.get() }
                .getOrElse {
                    unavailable(
                        "firebase",
                        appContext.getString(R.string.info_health_firebase_title),
                        appContext.getString(R.string.info_health_cannot_check)
                    )
                }
            mainHandler.post { callback(listOf(internet, github, firebase, googlePlay)) }
        }
    }

    suspend fun checkInternetNow(context: Context): InfoHealthItem = withContext(Dispatchers.IO) {
        checkInternetBlocking(context.applicationContext)
    }

    suspend fun checkGitHubNow(context: Context): InfoHealthItem = withContext(Dispatchers.IO) {
        checkGitHubBlocking(context.applicationContext)
    }

    suspend fun checkFirebaseAuthNow(context: Context): InfoHealthItem = withContext(Dispatchers.IO) {
        checkFirebaseAuthBlocking(context.applicationContext)
    }

    private fun checkInternetBlocking(context: Context): InfoHealthItem {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!hasInternet || !validated) {
            return unavailable(
                "internet",
                context.getString(R.string.info_health_internet_title),
                context.getString(R.string.info_health_internet_unvalidated)
            )
        }
        return probe(
            context = context,
            id = "internet",
            title = context.getString(R.string.info_health_internet_title),
            url = "https://connectivitycheck.gstatic.com/generate_204",
            expectedStatus = { it == 204 },
            failureDetail = context.getString(R.string.info_health_internet_unreachable)
        )
    }

    private fun checkGitHubBlocking(context: Context): InfoHealthItem = probe(
        context = context,
        id = "github",
        title = context.getString(R.string.info_health_github_title),
        url = "https://api.github.com/repos/Phi574/PiperOSTool-Android",
        expectedStatus = { it in 200..299 },
        failureDetail = context.getString(R.string.info_health_github_failure)
    )

    private fun checkFirebaseAuthBlocking(context: Context): InfoHealthItem {
        val appCheckToken = runCatching {
            Tasks.await(
                FirebaseAppCheck.getInstance().getAppCheckToken(false),
                REQUEST_TIMEOUT_MS.toLong(),
                TimeUnit.MILLISECONDS
            ).token
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: return unavailable(
                "firebase",
                context.getString(R.string.info_health_firebase_title),
                context.getString(R.string.info_health_firebase_app_check_missing)
            )
        val apiKey = runCatching { FirebaseApp.getInstance().options.apiKey }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return unavailable(
                "firebase",
                context.getString(R.string.info_health_firebase_title),
                context.getString(R.string.info_health_firebase_config_missing)
            )
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:createAuthUri?key=$encodedKey"
        val body = JSONObject()
            .put("identifier", "health-check@piperostool.invalid")
            .put("continueUri", "https://piperostool.invalid")
            .toString()
        return probe(
            context = context,
            id = "firebase",
            title = context.getString(R.string.info_health_firebase_title),
            url = url,
            method = "POST",
            body = body,
            headers = mapOf("X-Firebase-AppCheck" to appCheckToken),
            expectedStatus = { it in 200..299 },
            warningStatus = { it in 400..499 },
            warningDetail = { code ->
                context.getString(R.string.info_health_firebase_rejected, code)
            },
            failureDetail = context.getString(R.string.info_health_firebase_failure)
        )
    }

    private fun checkGooglePlayServicesBlocking(context: Context): InfoHealthItem {
        val title = context.getString(R.string.info_health_google_play_title)
        val code = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrElse {
            return unavailable(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_other, -1)
            )
        }
        return when (code) {
            ConnectionResult.SUCCESS -> InfoHealthItem(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_ok),
                InfoHealthState.HEALTHY
            )
            ConnectionResult.SERVICE_MISSING -> unavailable(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_missing)
            )
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> InfoHealthItem(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_update),
                InfoHealthState.WARNING
            )
            ConnectionResult.SERVICE_DISABLED -> unavailable(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_disabled)
            )
            ConnectionResult.SERVICE_UPDATING -> InfoHealthItem(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_updating),
                InfoHealthState.SLOW
            )
            else -> unavailable(
                "google_play",
                title,
                context.getString(R.string.info_health_google_play_other, code)
            )
        }
    }

    private fun probe(
        context: Context,
        id: String,
        title: String,
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        expectedStatus: (Int) -> Boolean,
        warningStatus: (Int) -> Boolean = { false },
        warningDetail: (Int) -> String = {
            context.getString(R.string.info_health_server_rejected, it)
        },
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
                unavailable(
                    id,
                    title,
                    context.getString(R.string.info_health_server_http, failureDetail, statusCode),
                    latency
                )
            } else if (latency > GREEN_LATENCY_MS) {
                InfoHealthItem(
                    id,
                    title,
                    context.getString(R.string.info_health_slow),
                    InfoHealthState.SLOW,
                    latency
                )
            } else {
                InfoHealthItem(
                    id,
                    title,
                    context.getString(R.string.info_health_online, latency),
                    InfoHealthState.HEALTHY,
                    latency
                )
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