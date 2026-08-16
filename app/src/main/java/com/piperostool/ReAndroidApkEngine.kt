package com.piperostool

import com.reandroid.apkeditor.Main
import java.io.File

/** On-device APK resource/smali engine. It does not require a native aapt2 binary. */
object ReAndroidApkEngine {
    fun decode(sourceApk: File, outputDirectory: File, decodeSmali: Boolean): File {
        require(sourceApk.isFile) { "APK nguồn không tồn tại" }
        outputDirectory.parentFile?.mkdirs()
        try {
            val arguments = mutableListOf(
                    "d",
                    "-t", "xml",
                    "-i", sourceApk.absolutePath,
                    "-o", outputDirectory.absolutePath,
                    "-f",
                    "-load-dex", "1",
                    "-dex-lib", "internal",
                    "-no-cache"
                )
            if (!decodeSmali) arguments += "-dex"
            val result = Main.execute(arguments.toTypedArray())
            check(result == 0 && File(outputDirectory, "AndroidManifest.xml").isFile) {
                "Engine giải mã APK thất bại (mã $result)"
            }
            return outputDirectory
        } catch (error: Throwable) {
            outputDirectory.deleteRecursively()
            if (error is OutOfMemoryError) {
                throw IllegalStateException(
                    "APK quá lớn so với bộ nhớ khả dụng. Hãy đóng ứng dụng nền rồi thử lại.",
                    error
                )
            }
            throw error
        }
    }

    fun build(projectDirectory: File, outputApk: File): File {
        require(File(projectDirectory, "AndroidManifest.xml").isFile) {
            "Workspace chưa có AndroidManifest.xml đã giải mã"
        }
        outputApk.parentFile?.mkdirs()
        val result = Main.execute(
            arrayOf(
                "b",
                "-t", "xml",
                "-i", projectDirectory.absolutePath,
                "-o", outputApk.absolutePath,
                "-f",
                "-dex-lib", "internal",
                "-no-cache"
            )
        )
        check(result == 0 && outputApk.isFile && outputApk.length() > 0L) {
            "Engine đóng gói APK thất bại (mã $result)"
        }
        return outputApk
    }
}
