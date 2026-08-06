package com.piperostool

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ApkWorkspaceExportInstrumentedTest {
    @Test
    fun exportsSelectedFileAndFolderWithoutFlatteningPaths() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "workspace-export-source.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            mapOf(
                "AndroidManifest.xml" to "manifest",
                "assets/banner.txt" to "banner",
                "assets/nested/message.txt" to "hello",
                "classes.dex" to "dex"
            ).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        val destination = File(context.cacheDir, "workspace-export-result")
            .apply { deleteRecursively(); mkdirs() }
        val workspace = ApkWorkspace.createFromPath(context, source)

        val result = ApkBackupEngine(
            context,
            workspace,
            DocumentFile.fromFile(destination)
        ).export(
            listOf("assets", "classes.dex"),
        )

        assertEquals(3, result.fileCount)
        val assetsBackup = destination.listFiles()?.single { it.isDirectory && it.name.startsWith("assets") }
        assertEquals("banner", File(assetsBackup, "banner.txt").readText())
        assertEquals("hello", File(assetsBackup, "nested/message.txt").readText())
        assertTrue(File(destination, "classes.dex").isFile)
        workspace.root.deleteRecursively()
        source.delete()
        destination.deleteRecursively()
    }

    @Test
    fun exportsTwoThousandSmallFilesWithoutReopeningApkPerFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "workspace-export-many.apk")
        ZipOutputStream(source.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("manifest".toByteArray())
            zip.closeEntry()
            repeat(2_000) { index ->
                zip.putNextEntry(ZipEntry("assets/data/group-${index % 20}/item-$index.txt"))
                zip.write("value-$index".toByteArray())
                zip.closeEntry()
            }
        }
        val destination = File(context.cacheDir, "workspace-export-many-result")
            .apply { deleteRecursively(); mkdirs() }
        val workspace = ApkWorkspace.createFromPath(context, source)
        val started = android.os.SystemClock.elapsedRealtime()

        val result = ApkBackupEngine(context, workspace, DocumentFile.fromFile(destination))
            .export(listOf("assets"))

        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertEquals(2_000, result.fileCount)
        assertEquals("value-1999", destination.walkTopDown().first { it.name == "item-1999.txt" }.readText())
        assertTrue("Backup took ${elapsed}ms", elapsed < 90_000L)
        workspace.root.deleteRecursively()
        source.delete()
        destination.deleteRecursively()
    }
}
