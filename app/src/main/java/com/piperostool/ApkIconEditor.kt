package com.piperostool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object ApkIconEditor {
    fun replace(context: Context, workspace: ApkWorkspace, source: Uri): File {
        require(workspace.hasDecodedProject) { "Hãy giải mã project đầy đủ trước khi đổi icon" }
        val bitmap = context.contentResolver.openInputStream(source)?.use(BitmapFactory::decodeStream)
            ?: error("Không đọc được ảnh đã chọn")
        val manifest = File(workspace.decodedDirectory, "AndroidManifest.xml")
        val manifestText = manifest.readText()
        val iconReference = Regex("android:icon\\s*=\\s*[\"']@([a-zA-Z0-9_]+)/([a-zA-Z0-9_]+)[\"']")
            .find(manifestText)
            ?.let { it.groupValues[1] to it.groupValues[2] }
            ?: error("Manifest không khai báo icon dạng resource")
        val targets = linkedSetOf<File>()
        collectRasterTargets(workspace.decodedDirectory, iconReference, targets, mutableSetOf())
        if (targets.isEmpty()) {
            bitmap.recycle()
            error("Icon APK này chỉ dùng vector/XML và không có lớp ảnh raster để thay")
        }
        targets.forEach { target ->
            EditorHistoryStore.capture(context, target)
            writeBitmap(bitmap, target)
        }
        bitmap.recycle()
        return targets.first()
    }

    private fun collectRasterTargets(
        project: File,
        reference: Pair<String, String>,
        output: MutableSet<File>,
        visited: MutableSet<Pair<String, String>>
    ) {
        if (!visited.add(reference)) return
        val (type, name) = reference
        val res = File(project, "res")
        res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.substringBefore('-') == type }
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.nameWithoutExtension == name }
            .forEach { file ->
                if (file.extension.lowercase() in RASTER_EXTENSIONS) {
                    output += file
                } else if (file.extension.equals("xml", ignoreCase = true)) {
                    Regex("@([a-zA-Z0-9_]+)/([a-zA-Z0-9_]+)")
                        .findAll(file.readText())
                        .forEach { match ->
                            collectRasterTargets(
                                project,
                                match.groupValues[1] to match.groupValues[2],
                                output,
                                visited
                            )
                        }
                }
            }
    }

    private fun writeBitmap(source: Bitmap, target: File) {
        val old = BitmapFactory.decodeFile(target.absolutePath)
        val width = old?.width?.takeIf { it > 0 } ?: source.width.coerceAtMost(1024)
        val height = old?.height?.takeIf { it > 0 } ?: source.height.coerceAtMost(1024)
        old?.recycle()
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val format = when (target.extension.lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.PNG
        }
        target.outputStream().use { output ->
            check(scaled.compress(format, 100, output)) { "Không thể ghi ${target.name}" }
        }
        scaled.recycle()
    }

    private val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
}
