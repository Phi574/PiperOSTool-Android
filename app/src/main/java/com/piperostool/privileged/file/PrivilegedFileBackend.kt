package com.piperostool.privileged.file

import android.os.ParcelFileDescriptor
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperFileEntry
import com.piperostool.privileged.PiperPrivilege

interface PrivilegedFileBackend : AutoCloseable {
    val privilege: PiperPrivilege
    fun capabilities(): PiperCapabilities
    fun list(path: String, showHidden: Boolean): Sequence<PiperFileEntry>
    fun stat(path: String): PiperFileEntry?
    fun openRead(path: String): ParcelFileDescriptor?
    fun mkdir(path: String): Boolean
    fun rename(source: String, destination: String): Boolean
    fun delete(path: String, recursive: Boolean): Boolean
    fun chmod(path: String, mode: Int): Boolean
    fun chown(path: String, uid: Int, gid: Int): Boolean
    override fun close() = Unit
}
