package com.piperostool

internal data class WhatsNewRelease(
    val version: String,
    val details: String
) {
    val channel: AppUpdateRepository.Channel get() = AppUpdateRepository.channel(version)
    val parsedVersion: AppUpdateRepository.Version get() = AppUpdateRepository.version(version)!!
}

internal object WhatsNewCatalog {
    private val versionAtStart = Regex("(?i)^\\s*v?(\\d+\\.\\d+\\.\\d+(?:[._-][a-z0-9]+)?)")

    fun parse(markdown: String): List<WhatsNewRelease> = markdown
        .split(Regex("(?m)^##\\s+"))
        .drop(1)
        .mapNotNull { section ->
            val lines = section.lines()
            val heading = lines.firstOrNull()?.trim().orEmpty()
            val version = versionAtStart.find(heading)?.groupValues?.get(1)
                ?.takeIf { AppUpdateRepository.version(it) != null }
                ?: return@mapNotNull null
            val details = lines.drop(1).joinToString("\n") { line ->
                line.trim()
                    .removePrefix("- ")
                    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                    .replace(Regex("`([^`]*)`"), "$1")
            }.trim()
            WhatsNewRelease(version, details)
        }
        .sortedWith { left, right -> right.parsedVersion.compareTo(left.parsedVersion) }

    fun currentRelease(releases: List<WhatsNewRelease>, installedVersion: String): WhatsNewRelease? {
        val installed = AppUpdateRepository.version(installedVersion) ?: return null
        return releases.firstOrNull { it.parsedVersion == installed }
    }

    fun mergeLocalAndRemote(local: List<WhatsNewRelease>, remote: List<AppRelease>): List<WhatsNewRelease> {
        val merged = local.associateBy { it.parsedVersion }.toMutableMap()
        remote.forEach { release ->
            val parsed = AppUpdateRepository.version(release.tag) ?: return@forEach
            if (parsed in merged) return@forEach
            val version = release.tag.removePrefix("v").removePrefix("V")
            val details = release.description.lineSequence().joinToString("\n") { line ->
                line.trim()
                    .removePrefix("- ")
                    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                    .replace(Regex("`([^`]*)`"), "$1")
            }.trim()
            merged[parsed] = WhatsNewRelease(version, details)
        }
        return merged.values.sortedWith { left, right -> right.parsedVersion.compareTo(left.parsedVersion) }
    }
}
