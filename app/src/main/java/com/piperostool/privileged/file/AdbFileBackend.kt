package com.piperostool.privileged.file

import android.os.ParcelFileDescriptor
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperAppActionPolicy
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperPathPolicy
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.adb.AdbShellSession
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONObject

internal class AdbFileBackend(
    private val session: AdbShellSession
) : PrivilegedFileBackend {
    private val transferWorker = Executors.newCachedThreadPool()
    override val privilege = PiperPrivilege.SHELL

    fun isConnected(): Boolean = session.isConnected()

    fun transactSystemService(serviceName: String, transactionCode: Int, data: ByteArray, flags: Int): JSONObject =
        session.transactSystemService(serviceName, transactionCode, data, flags)

    fun newProcess(
        command: String,
        workingDirectory: String?,
        stdin: String,
        environment: Map<String, String>,
        timeoutMs: Long
    ): JSONObject = session.newProcess(command, workingDirectory, stdin, environment, timeoutMs)

    fun runAppAction(action: String, packageName: String, activityName: String): Pair<Boolean, String> {
        val command = PiperAppActionPolicy.command(action, packageName, activityName)
        val result = session.execute(command)
        val failedOutput = result.output.contains("error", ignoreCase = true) ||
            result.output.contains("exception", ignoreCase = true)
        val expectedResult = when (action) {
            PiperAppActionPolicy.UNINSTALL_USER_APP -> result.output.contains("Success", ignoreCase = true)
            PiperAppActionPolicy.DISABLE_USER_APP -> result.output.contains("new state: disabled-user", ignoreCase = true)
            PiperAppActionPolicy.ENABLE_USER_APP -> result.output.contains("new state: enabled", ignoreCase = true)
            else -> true
        }
        val success = result.exitCode == 0 && !failedOutput && expectedResult
        val message = if (success) {
            result.output.ifBlank { "Hoàn tất" }
        } else {
            PiperAppActionPolicy.failureMessage(action, packageName, result.output)
                .ifBlank { "Lệnh thất bại (exit ${result.exitCode})" }
        }
        return success to message
    }

    override fun capabilities() = PiperCapabilities(
        privilege = PiperPrivilege.SHELL,
        canAccessAndroidData = true,
        canAccessAndroidObb = true,
        canReadSystemFiles = true,
        canWriteSystemFiles = false,
        canUsePackageManager = true,
        canUseAppOps = true,
        canChmod = true,
        canChown = false,
        canExecutePrivilegedCommands = true,
        canManageProcesses = true
    )

    override fun list(path: String, showHidden: Boolean): Sequence<PiperFileEntry> {
        val canonical = PiperPathPolicy.canonical(path)
        return session.listDirectory(canonical, showHidden).asSequence().map(::parseEntry)
    }

    override fun stat(path: String): PiperFileEntry? {
        val canonical = PiperPathPolicy.canonical(path)
        val result = session.fileOperation("stat", canonical)
        if (!result.optBoolean("success")) return null
        return result.optJSONObject("entry")?.let(::parseEntry)
    }

    private fun parseEntry(json: JSONObject) = PiperFileEntry(
        name = json.optString("name"),
        path = json.optString("path"),
        directory = json.optBoolean("directory"),
        size = json.optLong("size"),
        modifiedAt = json.optLong("modified"),
        mode = json.optString("mode"),
        uid = json.optInt("uid", -1),
        gid = json.optInt("gid", -1),
        symlinkTarget = json.optString("link").takeUnless { it.isBlank() || it == "null" },
        hidden = json.optBoolean("hidden")
    )

    override fun openRead(path: String): ParcelFileDescriptor? {
        val canonical = PiperPathPolicy.canonical(path)
        val pipe = ParcelFileDescriptor.createPipe()
        transferWorker.execute {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    session.copyRemoteFile(canonical, output)
                }
            }.onFailure { runCatching { pipe[1].close() } }
        }
        return pipe[0]
    }

    override fun mkdir(path: String) = fileSuccess("mkdir", PiperPathPolicy.requireWriteAllowed(path, false))

    override fun rename(source: String, destination: String): Boolean {
        val from = PiperPathPolicy.requireWriteAllowed(source, false)
        val to = PiperPathPolicy.requireWriteAllowed(destination, false)
        return fileSuccess("rename", from, destination = to)
    }

    override fun delete(path: String, recursive: Boolean) = fileSuccess(
        "delete", PiperPathPolicy.requireWriteAllowed(path, false), recursive = recursive
    )

    override fun chmod(path: String, mode: Int) = fileSuccess(
        "chmod", PiperPathPolicy.requireWriteAllowed(path, false), mode = mode
    )

    override fun chown(path: String, uid: Int, gid: Int) = false

    override fun close() {
        transferWorker.shutdownNow()
        session.close()
    }

    private fun fileSuccess(
        action: String,
        path: String,
        destination: String = "",
        recursive: Boolean = false,
        mode: Int = 0
    ) = session.fileOperation(action, path, destination, recursive, mode).optBoolean("success")
}
