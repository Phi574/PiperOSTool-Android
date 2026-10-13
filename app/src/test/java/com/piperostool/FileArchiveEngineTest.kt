package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileArchiveEngineTest {
    @Test
    fun zipRoundTripPreservesNestedFilesAndRelativeRoot() {
        val root = Files.createTempDirectory("piper-archive-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "nested/value.txt").apply { parentFile?.mkdirs(); writeText("hello") }
            val archive = File(root, "result.zip")
            FileArchiveEngine.compress(
                source, archive, FileArchiveFormat.ZIP, ArchiveCompressionPreset.NORMAL,
                null, { false }, {}
            )
            assertTrue(archive.isFile)
            val output = File(root, "unpacked")
            FileArchiveEngine.extract(archive, output, null, { false }, {})
            assertEquals("hello", File(output, "source/nested/value.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveOutputInsideSourceDirectoryAndLeavesNoPartialFile() {
        val root = Files.createTempDirectory("piper-archive-guard").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "value.txt").writeText("hello")
            val archive = File(source, "nested/result.zip")
            assertThrows(IllegalArgumentException::class.java) {
                FileArchiveEngine.compress(
                    source, archive, FileArchiveFormat.ZIP, ArchiveCompressionPreset.NORMAL,
                    null, { false }, {}
                )
            }
            assertFalse(archive.exists())
            assertFalse(source.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        } finally {
            root.deleteRecursively()
        }
    }
}
