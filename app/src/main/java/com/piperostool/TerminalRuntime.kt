package com.piperostool

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

object TerminalRuntime {
    const val RUNTIME_VERSION = "2.5.6-beta"
    const val RUNTIME_RELEASE_TAG = "runtime-v2.5.6-beta"
    const val SOURCE_REPOSITORY_URL =
        "https://github.com/Phi574/Piperos_termux"
    const val PACKAGE_REPOSITORY_URL =
        "https://raw.githubusercontent.com/Phi574/Piperos_termux/gh-pages"
    const val PACKAGE_REPOSITORY_SUITE = "stable"
    const val PACKAGE_REPOSITORY_COMPONENT = "main"
    const val MANIFEST_URL =
        "$SOURCE_REPOSITORY_URL/releases/download/$RUNTIME_RELEASE_TAG/runtime-manifest.json"
    const val MANIFEST_SIGNATURE_URL =
        "$SOURCE_REPOSITORY_URL/releases/download/$RUNTIME_RELEASE_TAG/runtime-manifest.sig"
    private const val VERSION_FILE_NAME = ".piperos-runtime-version"

    data class Status(
        val prefixDirectory: File,
        val homeDirectory: File,
        val shellExecutable: File?,
        val installedVersion: String?
    ) {
        val installed: Boolean
            get() = shellExecutable != null

        val updateAvailable: Boolean
            get() = installed && (
                installedVersion == null ||
                    compareVersions(installedVersion, RUNTIME_VERSION) < 0
                )
    }

    fun inspect(context: Context): Status {
        val prefix = File(context.filesDir, "usr")
        val shell = listOf(
            File(prefix, "bin/bash"),
            File(prefix, "bin/sh")
        ).firstOrNull { it.isFile && it.canExecute() }

        return Status(
            prefixDirectory = prefix,
            homeDirectory = AccountDataScope.directory(context, "terminal/home"),
            shellExecutable = shell,
            installedVersion = runCatching {
                File(prefix, VERSION_FILE_NAME)
                    .takeIf(File::isFile)
                    ?.readText()
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }.getOrNull()
        )
    }

    fun writeInstalledVersion(prefix: File, version: String) {
        File(prefix, VERSION_FILE_NAME).writeText(version.trim() + "\n")
    }

    fun manifestUrl(tag: String): String =
        "$SOURCE_REPOSITORY_URL/releases/download/$tag/runtime-manifest.json"

    fun manifestSignatureUrl(tag: String): String =
        "$SOURCE_REPOSITORY_URL/releases/download/$tag/runtime-manifest.sig"

    fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val size = maxOf(leftParts.size, rightParts.size)
        repeat(size) { index ->
            val leftPart = leftParts.getOrNull(index) ?: "0"
            val rightPart = rightParts.getOrNull(index) ?: "0"
            val comparison = when {
                leftPart.all(Char::isDigit) && rightPart.all(Char::isDigit) ->
                    leftPart.toLongOrNull().orEmptyCompare(rightPart.toLongOrNull())
                leftPart.all(Char::isDigit) -> 1
                rightPart.all(Char::isDigit) -> -1
                else -> leftPart.compareTo(rightPart, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun versionParts(version: String): List<String> =
        Regex("[0-9]+|[A-Za-z]+").findAll(version).map { it.value }.toList()

    private fun Long?.orEmptyCompare(other: Long?): Int =
        (this ?: 0L).compareTo(other ?: 0L)

    fun repairPackageConfiguration(context: Context) {
        val prefix = File(context.filesDir, "usr")
        if (!prefix.isDirectory) return
        val sourceDirectory = File(prefix, "etc/apt/sources.list.d").apply { mkdirs() }
        val source = File(sourceDirectory, "piperos.list")
        File(prefix, "etc/apt/sources.list").delete()
        sourceDirectory.listFiles()?.filter { it != source }?.forEach { it.delete() }

        val keyring = File(prefix, "etc/apt/keyrings/piperos-archive-keyring.gpg")
        keyring.parentFile?.mkdirs()
        context.resources.openRawResource(R.raw.piperos_apt_repository_public).use { input ->
            FileOutputStream(keyring).use { output -> input.copyTo(output) }
        }
        Os.chmod(keyring.absolutePath, 384)
        source.writeText(
            "deb [signed-by=${keyring.absolutePath}] $PACKAGE_REPOSITORY_URL " +
                "$PACKAGE_REPOSITORY_SUITE $PACKAGE_REPOSITORY_COMPONENT\n"
        )
        Os.chmod(source.absolutePath, 384)

        File(context.cacheDir, "apt/archives").listFiles()
            ?.filter { it.extension == "deb" }
            ?.forEach { it.delete() }
        installPrefixGuard(context, prefix)
    }

    private fun installPrefixGuard(context: Context, prefix: File) {
        val guard = File(prefix, "libexec/piperos/verify-package-prefix")
        guard.parentFile?.mkdirs()
        guard.writeText(
            """#!/data/data/${context.packageName}/files/usr/bin/sh
            |set -eu
            |while IFS= read -r archive; do
            |  [ -f "${'$'}archive" ] || continue
            |  if dpkg-deb --fsys-tarfile "${'$'}archive" 2>/dev/null | tar -tf - 2>/dev/null | grep -q '/data/data/com.termux'; then
            |    echo "PiperOS blocked an incompatible com.termux package: ${'$'}archive" >&2
            |    echo "Use packages built for ${context.packageName}." >&2
            |    exit 100
            |  fi
            |done
            |""".trimMargin()
        )
        Os.chmod(guard.absolutePath, 448)
        File(prefix, "etc/apt/apt.conf.d/99piperos-prefix-guard").apply {
            parentFile?.mkdirs()
            writeText("DPkg::Pre-Install-Pkgs { \"${guard.absolutePath}\"; };\n")
        }
    }

    fun uninstall(context: Context) {
        listOf(
            File(context.filesDir, "usr"),
            File(context.filesDir, "home"),
            File(context.filesDir, ".piperos-runtime-staging"),
            File(context.filesDir, ".piperos-runtime-backup"),
            File(context.cacheDir, "piperos-runtime-install")
        ).forEach(::deleteTreeWithoutFollowingLinks)
    }

    private fun deleteTreeWithoutFollowingLinks(file: File) {
        if (!file.exists() && !isSymbolicLink(file)) return
        val symbolicLink = isSymbolicLink(file)
        if (file.isDirectory && !symbolicLink) {
            file.listFiles()?.forEach(::deleteTreeWithoutFollowingLinks)
        }
        check(file.delete() || !file.exists()) {
            "Không thể xóa ${file.absolutePath}"
        }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    }.getOrDefault(false)
}
