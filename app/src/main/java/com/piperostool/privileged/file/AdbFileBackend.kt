package com.piperostool.privileged.file

import android.os.ParcelFileDescriptor
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperPathPolicy
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.adb.AdbShellSession
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal class AdbFileBackend(
    private val session: AdbShellSession
) : PrivilegedFileBackend {
    private val transferWorker = Executors.newCachedThreadPool()
    override val privilege = PiperPrivilege.SHELL

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
        val quoted = PiperPathPolicy.shellQuote(canonical)
        val flags = if (showHidden) "-lnA" else "-ln"
        val result = session.execute(
            "[ -d $quoted ] || { echo 'Not a directory' >&2; exit 2; }; " +
                "[ -r $quoted ] || { echo 'Permission denied' >&2; exit 13; }; " +
                "LC_ALL=C ls $flags -- $quoted"
        )
        if (result.exitCode != 0) {
            throw IOException(result.output.ifBlank { "Không thể đọc $canonical (exit ${result.exitCode})" })
        }
        val entries = result.output.lineSequence()
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("total ") }
            .mapNotNull { parseListEntry(canonical, it) }
            .filter { showHidden || !it.hidden }
            .toList()
        if (result.output.isNotBlank() && entries.isEmpty()) {
            throw IOException("PPS nhận dữ liệu thư mục không hợp lệ từ $canonical")
        }
        return entries.asSequence()
    }

    private fun parseListEntry(parent: String, line: String): PiperFileEntry? {
        val values = line.trim().split(Regex("\\s+"), limit = 8)
        if (values.size < 8) return null
        val mode = values[0]
        val rawName = values[7]
        val name = if (mode.startsWith('l')) rawName.substringBefore(" -> ") else rawName
        if (name == "." || name == ".." || name.isBlank()) return null
        val path = if (parent == "/") "/$name" else "$parent/$name"
        return PiperFileEntry(
            name = name,
            path = path,
            directory = mode.startsWith('d'),
            size = values[4].toLongOrNull() ?: 0L,
            modifiedAt = 0L,
            mode = mode,
            uid = values[2].toIntOrNull() ?: -1,
            gid = values[3].toIntOrNull() ?: -1,
            symlinkTarget = if (mode.startsWith('l')) rawName.substringAfter(" -> ", "").ifBlank { null } else null
        )
    }

    override fun stat(path: String): PiperFileEntry? {
        val canonical = PiperPathPolicy.canonical(path)
        val quoted = PiperPathPolicy.shellQuote(canonical)
        val result = session.execute(
            "if [ -e $quoted ] || [ -L $quoted ]; then " +
                "if [ -d $quoted ]; then t=d; else t=f; fi; " +
                "s=\$(stat -c %s $quoted 2>/dev/null || echo 0); " +
                "m=\$(stat -c %Y $quoted 2>/dev/null || echo 0); " +
                "o=\$(stat -c %a $quoted 2>/dev/null || echo ''); " +
                "u=\$(stat -c %u $quoted 2>/dev/null || echo -1); " +
                "g=\$(stat -c %g $quoted 2>/dev/null || echo -1); " +
                "l=\$(readlink $quoted 2>/dev/null || true); " +
                "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s' \"\$t\" \"\$s\" \"\$m\" \"\$o\" \"\$u\" \"\$g\"; " +
                "[ -n \"\$l\" ] && printf '\\t%s' \"\$l\"; fi"
        )
        if (result.exitCode != 0 || result.output.isBlank()) return null
        val values = result.output.lineSequence().last().split('\t')
        return PiperFileEntry(
            name = File(canonical).name.ifEmpty { "/" },
            path = canonical,
            directory = values.getOrNull(0) == "d",
            size = values.getOrNull(1)?.toLongOrNull() ?: 0L,
            modifiedAt = (values.getOrNull(2)?.toLongOrNull() ?: 0L) * 1000L,
            mode = values.getOrNull(3).orEmpty(),
            uid = values.getOrNull(4)?.toIntOrNull() ?: -1,
            gid = values.getOrNull(5)?.toIntOrNull() ?: -1,
            symlinkTarget = values.getOrNull(6)?.ifBlank { null }
        )
    }

    override fun openRead(path: String): ParcelFileDescriptor? {
        val canonical = PiperPathPolicy.canonical(path)
        val pipe = ParcelFileDescriptor.createPipe()
        transferWorker.execute {
            runCatching {
                session.open("cat -- ${PiperPathPolicy.shellQuote(canonical)}").use { stream ->
                    stream.openInputStream().use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }.onFailure { runCatching { pipe[1].close() } }
        }
        return pipe[0]
    }

    override fun mkdir(path: String) = write(path) { "mkdir -- ${PiperPathPolicy.shellQuote(it)}" }

    override fun rename(source: String, destination: String): Boolean {
        val from = PiperPathPolicy.requireWriteAllowed(source, false)
        val to = PiperPathPolicy.requireWriteAllowed(destination, false)
        return session.execute("mv -- ${PiperPathPolicy.shellQuote(from)} ${PiperPathPolicy.shellQuote(to)}").exitCode == 0
    }

    override fun delete(path: String, recursive: Boolean) = write(path) {
        if (recursive) "rm -rf -- ${PiperPathPolicy.shellQuote(it)}" else "rm -f -- ${PiperPathPolicy.shellQuote(it)}"
    }

    override fun chmod(path: String, mode: Int) = write(path) {
        "chmod ${mode.toString(8)} -- ${PiperPathPolicy.shellQuote(it)}"
    }

    override fun chown(path: String, uid: Int, gid: Int) = false

    override fun close() {
        transferWorker.shutdownNow()
    }

    private inline fun write(path: String, command: (String) -> String): Boolean {
        val safe = PiperPathPolicy.requireWriteAllowed(path, false)
        return session.execute(command(safe)).exitCode == 0
    }
}
