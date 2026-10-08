package com.piperostool.privileged.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.piperostool.privileged.IPiperOSService
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.server.PiperPrivilegedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

class PiperPrivilegedClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    @Volatile private var service: IPiperOSService? = null
    @Volatile private var binding = false
    private val waiters = mutableListOf<(Boolean) -> Unit>()
    private val deathRecipient = IBinder.DeathRecipient {
        service = null
        binding = false
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
            service = IPiperOSService.Stub.asInterface(binder)
            binding = false
            finishWaiters(service != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            finishWaiters(false)
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            binding = false
            connectAsync()
        }
    }

    suspend fun connect(): Boolean {
        if (service != null) return true
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val waiter: (Boolean) -> Unit = { connected ->
                    if (continuation.isActive) continuation.resume(connected)
                }
                synchronized(waiters) { waiters += waiter }
                continuation.invokeOnCancellation { synchronized(waiters) { waiters.remove(waiter) } }
                connectAsync()
            }
        } ?: false
    }

    suspend fun status(): PiperServiceStatus? = withConnected {
        PiperServiceStatus.fromBundle(it.status)
    }

    suspend fun recentLogs(sinceTimestamp: Long): List<String> = withConnected {
        it.getRecentLogs(sinceTimestamp).orEmpty().toList()
    }.orEmpty()

    suspend fun capabilities(): PiperCapabilities? = withConnected {
        PiperCapabilities.fromBundle(it.capabilities)
    }

    suspend fun clientPermissions(): Map<String, Boolean> = withConnected { remote ->
        remote.getClientPermissions().let { bundle ->
            listOf("file_read", "file_write", "app_management", "private_activities", "system_transactions", "shell_commands")
                .associateWith { bundle.getBoolean(it, true) }
        }
    }.orEmpty()

    suspend fun setClientPermission(permission: String, granted: Boolean): Boolean = withConnected {
        it.setClientPermission(permission, granted)
        true
    } ?: false

    suspend fun transactSystemService(
        serviceName: String,
        transactionCode: Int,
        data: ByteArray,
        flags: Int = 0
    ): PiperSystemTransactionResult? = withConnected { remote ->
        val result = remote.transactSystemService(serviceName, transactionCode, data, flags)
        PiperSystemTransactionResult(
            handled = result.getBoolean("handled"),
            reply = result.getByteArray("reply") ?: byteArrayOf(),
            errorType = result.getString("errorType").orEmpty(),
            error = result.getString("error").orEmpty()
        )
    }

    suspend fun newProcess(
        command: String,
        workingDirectory: String? = null,
        stdin: String = "",
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L
    ): PiperShellCommandResult? = withConnected { remote ->
        val variables = Bundle().apply { environment.forEach { (key, value) -> putString(key, value) } }
        val result = remote.executeShell(command, workingDirectory.orEmpty(), stdin, variables, timeoutMs)
        PiperShellCommandResult(
            output = result.getString("output").orEmpty(),
            exitCode = result.getInt("exitCode", -1),
            errorType = result.getString("errorType").orEmpty()
        )
    }

    suspend fun list(path: String, showHidden: Boolean): List<PiperFileEntry>? = withConnected { remote ->
        remote.openDirectory(path, showHidden)?.use(::readDirectory)
    }

    suspend fun materializeReadOnly(path: String, destination: File): File? = withConnected { remote ->
        val descriptor = remote.openRead(path) ?: return@withConnected null
        destination.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            FileOutputStream(destination).use(input::copyTo)
        }
        destination
    }

    suspend fun mkdir(path: String): Boolean = withConnected { it.mkdir(path) } ?: false
    suspend fun rename(source: String, destination: String): Boolean =
        withConnected { it.rename(source, destination) } ?: false
    suspend fun delete(path: String, recursive: Boolean): Boolean =
        withConnected { it.delete(path, recursive) } ?: false

    suspend fun refresh(): Boolean = withConnected {
        it.refreshCapabilities()
        true
    } ?: false

    suspend fun reconnectAdb(): Boolean = withConnected {
        it.reconnectAdb()
        true
    } ?: false

    suspend fun adbEnabled(): Boolean = withConnected { it.isAdbEnabled() } ?: false

    suspend fun setAdbEnabled(enabled: Boolean): Boolean = withConnected {
        it.setAdbEnabled(enabled)
        true
    } ?: false

    suspend fun runAppAction(
        action: String,
        packageName: String,
        activityName: String = ""
    ): PiperAppActionResult? = withConnected {
        val result = it.runAppAction(action, packageName, activityName)
        PiperAppActionResult(
            success = result.getBoolean("success"),
            message = result.getString("message").orEmpty()
        )
    }

    suspend fun shutdown(): Boolean = withConnected {
        it.shutdown()
        true
    } ?: false

    override fun close() {
        runCatching { service?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
        runCatching { appContext.unbindService(connection) }
        service = null
        binding = false
    }

    private fun connectAsync() {
        if (service != null || binding) return
        binding = true
        val connected = runCatching {
            appContext.bindService(
                Intent(appContext, PiperPrivilegedService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        if (!connected) {
            binding = false
            finishWaiters(false)
        }
    }

    private suspend fun <T> withConnected(block: (IPiperOSService) -> T): T? = withContext(Dispatchers.IO) {
        if (!connect()) return@withContext null
        val remote = service ?: return@withContext null
        runCatching { block(remote) }.getOrNull()
    }

    private fun finishWaiters(connected: Boolean) {
        val callbacks = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
        callbacks.forEach { it(connected) }
    }

    private fun readDirectory(descriptor: ParcelFileDescriptor): List<PiperFileEntry> =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { lines ->
            lines.map { line ->
                val json = JSONObject(line)
                if (json.has("_error")) throw IOException(json.optString("_error", "Directory access failed"))
                runCatching {
                    PiperFileEntry(
                        name = json.getString("name"),
                        path = json.getString("path"),
                        directory = json.getBoolean("directory"),
                        size = json.optLong("size"),
                        modifiedAt = json.optLong("modified"),
                        mode = json.optString("mode"),
                        uid = json.optInt("uid", -1),
                        gid = json.optInt("gid", -1),
                        symlinkTarget = json.optString("link").takeUnless { it.isBlank() || it == "null" },
                        hidden = json.optBoolean("hidden")
                    )
                }.getOrNull()
            }.filterNotNull().toList()
        }
}

data class PiperAppActionResult(val success: Boolean, val message: String)
data class PiperSystemTransactionResult(
    val handled: Boolean,
    val reply: ByteArray,
    val errorType: String,
    val error: String
)
data class PiperShellCommandResult(val output: String, val exitCode: Int, val errorType: String)
