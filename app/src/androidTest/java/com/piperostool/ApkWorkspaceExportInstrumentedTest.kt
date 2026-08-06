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

        val count = workspace.exportSelection(
            listOf("assets", "classes.dex"),
            DocumentFile.fromFile(destination),
            context
        ) { }

        assertEquals(3, count)
        val assetsBackup = destination.listFiles()?.single { it.isDirectory && it.name.startsWith("assets") }
        assertEquals("banner", File(assetsBackup, "banner.txt").readText())
        assertEquals("hello", File(assetsBackup, "nested/message.txt").readText())
        assertTrue(File(destination, "classes.dex").isFile)
        workspace.root.deleteRecursively()
        source.delete()
        destination.deleteRecursively()
    }
}
