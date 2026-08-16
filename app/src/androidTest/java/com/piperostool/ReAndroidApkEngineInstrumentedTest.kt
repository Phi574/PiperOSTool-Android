package com.piperostool

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ReAndroidApkEngineInstrumentedTest {
    @Test
    fun decodesEditsAndRebuildsApkProjectOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .mapNotNull { it.sourceDir?.let(::File) }
            .filter { it.isFile && it.length() > 0L }
            .minByOrNull(File::length)
            ?: File(context.applicationInfo.sourceDir)
        val workspace = ApkWorkspace.createFromPath(context, source)
        try {
            workspace.decodeFullProject(decodeSmali = false) { }
            val manifest = File(workspace.decodedDirectory, "AndroidManifest.xml")
            assertTrue(manifest.isFile)
            assertTrue(manifest.readText().contains("<manifest"))
            val marker = File(workspace.decodedDirectory, "root/assets/piperos-engine-test.txt")
            marker.parentFile?.mkdirs()
            marker.writeText("decoded-project-build")
            val output = PiperApkBuilder(context).build(workspace) { }

            assertTrue(output.isFile)
            ZipFile(output).use { zip ->
                val entry = zip.getEntry("assets/piperos-engine-test.txt")
                assertTrue(entry != null)
                assertTrue(zip.getInputStream(entry).bufferedReader().readText() == "decoded-project-build")
            }
        } finally {
            workspace.root.deleteRecursively()
        }
    }
}
