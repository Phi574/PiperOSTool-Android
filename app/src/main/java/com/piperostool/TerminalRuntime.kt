package com.piperostool

import android.content.Context
import java.io.File

object TerminalRuntime {
    const val RUNTIME_VERSION = "2.5.0-beta.1"
    const val RUNTIME_RELEASE_TAG = "runtime-v2.5.0-beta.1"
    const val SOURCE_REPOSITORY_URL =
        "https://github.com/Phi574/Piperos_termux"
    const val PACKAGE_REPOSITORY_URL =
        "https://phi574.github.io/Piperos_termux"
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
            get() = installed && installedVersion != RUNTIME_VERSION
    }

    fun inspect(context: Context): Status {
        val prefix = File(context.filesDir, "usr")
        val shell = listOf(
            File(prefix, "bin/bash"),
            File(prefix, "bin/sh")
        ).firstOrNull { it.isFile && it.canExecute() }

        return Status(
            prefixDirectory = prefix,
            homeDirectory = if (shell != null) {
                File(context.filesDir, "home")
            } else {
                File(context.filesDir, "terminal/home")
            },
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
}
