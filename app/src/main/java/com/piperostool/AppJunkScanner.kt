package com.piperostool

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

data class AppJunkItem(
    val id: String,
    val label: String,
    val detail: String,
    val category: String,
    val bytes: Long,
    val modified: Long,
    val file: File? = null,
    val root: File? = null,
    val legacyValue: String? = null
)

/** Only disposable app-owned files are candidates. User content and browser profiles stay untouched. */
object AppJunkScanner {
    private const val DAY = 24L * 60 * 60 * 1000

    fun scan(context: Context, now: Long = System.currentTimeMillis()): List<AppJunkItem> {
        val app = context.applicationContext
        val result = mutableListOf<AppJunkItem>()
        scanRoot(app.cacheDir, "Bộ nhớ đệm", now - DAY, result)
        app.externalCacheDir?.let { scanRoot(it, "Bộ nhớ đệm ngoài", now - DAY, result) }
        // PPS writes its current log here. Only rotated, old logs are disposable.
        scanRoot(File(app.filesDir, "piperos/logs"), "Nhật ký cũ", now - 30 * DAY, result) {
            it.name != "pps.log" && it.extension == "log"
        }
        val oldVpn = AccountDataScope.preferences(app, "PiperBrowserSession")
            .getString("vpn_package", null)
        if (oldVpn != null) result += AppJunkItem(
            id = "legacy:browser-vpn-package",
            label = "Thiết lập VPN Browser đã gỡ",
            detail = "PiperBrowserSession / vpn_package",
            category = "Thiết lập phiên bản cũ",
            bytes = oldVpn.toByteArray().size.toLong(),
            modified = 0L,
            legacyValue = oldVpn
        )
        return result.sortedWith(compareBy<AppJunkItem> { it.category }.thenByDescending { it.bytes })
    }

    fun delete(context: Context, item: AppJunkItem): Boolean {
        if (item.legacyValue != null) {
            val preferences = AccountDataScope.preferences(context.applicationContext, "PiperBrowserSession")
            if (preferences.getString("vpn_package", null) != item.legacyValue) return false
            return preferences.edit().remove("vpn_package").commit()
        }
        val file = item.file ?: return false
        val root = item.root ?: return false
        if (!isInside(file, root) || Files.isSymbolicLink(file.toPath()) || !file.isFile) return false
        // A service may have reused this path since the scan; never remove a changed file.
        if (file.length() != item.bytes || file.lastModified() != item.modified) return false
        return file.delete()
    }

    private fun scanRoot(
        root: File,
        category: String,
        olderThan: Long,
        result: MutableList<AppJunkItem>,
        include: (File) -> Boolean = { true }
    ) {
        if (!root.isDirectory) return
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            directory.listFiles()?.forEach { file ->
                if (Files.isSymbolicLink(file.toPath()) || !isInside(file, root)) return@forEach
                if (file.isDirectory) {
                    // Runtime extraction can still be in use; its staging files are not junk.
                    if (file.name != "piperos-runtime-install") pending.add(file)
                } else if (file.isFile && file.lastModified() in 1 until olderThan && include(file)) {
                    result += AppJunkItem(
                        id = file.absolutePath,
                        label = file.name,
                        detail = file.relativeTo(root).path,
                        category = category,
                        bytes = file.length(),
                        modified = file.lastModified(),
                        file = file,
                        root = root
                    )
                }
            }
        }
    }

    private fun isInside(file: File, root: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)
}
