package com.piperostool.privileged.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.piperostool.privileged.IPiperOSService
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperPrivilegedPreferences
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.file.NormalFileBackend
import com.piperostool.privileged.file.PrivilegedFileBackend
import com.piperostool.privileged.file.RootFileBackend
import com.piperostool.privileged.file.AdbFileBackend
import com.piperostool.privileged.adb.AdbShellSession
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class PiperPrivilegedService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var backend: PrivilegedFileBackend = NormalFileBackend()
    @Volatile private var capabilities = PiperCapabilities()
    @Volatile private var status = PiperServiceStatus(
        state = PiperServiceState.STARTING,
        uid = Process.myUid(),
        pid = Process.myPid(),
        startedAt = System.currentTimeMillis()
    )

    private val binder = object : IPiperOSService.Stub() {
        override fun getProtocolVersion(): Int {
            enforceClient()
            return PROTOCOL_VERSION
        }

        override fun getStatus(): Bundle {
            enforceClient()
            return this@PiperPrivilegedService.status.toBundle()
        }

        override fun getCapabilities(): Bundle {
            enforceClient()
            return this@PiperPrivilegedService.capabilities.toBundle()
        }

        override fun openDirectory(path: String, showHidden: Boolean): ParcelFileDescriptor {
            enforceClient()
            val pipe = ParcelFileDescriptor.createPipe()
            worker.execute {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).bufferedWriter().use { output ->
                    runCatching {
                        backend.list(path, showHidden).forEach { entry ->
                            output.append(JSONObject().apply {
                                put("name", entry.name)
                                put("path", entry.path)
                                put("directory", entry.directory)
                                put("size", entry.size)
                                put("modified", entry.modifiedAt)
                                put("mode", entry.mode)
                                put("uid", entry.uid)
                                put("gid", entry.gid)
                                put("hidden", entry.hidden)
                                put("link", entry.symlinkTarget ?: JSONObject.NULL)
                            }.toString()).append('\n')
                        }
                    }.onFailure {
                        log("list", it)
                        output.append(JSONObject().apply {
                            put("_error", it.message ?: "Directory access failed")
                        }.toString()).append('\n')
                    }
                }
            }
            return pipe[0]
        }

        override fun stat(path: String): Bundle {
            enforceClient()
            val entry = runCatching { backend.stat(path) }.getOrElse {
                log("stat", it)
                null
            } ?: return Bundle().apply { putBoolean("exists", false) }
            return Bundle().apply {
                putBoolean("exists", true)
                putString("name", entry.name)
                putString("path", entry.path)
                putBoolean("directory", entry.directory)
                putLong("size", entry.size)
                putLong("modified", entry.modifiedAt)
                putString("mode", entry.mode)
                putInt("uid", entry.uid)
                putInt("gid", entry.gid)
                putString("link", entry.symlinkTarget)
            }
        }

        override fun openRead(path: String): ParcelFileDescriptor? {
            enforceClient()
            return runCatching { backend.openRead(path) }.getOrElse {
                log("openRead", it)
                null
            }
        }

        override fun mkdir(path: String) = write("mkdir") { backend.mkdir(path) }
        override fun rename(source: String, destination: String) = write("rename") {
            backend.rename(source, destination)
        }
        override fun delete(path: String, recursive: Boolean) = write("delete") {
            backend.delete(path, recursive)
        }
        override fun chmod(path: String, mode: Int) = write("chmod") { backend.chmod(path, mode) }
        override fun chown(path: String, uid: Int, gid: Int) = write("chown") {
            backend.chown(path, uid, gid)
        }

        override fun refreshCapabilities() {
            enforceClient()
            requestInitialization()
        }

        override fun shutdown() {
            enforceClient()
            this@PiperPrivilegedService.status = this@PiperPrivilegedService.status.copy(
                state = PiperServiceState.STOPPED,
                privilege = PiperPrivilege.STANDARD,
                error = PiperError.NONE,
                detail = ""
            )
            this@PiperPrivilegedService.capabilities = PiperCapabilities()
            worker.execute {
                runCatching { backend.close() }
                backend = NormalFileBackend()
            }
        }

        private inline fun write(operation: String, action: () -> Boolean): Boolean {
            enforceClient()
            return runCatching(action).getOrElse {
                log(operation, it)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        requestInitialization()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) requestInitialization()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { backend.close() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun initializeBackend() {
        runCatching { backend.close() }
        val method = PiperPrivilegedPreferences.method(this)
        if (method == PiperPrivilegedPreferences.METHOD_SHIZUKU) {
            setNormal(
                PiperError.UNSUPPORTED_OPERATION,
                "Shizuku/SUI is a separate method. Start Shizuku or SUI and grant PiperOS permission."
            )
            return
        }
        val root = runCatching { PersistentRootSession.open() }
        if (root.isSuccess) {
            val session = root.getOrThrow()
            backend = RootFileBackend(
                this,
                session
            ) { PiperPrivilegedPreferences.systemWrite(this) }
            capabilities = backend.capabilities()
            val pid = runCatching { session.execute("echo \$\$").output.trim().toInt() }.getOrDefault(-1)
            val selinux = runCatching { session.execute("getenforce 2>/dev/null || echo Unknown").output.trim() }
                .getOrDefault("Unknown")
            status = PiperServiceStatus(
                state = PiperServiceState.RUNNING,
                privilege = PiperPrivilege.ROOT,
                uid = 0,
                pid = pid,
                startupMethod = "ROOT",
                selinux = selinux,
                startedAt = System.currentTimeMillis(),
                protocolVersion = PROTOCOL_VERSION
            )
            log("start", "ROOT uid=0 pid=$pid selinux=$selinux")
        } else if (method == PiperPrivilegedPreferences.METHOD_SU) {
            setNormal(PiperError.ROOT_DENIED, root.exceptionOrNull()?.message.orEmpty())
        } else {
            initializeAdbBackend(root.exceptionOrNull()?.message.orEmpty())
        }
    }

    private fun requestInitialization() {
        status = status.copy(
            state = PiperServiceState.STARTING,
            error = PiperError.NONE,
            detail = ""
        )
        worker.execute(::initializeBackend)
    }

    private fun initializeAdbBackend(rootDetail: String) {
        val adb = runCatching {
            val session = AdbShellSession(this)
            check(session.connect()) { "Wireless debugging is not paired or is switched off" }
            val identity = session.execute("id -u; echo \$\$; getenforce 2>/dev/null || echo Unknown")
            check(identity.exitCode == 0) { identity.output.ifBlank { "ADB shell identity check failed" } }
            val lines = identity.output.lines().filter(String::isNotBlank)
            check(lines.firstOrNull()?.toIntOrNull() == 2000) { "ADB connected without shell UID 2000" }
            session to lines
        }
        if (adb.isFailure) {
            val detail = listOf(rootDetail, adb.exceptionOrNull()?.message.orEmpty())
                .filter(String::isNotBlank)
                .joinToString(" · ")
            setNormal(PiperError.ADB_NOT_AUTHORIZED, detail)
            return
        }
        val (session, lines) = adb.getOrThrow()
        backend = AdbFileBackend(session)
        capabilities = backend.capabilities()
        status = PiperServiceStatus(
            state = PiperServiceState.RUNNING,
            privilege = PiperPrivilege.SHELL,
            uid = 2000,
            pid = lines.getOrNull(1)?.toIntOrNull() ?: -1,
            startupMethod = "PIPEROS_ADB",
            selinux = lines.getOrNull(2).orEmpty().ifBlank { "Unknown" },
            startedAt = System.currentTimeMillis(),
            protocolVersion = PROTOCOL_VERSION
        )
        log("start", "PIPEROS_ADB uid=2000")
    }

    private fun setNormal(error: PiperError, detail: String) {
        backend = NormalFileBackend()
        capabilities = backend.capabilities()
        status = PiperServiceStatus(
            state = PiperServiceState.RUNNING,
            privilege = PiperPrivilege.STANDARD,
            uid = Process.myUid(),
            pid = Process.myPid(),
            startupMethod = "STANDARD",
            selinux = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }
                .map { if (it == "1") "Enforcing" else "Permissive" }
                .getOrDefault("Unknown"),
            startedAt = System.currentTimeMillis(),
            protocolVersion = PROTOCOL_VERSION,
            error = error,
            detail = detail.take(240)
        )
        log("start", "STANDARD error=${error.name}")
    }

    private fun enforceClient() {
        if (Binder.getCallingUid() != applicationInfo.uid) {
            log("client-denied", "uid=${Binder.getCallingUid()}")
            throw SecurityException("PiperOS client is not authorized")
        }
    }

    private fun log(operation: String, throwable: Throwable) =
        log(operation, "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(240)}")

    private fun log(operation: String, detail: String) {
        runCatching {
            val directory = File(filesDir, "piperos/logs").apply { mkdirs() }
            FileOutputStream(File(directory, "pps.log"), true).bufferedWriter().use {
                it.appendLine("${System.currentTimeMillis()}\t$operation\t${detail.replace('\n', ' ')}")
            }
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val ACTION_REFRESH = "com.piperostool.privileged.REFRESH"
    }
}
