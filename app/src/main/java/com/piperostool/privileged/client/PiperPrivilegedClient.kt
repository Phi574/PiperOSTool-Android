package com.piperostool.privileged.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.piperostool.privileged.IPiperOSService
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.server.PiperPrivilegedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            binding = false
            connectAsync()
        }
    }

    suspend fun connect(): Boolean {
        if (service != null) return true
        return suspendCancellableCoroutine { continuation ->
            synchronized(waiters) {
                waiters += { connected -> if (continuation.isActive) continuation.resume(connected) }
            }
            connectAsync()
        }
    }

    suspend fun status(): PiperServiceStatus? = withConnected {
        PiperServiceStatus.fromBundle(it.status)
    }

    suspend fun capabilities(): PiperCapabilities? = withConnected {
        PiperCapabilities.fromBundle(it.capabilities)
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
