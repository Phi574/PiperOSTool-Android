package com.piperostool.privileged.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.piperostool.privileged.IPiperOSService
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperAppActionPolicy
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperPrivilegedPreferences
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.PiperAdbClientPermission
import com.piperostool.privileged.file.NormalFileBackend
import com.piperostool.privileged.file.PrivilegedFileBackend
import com.piperostool.privileged.file.RootFileBackend
import com.piperostool.privileged.file.AdbFileBackend
import com.piperostool.privileged.adb.AdbShellSession
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PiperPrivilegedService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val initializationRunning = AtomicBoolean(false)
    private val initializationRerun = AtomicBoolean(false)
    private val backendLock = Any()
    private val logLock = Any()
    @Volatile private var desiredState = PiperServiceState.STARTING
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

        override fun getRecentLogs(sinceTimestamp: Long): Array<String> {
            enforceClient()
            val file = File(filesDir, "piperos/logs/pps.log")
            return synchronized(logLock) {
                runCatching {
                    file.takeIf { it.isFile }?.readLines().orEmpty()
                        .mapNotNull { line ->
                            val timestamp = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                            line.takeIf { timestamp >= sinceTimestamp }
                        }
                        .takeLast(100)
                        .toTypedArray()
                }.getOrDefault(emptyArray())
            }
        }

        override fun getCapabilities(): Bundle {
            enforceClient()
            return this@PiperPrivilegedService.capabilities.toBundle()
        }

        override fun getClientPermissions(): Bundle {
            enforceClient()
            return Bundle().apply {
                PiperAdbClientPermission.ALL.forEach { key ->
                    putBoolean(key, PiperPrivilegedPreferences.clientPermission(this@PiperPrivilegedService, key))
                }
            }
        }

        override fun setClientPermission(permission: String, granted: Boolean) {
            enforceClient()
            require(permission in PiperAdbClientPermission.ALL) { "Quyền client không hợp lệ" }
            PiperPrivilegedPreferences.setClientPermission(this@PiperPrivilegedService, permission, granted)
            log("client-permission", "client=${packageName} permission=$permission granted=$granted")
        }

        override fun transactSystemService(
            serviceName: String,
            transactionCode: Int,
            data: ByteArray,
            flags: Int
        ): Bundle {
            enforceClient()
            enforcePermission(PiperAdbClientPermission.SYSTEM_TRANSACTIONS)
            if (data.size > 256 * 1024) {
                return Bundle().apply {
                    putBoolean("handled", false)
                    putString("errorType", "TransactionTooLargeException")
                    putString("error", "Binder request exceeds 256 KiB")
                }
            }
            val adbBackend = backend as? AdbFileBackend
                ?: return Bundle().apply { putString("errorType", "PiperAdbUnavailable"); putString("error", "PiperOS ADB chưa kết nối") }
            val startedAt = SystemClock.elapsedRealtime()
            log("binder-transaction-start", "service=$serviceName code=$transactionCode bytes=${data.size}")
            return runCatching {
                synchronized(backendLock) {
                    adbBackend.transactSystemService(serviceName, transactionCode, data, flags)
                }
            }.fold(
                onSuccess = { result ->
                    val error = result.optString("error")
                    val errorType = result.optString("errorType")
                    log(
                        if (error.isBlank()) "binder-transaction-finish" else "binder-transaction-failed",
                        "service=$serviceName code=$transactionCode handled=${result.optBoolean("handled")} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} error_type=$errorType error=${error.take(160)}"
                    )
                    Bundle().apply {
                        putBoolean("handled", result.optBoolean("handled"))
                        result.optString("reply").takeIf(String::isNotBlank)?.let {
                            val reply = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                            if (reply.size <= 512 * 1024) putByteArray("reply", reply)
                            else {
                                putBoolean("handled", false)
                                putString("errorType", "TransactionTooLargeException")
                                putString("error", "Binder reply exceeds 512 KiB")
                            }
                        }
                        if (error.isNotBlank()) putString("error", error)
                        if (errorType.isNotBlank()) putString("errorType", errorType)
                    }
                },
                onFailure = { error ->
                    log("binder-transaction-failed", "service=$serviceName code=$transactionCode error=${error.message.orEmpty().take(160)}")
                    Bundle().apply {
                        putBoolean("handled", false)
                        putString("error", error.message.orEmpty())
                        putString("errorType", error.javaClass.name)
                    }
                }
            )
        }

        override fun executeShell(
            command: String,
            workingDirectory: String,
            stdin: String,
            environment: Bundle,
            timeoutMs: Long
        ): Bundle {
            enforceClient()
            enforcePermission(PiperAdbClientPermission.SHELL_COMMANDS)
            val adbBackend = backend as? AdbFileBackend
                ?: return Bundle().apply { putInt("exitCode", 125); putString("errorType", "PiperAdbUnavailable"); putString("output", "PiperOS ADB chưa kết nối") }
            val env = environment.keySet().mapNotNull { key -> environment.getString(key)?.let { key to it } }.toMap()
            val startedAt = SystemClock.elapsedRealtime()
            log("shell-process-start", "command_chars=${command.length} cwd=${workingDirectory.take(160)} env_keys=${env.keys.joinToString(",")} timeout_ms=$timeoutMs")
            return runCatching {
                synchronized(backendLock) {
                    adbBackend.newProcess(command, workingDirectory.takeIf(String::isNotBlank), stdin, env, timeoutMs)
                }
            }.fold(
                onSuccess = { result ->
                    val exitCode = result.optInt("exitCode", -1)
                    val output = result.optString("output")
                    log(
                        if (exitCode == 0) "shell-process-finish" else "shell-process-failed",
                        "exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} output_chars=${output.length} error_type=${result.optString("errorType")}"
                    )
                    Bundle().apply {
                        putInt("exitCode", exitCode)
                        putString("output", output.take(512 * 1024))
                        if (output.length > 512 * 1024) putBoolean("outputTruncated", true)
                        result.optString("errorType").takeIf(String::isNotBlank)?.let { putString("errorType", it) }
                    }
                },
                onFailure = { error ->
                    log("shell-process-failed", "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} error=${error.message.orEmpty().take(160)}")
                    Bundle().apply {
                        putInt("exitCode", 125)
                        putString("output", error.message.orEmpty())
                        putString("errorType", error.javaClass.name)
                    }
                }
            )
        }

        override fun openDirectory(path: String, showHidden: Boolean): ParcelFileDescriptor {
            enforceClient()
            enforcePermission(PiperAdbClientPermission.FILE_READ)
            log("file-list-start", "path_hash=${path.hashCode()} show_hidden=$showHidden")
            val pipe = ParcelFileDescriptor.createPipe()
            worker.execute {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).bufferedWriter().use { output ->
                    runCatching {
                        val entries = synchronized(backendLock) {
                            check(this@PiperPrivilegedService.status.state != PiperServiceState.STARTING && this@PiperPrivilegedService.status.state != PiperServiceState.STOPPED) {
                                this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang kết nối hoặc ngắt kết nối" }
                            }
                            backend.list(path, showHidden).toList()
                        }
                        log("file-list-finish", "path_hash=${path.hashCode()} count=${entries.size}")
                        entries.forEach { entry ->
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
            enforcePermission(PiperAdbClientPermission.FILE_READ)
            log("file-stat", "path_hash=${path.hashCode()}")
            val entry = runCatching {
                synchronized(backendLock) {
                    check(this@PiperPrivilegedService.status.state != PiperServiceState.STARTING && this@PiperPrivilegedService.status.state != PiperServiceState.STOPPED) {
                        this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang kết nối hoặc ngắt kết nối" }
                    }
                    backend.stat(path)
                }
            }.getOrElse {
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
            enforcePermission(PiperAdbClientPermission.FILE_READ)
            log("file-read-start", "path_hash=${path.hashCode()}")
            return runCatching {
                synchronized(backendLock) {
                    check(this@PiperPrivilegedService.status.state != PiperServiceState.STARTING && this@PiperPrivilegedService.status.state != PiperServiceState.STOPPED) {
                        this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang kết nối hoặc ngắt kết nối" }
                    }
                    backend.openRead(path)
                }
            }.onSuccess { log("file-read-open", "path_hash=${path.hashCode()} opened=${it != null}") }.getOrElse {
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

        override fun reconnectAdb() {
            enforceClient()
            if (PiperPrivilegedPreferences.adbEnabled(this@PiperPrivilegedService)) {
                requestInitialization(force = true)
            }
        }

        override fun isAdbEnabled(): Boolean {
            enforceClient()
            return PiperPrivilegedPreferences.adbEnabled(this@PiperPrivilegedService)
        }

        override fun setAdbEnabled(enabled: Boolean) {
            enforceClient()
            log("adb-toggle", "requested=$enabled previous=${PiperPrivilegedPreferences.adbEnabled(this@PiperPrivilegedService)}")
            PiperPrivilegedPreferences.setAdbEnabled(this@PiperPrivilegedService, enabled)
            log("adb-toggle", "requested=$enabled persisted=${PiperPrivilegedPreferences.adbEnabled(this@PiperPrivilegedService)}")
            if (enabled) {
                PiperPrivilegedPreferences.setMethod(this@PiperPrivilegedService, PiperPrivilegedPreferences.METHOD_AUTO)
                log("adb-enable-start", "User enabled persistent PiperOS ADB; restoring saved Wireless debugging authorization")
                val started = runCatching {
                    startService(Intent(this@PiperPrivilegedService, PiperPrivilegedService::class.java).setAction(ACTION_ADB_ENABLED))
                }.onFailure { log("adb-enable-start-failed", it) }.isSuccess
                if (!started) requestInitialization(force = true)
            } else {
                log("adb-stop-start", "User disabled PiperOS ADB; waiting for active file/app operation before closing the session")
                generation.incrementAndGet()
                initializationRerun.set(false)
                desiredState = PiperServiceState.STOPPED
                this@PiperPrivilegedService.status = this@PiperPrivilegedService.status.copy(
                    state = PiperServiceState.STARTING,
                    error = PiperError.NONE,
                    detail = "Đang đóng kết nối PiperOS ADB…"
                )
                worker.execute {
                    val closeResult = synchronized(backendLock) {
                        val result = runCatching { backend.close() }
                        backend = NormalFileBackend()
                        this@PiperPrivilegedService.capabilities = PiperCapabilities()
                        result
                    }
                    this@PiperPrivilegedService.status = this@PiperPrivilegedService.status.copy(
                        state = PiperServiceState.STOPPED,
                        privilege = PiperPrivilege.STANDARD,
                        error = PiperError.NONE,
                        detail = ""
                    )
                    closeResult.fold(
                        onSuccess = { log("adb-stop", "PiperOS ADB session closed") },
                        onFailure = { log("adb-stop-failed", it) }
                    )
                    stopSelf()
                }
            }
        }

        override fun runAppAction(action: String, packageName: String, activityName: String): Bundle {
            enforceClient()
            enforcePermission(
                if (action == PiperAppActionPolicy.LAUNCH_ACTIVITY) PiperAdbClientPermission.PRIVATE_ACTIVITIES
                else PiperAdbClientPermission.APP_MANAGEMENT
            )
            if (this@PiperPrivilegedService.status.state == PiperServiceState.STARTING || this@PiperPrivilegedService.status.state == PiperServiceState.STOPPED) {
                return Bundle().apply {
                    putBoolean("success", false)
                    putString("message", this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang xử lý kết nối" })
                }
            }
            if (!PiperPrivilegedPreferences.adbEnabled(this@PiperPrivilegedService)) {
                log("app-action-rejected", "ADB disabled action=$action package=$packageName")
                return Bundle().apply {
                    putBoolean("success", false)
                    putString("message", "Bật PiperOS ADB trước khi dùng thao tác này")
                }
            }
            val adbBackend = backend as? AdbFileBackend ?: run {
                log("app-action-rejected", "ADB backend unavailable action=$action package=$packageName")
                return Bundle().apply {
                    putBoolean("success", false)
                    putString("message", "PiperOS ADB chưa kết nối")
                }
            }
            val startedAt = SystemClock.elapsedRealtime()
            log("app-action-start", "action=$action package=$packageName")
            val result = runCatching {
                synchronized(backendLock) {
                    check(this@PiperPrivilegedService.status.state != PiperServiceState.STARTING && this@PiperPrivilegedService.status.state != PiperServiceState.STOPPED) {
                        this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang kết nối hoặc ngắt kết nối" }
                    }
                    adbBackend.runAppAction(action, packageName, activityName)
                }
            }.fold(
                onSuccess = { (success, message) ->
                    log(
                        "app-action-finish",
                        "action=$action package=$packageName success=$success elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} response=${message.take(160)}"
                    )
                    Bundle().apply {
                    putBoolean("success", success)
                    putString("message", message)
                    }
                },
                onFailure = { error ->
                    log(
                        "app-action-failed",
                        "action=$action package=$packageName elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} error=${error.message.orEmpty().take(160)}"
                    )
                    Bundle().apply {
                    putBoolean("success", false)
                    putString("message", error.message ?: "Không thực hiện được thao tác")
                    }
                }
            )
            return result
        }

        override fun shutdown() {
            enforceClient()
            generation.incrementAndGet()
            desiredState = PiperServiceState.STOPPED
            this@PiperPrivilegedService.status = this@PiperPrivilegedService.status.copy(
                state = PiperServiceState.STOPPED,
                privilege = PiperPrivilege.STANDARD,
                error = PiperError.NONE,
                detail = ""
            )
            this@PiperPrivilegedService.capabilities = PiperCapabilities()
            worker.execute {
                synchronized(backendLock) {
                    runCatching { backend.close() }
                    backend = NormalFileBackend()
                }
            }
        }

        private inline fun write(operation: String, action: () -> Boolean): Boolean {
            enforceClient()
            enforcePermission(PiperAdbClientPermission.FILE_WRITE)
            log("file-write-start", "operation=$operation")
            return runCatching {
                synchronized(backendLock) {
                    check(this@PiperPrivilegedService.status.state != PiperServiceState.STARTING && this@PiperPrivilegedService.status.state != PiperServiceState.STOPPED) {
                        this@PiperPrivilegedService.status.detail.ifBlank { "PiperOS ADB đang kết nối hoặc ngắt kết nối" }
                    }
                    action()
                }
            }.onSuccess { log("file-write-finish", "operation=$operation success=$it") }.getOrElse {
                log(operation, it)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A service created only through BIND_AUTO_CREATE can be destroyed as soon as the
        // caller leaves its screen. If the user explicitly left PiperOS ADB enabled, promote
        // this instance to a started service so the shared session survives Activity changes.
        // The sticky restart is still gated by the persisted user opt-in; OFF remains stopped.
        if (PiperPrivilegedPreferences.adbEnabled(this)) {
            runCatching {
                startService(Intent(this, PiperPrivilegedService::class.java).setAction(ACTION_ADB_ENABLED))
            }.onFailure { log("adb-service-promote-failed", it) }
            log("adb-service-promote", "Explicitly enabled ADB service promoted from bound to started")
        }
        requestInitialization()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> requestInitialization()
            ACTION_ADB_ENABLED -> requestInitialization()
        }
        return if (PiperPrivilegedPreferences.adbEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { backend.close() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun initializeBackend() {
        synchronized(backendLock) {
            runCatching { backend.close() }
            if (PiperPrivilegedPreferences.adbEnabled(this)) {
                initializeAdbBackend("")
                return
            }
            val method = PiperPrivilegedPreferences.method(this)
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
                setNormal(PiperError.ADB_DISABLED, "PiperOS ADB is turned off")
            }
        }
    }

    private fun requestInitialization(force: Boolean = false) {
        if (!force) {
            val current = status
            val activeBackend = backend
            val adbEnabled = PiperPrivilegedPreferences.adbEnabled(this)
            if (current.state == PiperServiceState.RUNNING && current.error == PiperError.NONE &&
                ((!adbEnabled && activeBackend is RootFileBackend) ||
                    (adbEnabled && activeBackend is AdbFileBackend && activeBackend.isConnected()))
            ) return
        }
        if (!initializationRunning.compareAndSet(false, true)) {
            if (force) initializationRerun.set(true)
            return
        }
        val requestedGeneration = generation.incrementAndGet()
        desiredState = PiperServiceState.STARTING
        val adbRequested = PiperPrivilegedPreferences.adbEnabled(this)
        status = status.copy(
            state = PiperServiceState.STARTING,
            error = PiperError.NONE,
            detail = if (adbRequested) "Đang kết nối PiperOS ADB bằng quyền đã ghép đôi…"
            else "Đang kiểm tra quyền hệ thống…"
        )
        worker.execute {
            val startedAt = SystemClock.elapsedRealtime()
            log("initialize-start", "adb=$adbRequested forced=$force")
            try {
                if (generation.get() == requestedGeneration) initializeBackend()
                if (generation.get() != requestedGeneration) {
                    synchronized(backendLock) {
                        runCatching { backend.close() }
                        backend = NormalFileBackend()
                        capabilities = PiperCapabilities()
                    }
                    status = status.copy(
                        state = desiredState,
                        privilege = PiperPrivilege.STANDARD,
                        error = PiperError.NONE,
                        detail = ""
                    )
                }
                log(
                    "initialize-finish",
                    "adb=$adbRequested elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} state=${status.state} method=${status.startupMethod} error=${status.error}"
                )
            } catch (error: Throwable) {
                log("initialize", error)
                setNormal(PiperError.ADB_NOT_AUTHORIZED, error.message.orEmpty())
            } finally {
                initializationRunning.set(false)
                if (initializationRerun.getAndSet(false)) requestInitialization(force = true)
            }
        }
    }

    private fun initializeAdbBackend(rootDetail: String) {
        updateConnectingDetail("Đang mở quyền Wireless debugging đã ghép đôi…")
        log("adb-connect-start", "Using saved Wireless debugging authorization")
        val session = AdbShellSession(this)
        val adb = runCatching {
            check(session.connect()) { "Wireless debugging is not paired or is switched off" }
            log("adb-connect-ok", "Wireless debugging socket connected")
            log(
                "privileged-server-ready",
                "app_process server started; UID=2000; PID=${session.privilegedServerPid()}; protocol=1"
            )
            updateConnectingDetail("Đã kết nối; đang xác minh shell UID 2000…")
            val identity = session.execute("id -u; echo \$\$; getenforce 2>/dev/null || echo Unknown")
            check(identity.exitCode == 0) { identity.output.ifBlank { "ADB shell identity check failed" } }
            val lines = identity.output.lines().filter(String::isNotBlank)
            check(lines.firstOrNull()?.toIntOrNull() == 2000) { "ADB connected without shell UID 2000" }
            log(
                "adb-identity-ok",
                "Shell UID=2000 verified; PID=${lines.getOrNull(1).orEmpty()}; SELinux=${lines.getOrNull(2).orEmpty()}"
            )
            session to lines
        }.onFailure { runCatching { session.close() } }
        if (adb.isFailure) {
            val detail = listOf(rootDetail, adb.exceptionOrNull()?.message.orEmpty())
                .filter(String::isNotBlank)
                .joinToString(" · ")
            log("adb-connect-failed", detail.ifBlank { "Wireless debugging connection failed" })
            setNormal(PiperError.ADB_NOT_AUTHORIZED, detail)
            return
        }
        val lines = adb.getOrThrow().second
        backend = AdbFileBackend(session)
        capabilities = backend.capabilities()
        status = PiperServiceStatus(
            state = PiperServiceState.RUNNING,
            privilege = PiperPrivilege.SHELL,
            uid = 2000,
            pid = session.privilegedServerPid().takeIf { it > 0 } ?: lines.getOrNull(1)?.toIntOrNull() ?: -1,
            startupMethod = "PIPEROS_ADB",
            selinux = lines.getOrNull(2).orEmpty().ifBlank { "Unknown" },
            startedAt = System.currentTimeMillis(),
            protocolVersion = PROTOCOL_VERSION
        )
        log("start", "PIPEROS_ADB app_process active; shell UID=2000; PID=${status.pid}; SELinux=${status.selinux}")
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

    private fun enforcePermission(permission: String) {
        if (!PiperPrivilegedPreferences.clientPermission(this, permission)) {
            log("client-permission-denied", "uid=${Binder.getCallingUid()} permission=$permission")
            throw SecurityException("PiperOS ADB client does not have permission: $permission")
        }
    }

    private fun log(operation: String, throwable: Throwable) =
        log(operation, "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(240)}")

    private fun log(operation: String, detail: String) {
        synchronized(logLock) {
            runCatching {
                val directory = File(filesDir, "piperos/logs").apply { mkdirs() }
                FileOutputStream(File(directory, "pps.log"), true).bufferedWriter().use {
                    it.appendLine("${System.currentTimeMillis()}\t$operation\t${detail.replace('\n', ' ')}")
                }
            }
        }
    }

    private fun updateConnectingDetail(detail: String) {
        if (status.state == PiperServiceState.STARTING) status = status.copy(detail = detail)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val ACTION_REFRESH = "com.piperostool.privileged.REFRESH"
        const val ACTION_ADB_ENABLED = "com.piperostool.privileged.ADB_ENABLED"
    }
}
