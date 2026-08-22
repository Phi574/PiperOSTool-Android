package com.piperostool.privileged.file

import android.content.Context
import android.os.ParcelFileDescriptor
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperPathPolicy
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.server.PersistentRootSession
import java.io.File

internal class RootFileBackend(
    context: Context,
    private val session: PersistentRootSession,
    private val systemWriteEnabled: () -> Boolean
) : PrivilegedFileBackend {
    private val tempDirectory = File(context.cacheDir, "pps-read").apply { mkdirs() }
    override val privilege = PiperPrivilege.ROOT

    override fun capabilities() = PiperCapabilities(
        privilege = PiperPrivilege.ROOT,
        canAccessAndroidData = probe("test -r /storage/emulated/0/Android/data"),
        canAccessAndroidObb = probe("test -r /storage/emulated/0/Android/obb"),
        canReadSystemFiles = probe("test -r /system/build.prop || test -r /system/etc/hosts"),
        canWriteSystemFiles = systemWriteEnabled() && probe("test -w /system"),
        canUsePackageManager = probe("command -v pm >/dev/null"),
        canUseAppOps = probe("command -v appops >/dev/null"),
        canChmod = true,
        canChown = true,
        canExecutePrivilegedCommands = true,
        canManageProcesses = true
    )

    override fun list(path: String, showHidden: Boolean): Sequence<PiperFileEntry> {
        val canonical = PiperPathPolicy.canonical(path)
        val quoted = PiperPathPolicy.shellQuote(canonical)
        val command = "find $quoted -mindepth 1 -maxdepth 1 -print 2>/dev/null"
        val result = session.execute(command, 30_000L)
        if (result.exitCode != 0 && result.output.isBlank()) return emptySequence()
        return result.output.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { raw -> runCatching { stat(raw) }.getOrNull() }
            .filterNotNull()
            .filter { showHidden || !it.hidden }
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
        val output = File(tempDirectory, "${canonical.hashCode()}-${System.nanoTime()}")
        val result = session.execute(
            "cp -- ${PiperPathPolicy.shellQuote(canonical)} ${PiperPathPolicy.shellQuote(output.path)} && " +
                "chmod 0644 ${PiperPathPolicy.shellQuote(output.path)}",
            60_000L
        )
        return if (result.exitCode == 0) {
            runCatching { ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        } else null
    }

    override fun mkdir(path: String): Boolean = write(path) {
        "mkdir -- ${PiperPathPolicy.shellQuote(it)}"
    }
    override fun rename(source: String, destination: String): Boolean {
        val safeSource = PiperPathPolicy.requireWriteAllowed(source, systemWriteEnabled())
        val safeDestination = PiperPathPolicy.requireWriteAllowed(destination, systemWriteEnabled())
        return session.execute(
            "mv -- ${PiperPathPolicy.shellQuote(safeSource)} ${PiperPathPolicy.shellQuote(safeDestination)}"
        ).exitCode == 0
    }
    override fun delete(path: String, recursive: Boolean): Boolean = write(path) {
        if (recursive) "rm -rf -- ${PiperPathPolicy.shellQuote(it)}" else "rm -f -- ${PiperPathPolicy.shellQuote(it)}"
    }
    override fun chmod(path: String, mode: Int): Boolean = write(path) {
        "chmod ${mode.toString(8)} -- ${PiperPathPolicy.shellQuote(it)}"
    }
    override fun chown(path: String, uid: Int, gid: Int): Boolean = write(path) {
        "chown $uid:$gid -- ${PiperPathPolicy.shellQuote(it)}"
    }
    override fun close() = session.close()

    private fun probe(command: String) = runCatching { session.execute(command).exitCode == 0 }.getOrDefault(false)
    private inline fun write(path: String, command: (String) -> String): Boolean {
        val safe = PiperPathPolicy.requireWriteAllowed(path, systemWriteEnabled())
        return session.execute(command(safe), 60_000L).exitCode == 0
    }
}
