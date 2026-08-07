package com.piperostool

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileArchiveEngineInstrumentedTest {
    @Test
    fun createsAndExtractsZipWithAes256Password() {
        val root = testRoot("zip-aes")
        val source = createPayload(root)
        val archive = File(root, "payload.zip")
        val extracted = File(root, "out")

        FileArchiveEngine.compress(
            source,
            archive,
            FileArchiveFormat.ZIP,
            ArchiveCompressionPreset.NORMAL,
            "piper-256",
            { false }
        ) { }
        assertTrue(net.lingala.zip4j.ZipFile(archive).isEncrypted)
        FileArchiveEngine.extract(archive, extracted, "piper-256", { false }) { }

        assertEquals("PiperOS archive test", extracted.walkTopDown().first { it.name == "hello.txt" }.readText())
        root.deleteRecursively()
    }

    @Test
    fun roundTripsSupportedTarAndSevenZFormats() {
        val formats = listOf(
            FileArchiveFormat.SEVEN_Z,
            FileArchiveFormat.TAR,
            FileArchiveFormat.TAR_GZIP,
            FileArchiveFormat.TAR_BZIP2,
            FileArchiveFormat.TAR_XZ,
            FileArchiveFormat.TAR_LZ4,
            FileArchiveFormat.TAR_ZSTD
        )
        formats.forEach { format ->
            val root = testRoot(format.name)
            val source = createPayload(root)
            val archive = File(root, "payload${format.extension}")
            val extracted = File(root, "out")
            FileArchiveEngine.compress(
                source,
                archive,
                format,
                ArchiveCompressionPreset.NORMAL,
                null,
                { false }
            ) { }
            FileArchiveEngine.extract(archive, extracted, null, { false }) { }
            assertEquals(
                "Failed for ${format.name}",
                "PiperOS archive test",
                extracted.walkTopDown().first { it.name == "hello.txt" }.readText()
            )
            root.deleteRecursively()
        }
    }

    private fun testRoot(label: String): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.cacheDir, "archive-engine-$label").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun createPayload(root: File): File = File(root, "payload").apply {
        mkdirs()
        File(this, "nested").mkdirs()
        File(this, "nested/hello.txt").writeText("PiperOS archive test")
        File(this, "numbers.bin").writeBytes(ByteArray(4_096) { (it % 251).toByte() })
    }
}
