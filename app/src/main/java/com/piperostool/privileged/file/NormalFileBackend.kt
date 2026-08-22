package com.piperostool.privileged.file

import android.os.ParcelFileDescriptor
import android.system.Os
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperPathPolicy
import com.piperostool.privileged.PiperPrivilege
import java.io.File

class NormalFileBackend : PrivilegedFileBackend {
    override val privilege = PiperPrivilege.STANDARD

    override fun capabilities() = PiperCapabilities()

    override fun list(path: String, showHidden: Boolean): Sequence<PiperFileEntry> =
        File(PiperPathPolicy.canonical(path)).listFiles().orEmpty().asSequence()
            .filter { showHidden || !it.name.startsWith('.') }
            .map(::entry)

    override fun stat(path: String): PiperFileEntry? = File(PiperPathPolicy.canonical(path))
        .takeIf { it.exists() || runCatching { Os.lstat(it.path) }.isSuccess }
        ?.let(::entry)

    override fun openRead(path: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(File(PiperPathPolicy.canonical(path)), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    override fun mkdir(path: String) = File(PiperPathPolicy.canonical(path)).mkdir()
    override fun rename(source: String, destination: String) =
        File(PiperPathPolicy.canonical(source)).renameTo(File(PiperPathPolicy.canonical(destination)))
    override fun delete(path: String, recursive: Boolean): Boolean {
        val file = File(PiperPathPolicy.canonical(path))
        return if (recursive && file.isDirectory) file.deleteRecursively() else file.delete()
    }
    override fun chmod(path: String, mode: Int) = runCatching {
        Os.chmod(PiperPathPolicy.canonical(path), mode)
        true
    }.getOrDefault(false)
    override fun chown(path: String, uid: Int, gid: Int) = runCatching {
        Os.chown(PiperPathPolicy.canonical(path), uid, gid)
        true
    }.getOrDefault(false)

    private fun entry(file: File): PiperFileEntry {
        val stat = runCatching { Os.lstat(file.path) }.getOrNull()
        return PiperFileEntry(
            name = file.name.ifEmpty { "/" },
            path = file.absolutePath,
            directory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            modifiedAt = file.lastModified(),
            mode = stat?.st_mode?.toString(8).orEmpty(),
            uid = stat?.st_uid ?: -1,
            gid = stat?.st_gid ?: -1,
            symlinkTarget = runCatching { if (Os.readlink(file.path) != file.path) Os.readlink(file.path) else null }.getOrNull()
        )
    }
}
