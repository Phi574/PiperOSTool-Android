package com.piperostool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

open class TerminalRuntimeInstallService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    @Volatile private var activeJobParameters: JobParameters? = null
    @Volatile private var uidtJobMode = false
    private var requestedTag = TerminalRuntime.RUNTIME_RELEASE_TAG
    private var requestedVersion = TerminalRuntime.RUNTIME_VERSION
    private var requestedMode = InstallMode.NORMAL

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested.set(true)
            activeConnection.getAndSet(null)?.disconnect()
            installJob?.cancel(CancellationException("Cancelled by user"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                getSystemService(JobScheduler::class.java).cancel(UIDT_JOB_ID)
            }
            if (installJob == null && currentState.running) {
                finishInstall(Phase.CANCELLED, getString(R.string.terminal_runtime_cancelled))
                activeJobParameters?.let { params ->
                    activeJobParameters = null
                    runCatching { jobFinished(params, false) }
                }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_INSTALL) return START_NOT_STICKY
        // UIDT jobs replace dataSync FGS for modern Android. Keep the legacy
        // service entry point only for Android 10-13 where JobScheduler UIDT
        // does not exist yet.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            finishInstall(
                Phase.ERROR,
                getString(R.string.terminal_runtime_failed, "Use the user-initiated transfer job")
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startInstall(intent, jobParameters = null, startId = startId)
        return START_NOT_STICKY
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val intent = Intent(ACTION_INSTALL)
            .putExtra(EXTRA_RELEASE_TAG, extras.getString(EXTRA_RELEASE_TAG))
            .putExtra(EXTRA_RUNTIME_VERSION, extras.getString(EXTRA_RUNTIME_VERSION))
            .putExtra(EXTRA_INSTALL_MODE, extras.getString(EXTRA_INSTALL_MODE))
        return startInstall(intent, jobParameters = params, startId = null)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val shouldReschedule = !cancelRequested.get()
        if (activeJobParameters === params) activeJobParameters = null
        cancelRequested.set(true)
        activeConnection.getAndSet(null)?.disconnect()
        installJob?.cancel(CancellationException("User-initiated runtime job stopped"))
        if (installJob == null && currentState.running) {
            uidtJobMode = false
            finishInstall(Phase.CANCELLED, getString(R.string.terminal_runtime_cancelled))
            installing.set(false)
        }
        return shouldReschedule
    }

    private fun startInstall(
        intent: Intent,
        jobParameters: JobParameters?,
        startId: Int?
    ): Boolean {
        if (!installing.compareAndSet(false, true)) {
            if (jobParameters != null) {
                // A stopped UIDT job can be rescheduled while cancellation
                // cleanup is finishing. Ask JobScheduler to retry afterward.
                Handler(Looper.getMainLooper()).postDelayed(
                    { runCatching { jobFinished(jobParameters, true) } },
                    1_000L
                )
                return true
            }
            return false
        }

        activeJobParameters = jobParameters
        uidtJobMode = jobParameters != null
        requestedTag = intent.getStringExtra(EXTRA_RELEASE_TAG)
            ?.takeIf { it.matches(Regex("runtime-v[0-9][0-9A-Za-z._-]*")) }
            ?: TerminalRuntime.RUNTIME_RELEASE_TAG
        requestedVersion = intent.getStringExtra(EXTRA_RUNTIME_VERSION)
            ?.takeIf { requestedTag == "runtime-v$it" }
            ?: requestedTag.removePrefix("runtime-v")
        requestedMode = intent.getStringExtra(EXTRA_INSTALL_MODE)
            ?.let { runCatching { InstallMode.valueOf(it) }.getOrNull() }
            ?: InstallMode.NORMAL

        cancelRequested.set(false)
        publish(State(Phase.PREPARING, 0, getString(R.string.terminal_runtime_preparing)))
        try {
            if (jobParameters != null) {
                setUidtNotification(jobParameters, currentState)
            } else {
                startAsForeground(buildNotification(currentState))
            }
        } catch (error: Exception) {
            activeJobParameters = null
            uidtJobMode = false
            installing.set(false)
            finishInstall(
                Phase.ERROR,
                getString(
                    R.string.terminal_runtime_failed,
                    error.message?.take(180) ?: error.javaClass.simpleName
                )
            )
            if (jobParameters != null) {
                runCatching { jobFinished(jobParameters, false) }
            } else {
                startId?.let { stopSelf(it) }
            }
            return false
        }

        installJob = serviceScope.launch {
            try {
                installRuntime()
                if (cancelRequested.get()) throw CancellationException("Cancelled by user")
                publish(State(Phase.COMPLETE, 100, getString(R.string.terminal_runtime_complete)))
                TerminalSessionManager.closeAll()
                TerminalSessionManager.ensureSession(this@TerminalRuntimeInstallService)
                startService(Intent(this@TerminalRuntimeInstallService, PiperTerminalService::class.java))
            } catch (error: Exception) {
                val cancelled = error is CancellationException || cancelRequested.get()
                finishInstall(
                    if (cancelled) Phase.CANCELLED else Phase.ERROR,
                    if (cancelled) getString(R.string.terminal_runtime_cancelled) else getString(
                        R.string.terminal_runtime_failed,
                        error.message?.take(180) ?: error.javaClass.simpleName
                    )
                )
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
                installing.set(false)
                installJob = null
                if (jobParameters != null) {
                    if (activeJobParameters === jobParameters) {
                        activeJobParameters = null
                        runCatching { jobFinished(jobParameters, false) }
                    }
                    uidtJobMode = false
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    startId?.let { stopSelf(it) }
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        if (installJob?.isActive == true) {
            cancelRequested.set(true)
            activeConnection.getAndSet(null)?.disconnect()
            installJob?.cancel(CancellationException("Installer service was stopped"))
        } else if (currentState.running) {
            finishInstall(Phase.CANCELLED, getString(R.string.terminal_runtime_cancelled))
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun installRuntime() {
        val workDirectory = File(cacheDir, "piperos-runtime-install")
        val archive = File(workDirectory, "runtime.zip")
        val stagingRoot = File(filesDir, ".piperos-runtime-staging")
        val stagingPrefix = File(stagingRoot, "usr")
        val backupPrefix = File(filesDir, ".piperos-runtime-backup")
        val activePrefix = File(filesDir, "usr")
        var activationStarted = false

        if (requestedMode == InstallMode.CLEAN) {
            TerminalSessionManager.closeAll()
            TerminalRuntime.uninstall(this)
        }

        workDirectory.deleteRecursively()
        stagingRoot.deleteRecursively()
        workDirectory.mkdirsOrThrow()
        stagingPrefix.mkdirsOrThrow()

        try {
            publish(State(Phase.MANIFEST, 2, getString(R.string.terminal_runtime_checking)))
            val manifestBytes = downloadBytes(
                TerminalRuntime.manifestUrl(requestedTag),
                MAX_MANIFEST_BYTES
            )
            val signatureBytes = downloadBytes(
                TerminalRuntime.manifestSignatureUrl(requestedTag),
                MAX_SIGNATURE_BYTES
            )
            verifyManifestSignature(manifestBytes, signatureBytes)
            val selection = selectRuntime(
                JSONObject(String(manifestBytes, Charsets.UTF_8)),
                requestedVersion
            )

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val requiredBytes = selection.size * 8 + MIN_FREE_SPACE_BYTES
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
            rewriteBootstrapPaths(stagingPrefix)
            restoreSymlinks(stagingPrefix, symlinkLines)
            TerminalRuntime.writeInstalledVersion(stagingPrefix, selection.version)
            require(File(stagingPrefix, "bin/bash").isFile) {
                getString(R.string.terminal_runtime_missing_shell)
            }

            publish(State(Phase.ACTIVATING, 94, getString(R.string.terminal_runtime_activating)))
            TerminalSessionManager.closeAll()
            activationStarted = true
            activateRuntime(stagingPrefix, activePrefix, backupPrefix, requestedMode)
            runSecondStage(activePrefix)
            provisionPackageRepository(activePrefix)
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
        activeConnection.set(connection)
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
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun downloadArchive(selection: RuntimeSelection, destination: File) {
        for (attempt in 1..MAX_ARCHIVE_DOWNLOAD_ATTEMPTS) {
            ensureNotCancelled()
            destination.delete()
            try {
                downloadArchiveAttempt(selection, destination)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val retryable = when (error) {
                    is HttpResponseException ->
                        error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
                    is java.io.IOException -> true
                    else -> false
                }
                if (!retryable || attempt == MAX_ARCHIVE_DOWNLOAD_ATTEMPTS) throw error
                publish(
                    State(
                        Phase.DOWNLOADING,
                        4,
                        getString(
                            R.string.terminal_runtime_download_retry,
                            attempt,
                            MAX_ARCHIVE_DOWNLOAD_ATTEMPTS
                        )
                    )
                )
                Thread.sleep(ARCHIVE_RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun downloadArchiveAttempt(selection: RuntimeSelection, destination: File) {
        val connection = openConnection(selection.url)
        activeConnection.set(connection)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw HttpResponseException(
                    responseCode,
                    "HTTP $responseCode while downloading runtime"
                )
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
            activeConnection.compareAndSet(connection, null)
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

    private fun selectRuntime(manifest: JSONObject, expectedVersion: String): RuntimeSelection {
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
        require(version == expectedVersion) { "Unexpected runtime version: $version" }
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
                val normalizedName = normalizeRelativePath(cleanName)
                require(++fileCount <= MAX_ZIP_ENTRIES) { "Runtime contains too many files" }
                val output = File(prefix, normalizedName)
                if (!seen.add(normalizedName)) {
                    require(entry.isDirectory && output.isDirectory) {
                        "Duplicate ZIP file entry: $cleanName"
                    }
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    output.mkdirsOrThrow()
                    Os.chmod(output.absolutePath, DIRECTORY_MODE)
                } else if (normalizedName == SYMLINKS_FILE) {
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
            val normalizedLink = normalizeRelativePath(linkName)
            require(seenLinks.add(normalizedLink)) { "Duplicate runtime symlink" }
            if (File(target).isAbsolute) {
                val installedPrefix = "/data/data/$packageName/files/usr"
                require(target == installedPrefix || target.startsWith("$installedPrefix/")) {
                    "Runtime symlink points outside PREFIX"
                }
            } else {
                val linkParent = normalizedLink.substringBeforeLast('/', "")
                normalizeRelativePath(
                    if (linkParent.isEmpty()) target else "$linkParent/$target"
                )
            }
            val link = File(prefix, normalizedLink)
            link.parentFile?.mkdirsOrThrow()
            if (link.exists()) require(link.delete()) { "Cannot replace symlink path" }
            Os.symlink(target, link.absolutePath)
        }
    }

    private fun provisionPackageRepository(prefix: File) {
        val source = File(prefix, "etc/apt/sources.list.d/piperos.list")
        File(prefix, "etc/apt/sources.list").delete()
        File(prefix, "etc/apt/sources.list.d").apply {
            mkdirsOrThrow()
            listFiles()?.filter { it != source }?.forEach { it.delete() }
        }
        if (!isPackageRepositoryAvailable()) {
            source.delete()
            return
        }

        val keyring = File(prefix, "etc/apt/keyrings/piperos-archive-keyring.gpg")
        keyring.parentFile?.mkdirsOrThrow()
        resources.openRawResource(R.raw.piperos_apt_repository_public).use { input ->
            FileOutputStream(keyring).use { output -> input.copyTo(output) }
        }
        Os.chmod(keyring.absolutePath, READ_ONLY_FILE_MODE)

        // Packages built for com.termux cannot be mixed with the PiperOS prefix.
        // Remove bootstrap mirror definitions before enabling the pinned PiperOS repo.
        File(cacheDir, "apt/archives").listFiles()
            ?.filter { it.extension == "deb" }
            ?.forEach { it.delete() }

        source.parentFile?.mkdirsOrThrow()
        source.writeText(
            "deb [signed-by=${keyring.absolutePath}] " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_URL} " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_SUITE} " +
                "${TerminalRuntime.PACKAGE_REPOSITORY_COMPONENT}\n"
        )
        Os.chmod(source.absolutePath, READ_ONLY_FILE_MODE)
        installPackagePrefixGuard(prefix)
    }

    private fun installPackagePrefixGuard(prefix: File) {
        val guard = File(prefix, "libexec/piperos/verify-package-prefix").apply {
            parentFile?.mkdirsOrThrow()
            writeText(
                """#!/data/data/$packageName/files/usr/bin/sh
                |set -eu
                |while IFS= read -r archive; do
                |  [ -f "${'$'}archive" ] || continue
                |  if dpkg-deb --fsys-tarfile "${'$'}archive" 2>/dev/null | tar -tf - 2>/dev/null | grep -q '/data/data/com.termux'; then
                |    echo "PiperOS: blocked an incompatible com.termux package: ${'$'}archive" >&2
                |    echo "Use the PiperOS package repository or rebuild the package for $packageName." >&2
                |    exit 100
                |  fi
                |done
                |""".trimMargin()
            )
        }
        Os.chmod(guard.absolutePath, FILE_MODE)
        File(prefix, "etc/apt/apt.conf.d/99piperos-prefix-guard").apply {
            parentFile?.mkdirsOrThrow()
            writeText("DPkg::Pre-Install-Pkgs { \"${guard.absolutePath}\"; };\n")
        }
    }

    private fun isPackageRepositoryAvailable(): Boolean = runCatching {
        val releaseUrl =
            "${TerminalRuntime.PACKAGE_REPOSITORY_URL}/dists/" +
                "${TerminalRuntime.PACKAGE_REPOSITORY_SUITE}/Release"
        val connection = openConnection(releaseUrl)
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun activateRuntime(
        stagingPrefix: File,
        activePrefix: File,
        backupPrefix: File,
        mode: InstallMode
    ) {
        backupPrefix.deleteRecursively()
        if (activePrefix.exists()) {
            require(activePrefix.renameTo(backupPrefix)) { "Cannot back up current runtime" }
        }
        if (mode == InstallMode.KEEP_DATA && backupPrefix.exists()) {
            copyTree(backupPrefix, stagingPrefix, overwrite = false)
            PRESERVED_RUNTIME_PATHS.forEach { relativePath ->
                val source = File(backupPrefix, relativePath)
                if (!source.exists() && !isSymbolicLink(source)) return@forEach
                val destination = File(stagingPrefix, relativePath)
                deleteTreeWithoutFollowingLinks(destination)
                copyTree(source, destination, overwrite = true)
            }
            TerminalRuntime.writeInstalledVersion(stagingPrefix, requestedVersion)
        }
        if (!stagingPrefix.renameTo(activePrefix)) {
            if (backupPrefix.exists()) backupPrefix.renameTo(activePrefix)
            error("Cannot activate downloaded runtime")
        }
    }

    private fun copyTree(source: File, destination: File, overwrite: Boolean) {
        ensureNotCancelled()
        if (isSymbolicLink(source)) {
            if (destination.exists() || isSymbolicLink(destination)) {
                if (!overwrite) return
                deleteTreeWithoutFollowingLinks(destination)
            }
            destination.parentFile?.mkdirsOrThrow()
            Os.symlink(Os.readlink(source.absolutePath), destination.absolutePath)
            return
        }
        if (source.isDirectory) {
            destination.mkdirsOrThrow()
            source.listFiles()?.forEach { child ->
                copyTree(child, File(destination, child.name), overwrite)
            }
            return
        }
        if (destination.exists() && !overwrite) return
        destination.parentFile?.mkdirsOrThrow()
        source.copyTo(destination, overwrite = true)
    }

    private fun deleteTreeWithoutFollowingLinks(file: File) {
        if (!file.exists() && !isSymbolicLink(file)) return
        if (file.isDirectory && !isSymbolicLink(file)) {
            file.listFiles()?.forEach(::deleteTreeWithoutFollowingLinks)
        }
        check(file.delete() || !file.exists()) { "Cannot remove ${file.absolutePath}" }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    }.getOrDefault(false)

    private fun rewriteBootstrapPaths(prefix: File) {
        val appDataDirectory = applicationInfo.dataDir
        prefix.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.length() in 1..MAX_REWRITABLE_SCRIPT_BYTES
            }
            .forEach { file ->
                ensureNotCancelled()
                val bytes = file.readBytes()
                if (bytes.any { it == 0.toByte() }) return@forEach

                val source = bytes.toString(Charsets.UTF_8)
                val rewritten = source
                    .replace(LEGACY_TERMUX_USER_DATA_DIR, appDataDirectory)
                    .replace(LEGACY_TERMUX_DATA_DIR, appDataDirectory)
                if (rewritten != source) {
                    file.writeText(rewritten, Charsets.UTF_8)
                }
            }
    }

    private fun runSecondStage(prefix: File) {
        val script = listOf(
            File(prefix, "bin/termux-bootstrap-second-stage.sh"),
            File(prefix, "libexec/termux/termux-bootstrap-second-stage.sh"),
            File(prefix, "etc/termux-bootstrap/termux-bootstrap-second-stage.sh"),
            File(
                prefix,
                "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
            )
        ).firstOrNull(File::isFile) ?: return
        val bash = File(prefix, "bin/bash")
        val process = TermuxProcessLauncher.create(
            context = this,
            executable = bash,
            arguments = listOf(script.absolutePath),
            workingDirectory = prefix,
            prefixDirectory = prefix,
            homeDirectory = File(filesDir, "home").apply { mkdirs() }
        ).start()
        val output = StringBuilder()
        val outputThread = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < MAX_SECOND_STAGE_OUTPUT) {
                            output.append(line).append('\n')
                        }
                    }
                }
            }
        }, "PiperRuntimeSecondStageOutput").apply {
            isDaemon = true
            start()
        }
        val deadline = SystemClock.elapsedRealtime() + SECOND_STAGE_TIMEOUT_MS
        var completedExitCode: Int? = null
        try {
            while (completedExitCode == null) {
                ensureNotCancelled()
                try {
                    completedExitCode = process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    require(SystemClock.elapsedRealtime() < deadline) {
                        "Runtime setup timed out"
                    }
                    Thread.sleep(200)
                }
            }
        } finally {
            runCatching { process.destroy() }
            runCatching { outputThread.join(2_000) }
        }
        val exitCode = requireNotNull(completedExitCode)
        require(exitCode == 0) {
            val tail = synchronized(output) { output.toString().takeLast(500).trim() }
            "Runtime setup failed: $tail"
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
        val jobParameters = activeJobParameters
        if (uidtJobMode) {
            if (jobParameters != null) setUidtNotification(jobParameters, state)
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(state))
        }
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PHASE, state.phase.name)
                .putExtra(EXTRA_PROGRESS, state.progress)
                .putExtra(EXTRA_MESSAGE, state.message)
        )
    }

    private fun finishInstall(phase: Phase, message: String) {
        runCatching { publish(State(phase, currentState.progress, message)) }
        installing.set(false)
    }

    private fun setUidtNotification(params: JobParameters, state: State) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        setNotification(
            params,
            NOTIFICATION_ID,
            buildNotification(state),
            if (state.running) {
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH
            } else {
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
            }
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
        val cancelPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent.getBroadcast(
                this,
                CANCEL_REQUEST_CODE,
                Intent(this, TerminalRuntimeInstallCancelReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                CANCEL_REQUEST_CODE,
                Intent(this, TerminalRuntimeLegacyInstallService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
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
                    addAction(R.drawable.ic_browser_close, getString(R.string.cancel), cancelPendingIntent)
                }
            }
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
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

    private fun normalizeRelativePath(path: String): String {
        val normalizedInput = path.replace('\\', '/')
        require(
            normalizedInput.isNotBlank() &&
                !normalizedInput.startsWith('/') &&
                !normalizedInput.contains('\u0000')
        ) {
            "Unsafe relative path"
        }
        val parts = ArrayDeque<String>()
        normalizedInput.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> {
                    require(parts.isNotEmpty()) { "Path escapes PREFIX" }
                    parts.removeLast()
                }
                else -> parts.addLast(part)
            }
        }
        require(parts.isNotEmpty()) { "Empty relative path" }
        return parts.joinToString("/")
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

    enum class InstallMode {
        NORMAL,
        KEEP_DATA,
        CLEAN
    }

    private data class RuntimeSelection(
        val version: String,
        val abi: String,
        val url: String,
        val size: Long,
        val sha256: String
    )

    private class HttpResponseException(
        val statusCode: Int,
        message: String
    ) : java.io.IOException(message)

    companion object {
        const val ACTION_INSTALL = "com.piperostool.terminal.runtime.INSTALL"
        const val ACTION_CANCEL = "com.piperostool.terminal.runtime.CANCEL"
        const val ACTION_STATE_CHANGED = "com.piperostool.terminal.runtime.STATE_CHANGED"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RELEASE_TAG = "release_tag"
        const val EXTRA_RUNTIME_VERSION = "runtime_version"
        const val EXTRA_INSTALL_MODE = "install_mode"
        const val UIDT_JOB_ID = 4_520

        private const val CHANNEL_ID = "piperos_runtime_install"
        private const val NOTIFICATION_ID = 4520
        private const val OPEN_REQUEST_CODE = 4521
        private const val CANCEL_REQUEST_CODE = 4522
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_ARCHIVE_DOWNLOAD_ATTEMPTS = 3
        private const val ARCHIVE_RETRY_DELAY_MS = 1_500L
        private const val ESTIMATED_RUNTIME_DOWNLOAD_BYTES = 150L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256L * 1024
        private const val MAX_SIGNATURE_BYTES = 4L * 1024
        private const val MIN_ARCHIVE_BYTES = 1L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 900L * 1024 * 1024
        private const val MIN_FREE_SPACE_BYTES = 256L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 400_000
        private const val MAX_SYMLINK_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_SECOND_STAGE_OUTPUT = 128 * 1024
        private const val MAX_REWRITABLE_SCRIPT_BYTES = 2L * 1024 * 1024
        private const val SECOND_STAGE_TIMEOUT_MS = 5L * 60 * 1000
        private const val SYMLINKS_FILE = "SYMLINKS.txt"
        private const val SYMLINK_SEPARATOR = "\u2190"
        private const val LEGACY_TERMUX_DATA_DIR = "/data/data/com.termux"
        private const val LEGACY_TERMUX_USER_DATA_DIR = "/data/user/0/com.termux"
        private val PRESERVED_RUNTIME_PATHS = listOf(
            "var/lib/dpkg",
            "var/lib/apt",
            "etc/apt",
            "etc/environment"
        )
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

        fun scheduleUserInitiatedInstall(
            context: android.content.Context,
            release: TerminalRuntimeCatalog.Release,
            mode: InstallMode
        ): Int {
            val extras = PersistableBundle().apply {
                putString(EXTRA_RELEASE_TAG, release.tag)
                putString(EXTRA_RUNTIME_VERSION, release.version)
                putString(EXTRA_INSTALL_MODE, mode.name)
            }
            val jobInfo = JobInfo.Builder(
                UIDT_JOB_ID,
                ComponentName(context, TerminalRuntimeInstallService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setUserInitiated(true)
                .setEstimatedNetworkBytes(ESTIMATED_RUNTIME_DOWNLOAD_BYTES, 0L)
                .setExtras(extras)
                .build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                val queuedState = State(
                    Phase.PREPARING,
                    0,
                    context.getString(R.string.terminal_runtime_waiting_network)
                )
                currentState = queuedState
                context.sendBroadcast(
                    Intent(ACTION_STATE_CHANGED)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_PHASE, queuedState.phase.name)
                        .putExtra(EXTRA_PROGRESS, queuedState.progress)
                        .putExtra(EXTRA_MESSAGE, queuedState.message)
                )
            }
            return result
        }

        fun cancelUserInitiatedInstall(context: android.content.Context) {
            context.getSystemService(JobScheduler::class.java).cancel(UIDT_JOB_ID)
            val cancelledState = State(
                Phase.CANCELLED,
                currentState.progress,
                context.getString(R.string.terminal_runtime_cancelled)
            )
            currentState = cancelledState
            context.sendBroadcast(
                Intent(ACTION_STATE_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_PHASE, cancelledState.phase.name)
                    .putExtra(EXTRA_PROGRESS, cancelledState.progress)
                    .putExtra(EXTRA_MESSAGE, cancelledState.message)
            )
        }
    }

    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
}
