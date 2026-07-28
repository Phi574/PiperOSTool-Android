package com.piperostool

import android.content.Context
import java.io.File

object TerminalRuntime {
    data class Status(
        val prefixDirectory: File,
        val homeDirectory: File,
        val shellExecutable: File?
    ) {
        val installed: Boolean
            get() = shellExecutable != null
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
            shellExecutable = shell
        )
    }
}
