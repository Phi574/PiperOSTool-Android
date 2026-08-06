package com.piperostool

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.apksig.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PiperApkBuilderInstrumentedTest {
    @Test
    fun rebuildsAndSignsInstalledApk() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workspace = ApkWorkspace.createFromPath(
            context,
            File(context.applicationInfo.sourceDir)
        )
        val marker = File(workspace.filesDirectory, "assets/piperos-editor-test.txt")
        marker.parentFile?.mkdirs()
        marker.writeText("PiperOS APK Editor")

        val output = PiperApkBuilder(context).build(workspace) { }

        assertTrue(output.isFile)
        ZipFile(output).use { zip ->
            assertEquals(
                "PiperOS APK Editor",
                zip.getInputStream(zip.getEntry("assets/piperos-editor-test.txt"))
                    .bufferedReader()
                    .readText()
            )
        }
        val verification = ApkVerifier.Builder(output).build().verify()
        assertTrue(verification.errors.joinToString(), verification.isVerified)
        workspace.root.deleteRecursively()
    }
}
