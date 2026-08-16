package com.piperostool

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object TerminalRuntimeCatalog {
    private const val RELEASES_API =
        "https://api.github.com/repos/Phi574/Piperos_termux/releases?per_page=30"

    data class Release(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val assetCount: Int
    )

    fun fetch(): List<Release> {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PiperOS-Android")
        }
        return try {
            require(connection.responseCode in 200..299) {
                "GitHub HTTP ${connection.responseCode}"
            }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                repeat(releases.length()) { index ->
                    val release = releases.getJSONObject(index)
                    val tag = release.optString("tag_name")
                    val version = tag.removePrefix("runtime-v")
                    if (
                        release.optBoolean("draft") ||
                        !tag.matches(Regex("runtime-v[0-9][0-9A-Za-z._-]*"))
                    ) return@repeat

                    val assets = release.optJSONArray("assets") ?: return@repeat
                    val names = buildSet {
                        repeat(assets.length()) { assetIndex ->
                            add(assets.getJSONObject(assetIndex).optString("name"))
                        }
                    }
                    if (
                        "runtime-manifest.json" !in names ||
                        "runtime-manifest.sig" !in names ||
                        names.none { it.endsWith(".zip") }
                    ) return@repeat

                    add(
                        Release(
                            version = version,
                            tag = tag,
                            publishedAt = release.optString("published_at"),
                            assetCount = names.size
                        )
                    )
                }
            }.sortedWith { left, right ->
                -TerminalRuntime.compareVersions(left.version, right.version)
            }
        } finally {
            connection.disconnect()
        }
    }
}
