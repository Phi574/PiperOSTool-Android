package com.piperostool

import android.util.Xml
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object TerminalRuntimeCatalog {
    private const val RELEASES_API =
        "https://api.github.com/repos/Phi574/Piperos_termux/releases?per_page=30"
    private const val RELEASES_ATOM =
        "https://github.com/Phi574/Piperos_termux/releases.atom"
    private val runtimeTagPattern = Regex("runtime-v[0-9][0-9A-Za-z._-]*")

    data class Release(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val assetCount: Int
    )

    /**
     * Use GitHub's public Releases feed first. The REST API is frequently rate limited
     * for mobile users, which previously prevented the terminal installer from even
     * showing the runtime choices. The REST endpoint remains a fallback for networks
     * that block Atom feeds.
     */
    fun fetch(): List<Release> {
        val atomResult = runCatching(::fetchAtom)
        atomResult.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val apiResult = runCatching(::fetchApi)
        apiResult.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        if (atomResult.isSuccess) return atomResult.getOrDefault(emptyList())

        val atomError = atomResult.exceptionOrNull()?.message.orEmpty()
        val apiError = apiResult.exceptionOrNull()?.message.orEmpty()
        throw IOException(
            "Runtime feed failed ($atomError); GitHub API fallback failed ($apiError)",
            apiResult.exceptionOrNull()
        )
    }

    private fun fetchApi(): List<Release> {
        val connection = openConnection(RELEASES_API).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw responseError(connection, "GitHub API")
            }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                repeat(releases.length()) { index ->
                    val release = releases.getJSONObject(index)
                    val tag = release.optString("tag_name")
                    if (
                        release.optBoolean("draft") ||
                        !runtimeTagPattern.matches(tag)
                    ) return@repeat

                    val assets = release.optJSONArray("assets") ?: return@repeat
                    val names = buildSet {
                        repeat(assets.length()) { assetIndex ->
                            add(assets.getJSONObject(assetIndex).optString("name"))
                        }
                    }
                    if (!hasRuntimeAssets(names)) return@repeat

                    add(
                        Release(
                            version = tag.removePrefix("runtime-v"),
                            tag = tag,
                            publishedAt = release.optString("published_at"),
                            assetCount = names.size
                        )
                    )
                }
            }.sortedWith(newestFirst())
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchAtom(): List<Release> {
        val connection = openConnection(RELEASES_ATOM).apply {
            setRequestProperty("Accept", "application/atom+xml, application/xml;q=0.9")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw responseError(connection, "GitHub Releases feed")
            }
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            }
            val releases = mutableListOf<Release>()
            connection.inputStream.buffered().use { input ->
                parser.setInput(input, Charsets.UTF_8.name())
                var inEntry = false
                var tagFromLink: String? = null
                var title = ""
                var updated = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "entry" -> {
                                inEntry = true
                                tagFromLink = null
                                title = ""
                                updated = ""
                            }
                            "link" -> if (
                                inEntry && parser.getAttributeValue(null, "rel") == "alternate"
                            ) {
                                val href = parser.getAttributeValue(null, "href")
                                tagFromLink = href
                                    ?.substringBefore('?')
                                    ?.trimEnd('/')
                                    ?.substringAfterLast('/')
                            }
                            "title" -> if (inEntry) title = parser.nextText()
                            "updated" -> if (inEntry) updated = parser.nextText()
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "entry" && inEntry) {
                            val tag = tagFromLink
                                ?.takeIf(runtimeTagPattern::matches)
                                ?: runtimeTagPattern.find(title)?.value
                            if (tag != null) {
                                releases += Release(
                                    version = tag.removePrefix("runtime-v"),
                                    tag = tag,
                                    publishedAt = updated,
                                    // Assets are downloaded directly by tag and checked
                                    // against the signed manifest before extraction.
                                    assetCount = 0
                                )
                            }
                            inEntry = false
                        }
                    }
                    event = parser.next()
                }
            }
            releases.distinctBy(Release::tag).sortedWith(newestFirst())
        } finally {
            connection.disconnect()
        }
    }

    private fun hasRuntimeAssets(names: Set<String>): Boolean =
        "runtime-manifest.json" in names &&
            "runtime-manifest.sig" in names &&
            names.any { it.endsWith(".zip") }

    private fun newestFirst(): Comparator<Release> = Comparator { left, right ->
        -TerminalRuntime.compareVersions(left.version, right.version)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PiperOS-Android")
        }

    private fun responseError(connection: HttpURLConnection, source: String): IOException {
        val code = connection.responseCode
        val details = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?.replace(Regex("\\s+"), " ")
                ?.take(180)
                .orEmpty()
        }.getOrDefault("")
        return IOException("$source HTTP $code${details.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
    }
}
