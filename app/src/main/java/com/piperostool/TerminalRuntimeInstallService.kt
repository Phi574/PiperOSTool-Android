package com.piperostool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.system.Os
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class TerminalRuntimeInstallService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested.set(true)
            installJob?.cancel(CancellationException("Cancelled by user"))
            return START_NOT_STICKY
        }
        if (!installing.compareAndSet(false, true)) return START_NOT_STICKY

        cancelRequested.set(false)
        publish(State(Phase.PREPARING, 0, getString(R.string.terminal_runtime_preparing)))
        startAsForeground(buildNotification(currentState))
        installJob = serviceScope.launch {
            runCatching { installRuntime() }
                .onSuccess {
                    publish(State(Phase.COMPLETE, 100, getString(R.string.terminal_runtime_complete)))
                    TerminalSessionManager.closeAll()
                    TerminalSessionManager.ensureSession(this@TerminalRuntimeInstallService)
                    startService(Intent(this@TerminalRuntimeInstallService, PiperTerminalService::class.java))
                }
                .onFailure { error ->
                    val cancelled = error is CancellationException || cancelRequested.get()
                    publish(
                        State(
                            if (cancelled) Phase.CANCELLED else Phase.ERROR,
                            currentState.progress,
                            if (cancelled) {
                                getString(R.string.terminal_runtime_cancelled)
                            } else {
                                getString(
                                    R.string.terminal_runtime_failed,
                                    error.message?.take(180) ?: error.javaClass.simpleName
                                )
                            }
                        )
                    )
                }
            installing.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installRuntime() {
        val workDirectory = File(cacheDir, "piperos-runtime-install")
        val archive = File(workDirectory, "runtime.zip")
        val stagingRoot = File(filesDir, ".piperos-runtime-staging")
        val stagingPrefix = File(stagingRoot, "usr")
        val backupPrefix = File(filesDir, ".piperos-runtime-backup")
        val activePrefix = File(filesDir, "usr")
        var activationStarted = false

        workDirectory.deleteRecursively()
        stagingRoot.deleteRecursively()
        workDirectory.mkdirsOrThrow()
        stagingPrefix.mkdirsOrThrow()

        try {
            publish(State(Phase.MANIFEST, 2, getString(R.string.terminal_runtime_checking)))
            val manifestBytes = downloadBytes(TerminalRuntime.MANIFEST_URL, MAX_MANIFEST_BYTES)
            val signatureBytes = downloadBytes(
                TerminalRuntime.MANIFEST_SIGNATURE_URL,
                MAX_SIGNATURE_BYTES
            )
            verifyManifestSignature(manifestBytes, signatureBytes)
            val selection = selectRuntime(JSONObject(String(manifestBytes, Charsets.UTF_8)))

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val requiredBytes = selection.size * 4 + MIN_FREE_SPACE_BYTES
            require(availableBytes >= requiredBytes) {
                getString(
                    R.string.terminal_runtime_not_enough_space,
                    formatMegabytes(requiredBytes),
                    formatMegabytes(availableBytes)
                )
            }

            publish(State(Phase.DOWNLOADING, 4, getString(R.string.terminal_runtime_downloading)))
            downloadArchive(selection, archive)
            verifyArchive(archive, selection)
            ensureNotCancelled()

            publish(State(Phase.EXTRACTING, 72, getString(R.string.terminal_runtime_extracting)))
            val symlinkLines = extractArchive(archive, stagingPrefix)
            restoreSymlinks(stagingPrefix, symlinkLines)
            provisionPackageRepository(stagingPrefix)
            TerminalRuntime.writeInstalledVersion(stagingPrefix, selection.version)
            require(File(stagingPrefix, "bin/bash").isFile) {
                getString(R.string.terminal_runtime_missing_shell)
            }

            publish(State(Phase.ACTIVATING, 94, getString(R.string.terminal_runtime_activating)))
            TerminalSessionManager.closeAll()
            activationStarted = true
            activateRuntime(stagingPrefix, activePrefix, backupPrefix)
            runSecondStage(activePrefix)
            backupPrefix.deleteRecursively()
        } catch (error: Throwable) {
            if (activationStarted) {
                activePrefix.deleteRecursively()
                if (backupPrefix.exists()) backupPrefix.renameTo(activePrefix)
            }
            throw error
        } finally {
            archive.delete()
            workDirectory.deleteRecursively()
            stagingRoot.deleteRecursively()
            if (TerminalSessionManager.sessionCount() == 0) {
                TerminalSessionManager.ensureSession(this)
            }
        }
    }

    private fun downloadBytes(url: String, maximumSize: Long): ByteArray {
        val connection = openConnection(url)
        return try {
            require(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode} for $url"
            }
            val expected = connection.contentLengthLong
            require(expected in -1..maximumSize) { "Remote file is too large" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    ensureNotCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maximumSize) { "Remote file exceeded size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadArchive(selection: RuntimeSelection, destination: File) {
        val connection = openConnection(selection.url)
        try {
            require(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode} while downloading runtime"
            }
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastProgress = -1
                    while (true) {
                        ensureNotCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= selection.size) {
                            "Runtime archive exceeded its signed size"
                        }
                        output.write(buffer, 0, count)
                        val progress = 4 + ((downloaded * 66) / selection.size)
                            .toInt()
                            .coerceIn(0, 66)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            publish(
                                State(
                                    Phase.DOWNLOADING,
                                    progress,
                                    getString(
                                        R.string.terminal_runtime_download_progress,
                                        progress,
                                        formatMegabytes(downloaded),
                                        formatMegabytes(selection.size)
                                    )
                                )
                            )
                        }
                    }
                    require(downloaded == selection.size) {
                        "Runtime archive size did not match signed manifest"
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyManifestSignature(manifest: ByteArray, signature: ByteArray) {
        require(signature.size == 64) { "Invalid manifest signature length" }
        val pem = resources.openRawResource(R.raw.piperos_manifest_public)
            .bufferedReader()
            .use { it.readText() }
        val encoded = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot(Char::isWhitespace)
        val x509 = Base64.decode(encoded, Base64.DEFAULT)
        require(x509.size == ED25519_X509_PREFIX.size + 32) { "Invalid public key" }
        require(x509.copyOfRange(0, ED25519_X509_PREFIX.size).contentEquals(ED25519_X509_PREFIX)) {
            "Unexpected public key format"
        }
        Ed25519Verify(x509.copyOfRange(ED25519_X509_PREFIX.size, x509.size))
            .verify(signature, manifest)
    }

    private fun selectRuntime(manifest: JSONObject): RuntimeSelection {
        require(manifest.optInt("schema") == 1) { "Unsupported runtime manifest schema" }
        require(manifest.optString("applicationId") == packageName) {
            "Runtime belongs to another application"
        }
        require(manifest.optInt("minApi") <= Build.VERSION.SDK_INT) {
            "Runtime requires a newer Android version"
        }
        val signedRoot = "/data/data/$packageName/files"
        require(manifest.optString("rootfs") == signedRoot) {
            "Runtime root path does not match this application"
        }
        require(manifest.optString("prefix") == "$signedRoot/usr") {
            "Runtime prefix path does not match this application"
        }
        val version = manifest.optString("runtimeVersion")
        require(version == TerminalRuntime.RUNTIME_VERSION) { "Unexpected runtime version: $version" }
        val assets = manifest.getJSONObject("assets")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { assets.has(it) }
            ?: error("This device ABI is not supported: ${Build.SUPPORTED_ABIS.joinToString()}")
        val asset = assets.getJSONObject(abi)
        val size = asset.getLong("size")
        val sha256 = asset.getString("sha256").lowercase()
        val url = asset.getString("url")
        require(size in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES) { "Invalid runtime size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid runtime checksum" }
        require(URL(url).protocol == "https") { "Runtime URL must use HTTPS" }
        return RuntimeSelection(version, abi, url, size, sha256)
    }

    private fun verifyArchive(archive: File, selection: RuntimeSelection) {
        require(archive.length() == selection.size) { "Runtime archive size mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureNotCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == selection.sha256) { "Runtime archive checksum mismatch" }
    }

    private fun extractArchive(archive: File, prefix: File): List<String> {
        val prefixPath = prefix.canonicalFile.toPath()
        val seen = HashSet<String>()
        val symlinkLines = mutableListOf<String>()
        var expandedBytes = 0L
        var fileCount = 0

        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                ensureNotCancelled()
                val entry = zip.nextEntry ?: break
                val cleanName = entry.name.replace('\\', '/').removePrefix("./")
                require(cleanName.isNotBlank() && !cleanName.startsWith("/")) {
                    "Invalid ZIP entry"
                }
                require(seen.add(cleanName)) { "Duplicate ZIP entry: $cleanName" }
                require(++fileCount <= MAX_ZIP_ENTRIES) { "Runtime contains too many files" }

                val output = prefix.resolve(cleanName).canonicalFile
                require(output.toPath().startsWith(prefixPath)) {
                    "Unsafe ZIP path: $cleanName"
                }
                if (entry.isDirectory) {
                    output.mkdirsOrThrow()
                    Os.chmod(output.absolutePath, DIRECTORY_MODE)
                } else if (cleanName == SYMLINKS_FILE) {
                    val text = zip.readBytesLimited(MAX_SYMLINK_FILE_BYTES)
                        .toString(Charsets.UTF_8)
                    symlinkLines += text.lineSequence().filter(String::isNotBlank)
                } else {
                    output.parentFile?.mkdirsOrThrow()
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expandedBytes += count
                            require(expandedBytes <= MAX_EXPANDED_BYTES) {
                                "Expanded runtime exceeded safety limit"
                            }
                            destination.write(buffer, 0, count)
                        }
                    }
                    Os.chmod(output.absolutePath, FILE_MODE)
                }
                zip.closeEntry()
            }
        }
        require(fileCount > 100) { "Runtime archive is unexpectedly empty" }
        require(symlinkLines.isNotEmpty()) { "Runtime symlink table is missing" }
        return symlinkLines
    }

    private fun restoreSymlinks(prefix: File, lines: List<String>) {
        val prefixPath = prefix.canonicalFile.toPath()
        val seenLinks = HashSet<String>()
        lines.forEach { line ->
            ensureNotCancelled()
            val separator = line.indexOf(SYMLINK_SEPARATOR)
            require(separator > 0 && separator < line.lastIndex) {
                "Invalid runtime symlink entry"
            }
            val target = line.substring(0, separator)
            val linkName = line.substring(separator + SYMLINK_SEPARATOR.length)
                .removePrefix("./")
            require(!File(linkName).isAbsolute) { "Absolute symlink path is not allowed" }
            val linkPath = prefixPath.resolve(linkName).normalize()
            require(linkPath.startsWith(prefixPath)) { "Unsafe symlink path" }
            require(seenLinks.add(linkPath.toString())) { "Duplicate runtime symlink" }
            if (File(target).isAbsolute) {
                val installedPrefix = "/data/data/$packageName/files/usr"
                require(target == installedPrefix || target.startsWith("$installedPrefix/")) {
                    "Runtime symlink points outside PREFIX"
                }
            } else {
                val resolvedTarget = linkPath.parent.resolve(target).normalize()
                require(resolvedTarget.startsWith(prefixPath)) {
                    "Runtime symlink points outside PREFIX"
                }
            }
            val link = linkPath.toFile()
            link.parentFile?.mkdirsOrThrow()
            if (link.exists()) require(link.delete()) { "Cannot replace symlink path" }
            Os.symlink(target, link.absolutePath)
        }
    }

    private fun provisionPackageRepository(prefix: File) {
        val keyring = File(prefix, "etc/apt/keyrings/piperos-archive-keyring.gpg")
        keyring.parentFile?.mkdirsOrThrow()
        resources.openRawResource(R.raw.piperos_apt_repository_public).use { input ->
            FileOutputStream(keyring).use { output -> input.copyTo(output) }
        }
        Os.chmod(keyring.absolutePath, READ_ONLY_FILE_MODE)

        val source = File(prefix, "etc/apt/sources.list.d/piperos.list")
        source.parentFile?.mkdirsOrThrow()
        source.writeText(
            "deb [signed-by=${keyring.absolutePath}] " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_URL} " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_SUITE} " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_COMPONENT}\n"
        )
        Os.chmod(source.absolutePath, READ_ONLY_FILE_MODE)
    }

    private fun activateRuntime(stagingPrefix: File, activePrefix: File, backupPrefix: File) {
        backupPrefix.deleteRecursively()
        if (activePrefix.exists()) {
            require(activePrefix.renameTo(backupPrefix)) { "Cannot back up current runtime" }
        }
        if (!stagingPrefix.renameTo(activePrefix)) {
            if (backupPrefix.exists()) backupPrefix.renameTo(activePrefix)
            error("Cannot activate downloaded runtime")
        }
    }

    private fun runSecondStage(prefix: File) {
        val script = listOf(
            File(prefix, "bin/termux-bootstrap-second-stage.sh"),
            File(prefix, "libexec/termux/termux-bootstrap-second-stage.sh"),
            File(prefix, "etc/termux-bootstrap/termux-bootstrap-second-stage.sh")
        ).firstOrNull(File::isFile) ?: return
        val bash = File(prefix, "bin/bash")
        val process = ProcessBuilder(bash.absolutePath, script.absolutePath)
            .directory(prefix)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", File(filesDir, "home").apply { mkdirs() }.absolutePath)
                    put("PREFIX", prefix.absolutePath)
                    put("TMPDIR", File(prefix, "tmp").apply { mkdirs() }.absolutePath)
                    put("PATH", "${File(prefix, "bin").absolutePath}:/system/bin")
                    put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
                    put("LANG", "C.UTF-8")
                }
            }
            .start()
        val output = process.inputStream.bufferedReader().use { reader ->
            val text = StringBuilder()
            while (process.isAlive) {
                while (reader.ready() && text.length < MAX_SECOND_STAGE_OUTPUT) {
                    text.append(reader.readLine()).append('\n')
                }
                if (!process.waitFor(200, TimeUnit.MILLISECONDS)) ensureNotCancelled()
            }
            while (reader.ready() && text.length < MAX_SECOND_STAGE_OUTPUT) {
                text.append(reader.readLine()).append('\n')
            }
            text.toString()
        }
        require(process.exitValue() == 0) {
            "Runtime setup failed: ${output.takeLast(500).trim()}"
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream, application/json")
            setRequestProperty("User-Agent", "PiperOS-Android/${AppVersion.name(this@TerminalRuntimeInstallService)}")
        }

    private fun publish(state: State) {
        currentState = state
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PHASE, state.phase.name)
                .putExtra(EXTRA_PROGRESS, state.progress)
                .putExtra(EXTRA_MESSAGE, state.message)
        )
    }

    private fun buildNotification(state: State): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, PiperTerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            Intent(this, TerminalRuntimeInstallService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(getString(R.string.terminal_runtime_notification_title))
            .setContentText(state.message)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setProgress(100, state.progress, state.phase == Phase.PREPARING)
            .apply {
                if (state.running) {
                    addAction(R.drawable.ic_browser_close, getString(R.string.cancel), cancelIntent)
                }
            }
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.terminal_runtime_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.terminal_runtime_notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun ensureNotCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Runtime installation cancelled")
        }
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format("%.1f MB", bytes / 1024.0 / 1024.0)

    private fun File.mkdirsOrThrow() {
        require(isDirectory || mkdirs()) { "Cannot create directory: $absolutePath" }
    }

    private fun ZipInputStream.readBytesLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "ZIP metadata is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    data class State(
        val phase: Phase,
        val progress: Int,
        val message: String
    ) {
        val running: Boolean
            get() = phase !in setOf(Phase.IDLE, Phase.COMPLETE, Phase.ERROR, Phase.CANCELLED)
    }

    enum class Phase {
        IDLE,
        PREPARING,
        MANIFEST,
        DOWNLOADING,
        EXTRACTING,
        ACTIVATING,
        COMPLETE,
        ERROR,
        CANCELLED
    }

    private data class RuntimeSelection(
        val version: String,
        val abi: String,
        val url: String,
        val size: Long,
        val sha256: String
    )

    companion object {
        const val ACTION_INSTALL = "com.piperostool.terminal.runtime.INSTALL"
        const val ACTION_CANCEL = "com.piperostool.terminal.runtime.CANCEL"
        const val ACTION_STATE_CHANGED = "com.piperostool.terminal.runtime.STATE_CHANGED"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "piperos_runtime_install"
        private const val NOTIFICATION_ID = 4520
        private const val OPEN_REQUEST_CODE = 4521
        private const val CANCEL_REQUEST_CODE = 4522
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_MANIFEST_BYTES = 256L * 1024
        private const val MAX_SIGNATURE_BYTES = 4L * 1024
        private const val MIN_ARCHIVE_BYTES = 1L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 400L * 1024 * 1024
        private const val MIN_FREE_SPACE_BYTES = 128L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 1536L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 200_000
        private const val MAX_SYMLINK_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_SECOND_STAGE_OUTPUT = 128 * 1024
        private const val SYMLINKS_FILE = "SYMLINKS.txt"
        private const val SYMLINK_SEPARATOR = "\u2190"
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 448 // 0700
        private const val READ_ONLY_FILE_MODE = 384 // 0600
        private val ED25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        private val installing = AtomicBoolean(false)
        private val cancelRequested = AtomicBoolean(false)

        @Volatile
        var currentState = State(Phase.IDLE, 0, "")
            private set
    }
}
