package com.piperostool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRuntimeVersionTest {
    @Test
    fun updateIsOnlyAvailableForAnOlderInstalledRuntime() {
        assertTrue(status("2.5.5-beta").updateAvailable)
        assertFalse(status("2.5.6-beta").updateAvailable)
        assertFalse(status("2.5.7-beta").updateAvailable)
    }

    @Test
    fun missingVersionMetadataRequestsRepair() {
        assertTrue(status(null).updateAvailable)
    }

    private fun status(version: String?) = TerminalRuntime.Status(
        prefixDirectory = File("usr"),
        homeDirectory = File("home"),
        shellExecutable = File("usr/bin/bash"),
        installedVersion = version
    )
}
