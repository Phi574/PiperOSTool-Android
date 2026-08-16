package com.piperostool

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

object TermuxProcessLauncher {
    fun create(
        context: Context,
        executable: File,
        arguments: List<String>,
        workingDirectory: File,
        prefixDirectory: File,
        homeDirectory: File
    ): ProcessBuilder {
        return ProcessBuilder(buildCommand(executable, arguments))
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(buildEnvironment(context, prefixDirectory, homeDirectory))
            }
    }

    fun buildCommand(executable: File, arguments: List<String>): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(if (Process.is64Bit()) SYSTEM_LINKER_64 else SYSTEM_LINKER_32)
            }
            add(executable.absolutePath)
            addAll(arguments)
        }

    fun buildEnvironment(
        context: Context,
        prefixDirectory: File,
        homeDirectory: File
    ): Map<String, String> {
        val tmpDirectory = File(prefixDirectory, "tmp").apply { mkdirs() }
        return buildMap {
                    put("HOME", homeDirectory.absolutePath)
                    put("PREFIX", prefixDirectory.absolutePath)
                    put("TERMUX__PREFIX", prefixDirectory.absolutePath)
                    put("TERMUX__ROOTFS", context.filesDir.absolutePath)
                    put("TERMUX_APP__DATA_DIR", context.applicationInfo.dataDir)
                    put(
                        "TERMUX_APP__LEGACY_DATA_DIR",
                        "/data/data/${context.packageName}"
                    )
                    put("TERMUX_APP__PACKAGE_NAME", context.packageName)
                    put("ANDROID__BUILD_VERSION_SDK", Build.VERSION.SDK_INT.toString())
                    put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "force")
                    put("TMP", tmpDirectory.absolutePath)
                    put("TMPDIR", tmpDirectory.absolutePath)
                    put(
                        "PATH",
                        "${File(prefixDirectory, "bin").absolutePath}:" +
                            "/system/bin:/system/xbin:/vendor/bin"
                    )
                    put("LD_LIBRARY_PATH", File(prefixDirectory, "lib").absolutePath)
                    File(prefixDirectory, "lib/libtermux-exec-ld-preload.so")
                        .takeIf(File::isFile)
                        ?.let { put("LD_PRELOAD", it.absolutePath) }
                    put("LANG", "C.UTF-8")
                    put("LC_ALL", "C.UTF-8")
                    put("COLORTERM", "truecolor")
                    put("TERM", "xterm-256color")
                    put("SHELL", File(prefixDirectory, "bin/bash").absolutePath)
                    put("PS1", "")
                    put("PROMPT_COMMAND", "")
                }
    }

    private const val SYSTEM_LINKER_32 = "/system/bin/linker"
    private const val SYSTEM_LINKER_64 = "/system/bin/linker64"
}
