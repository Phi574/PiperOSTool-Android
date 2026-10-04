package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test fun releaseOrderingIncludesPreviewBuilds() {
        val older = AppUpdateRepository.version("3.4.3")!!
        val preview = AppUpdateRepository.version("v3.4.5.PRE")!!
        val stable = AppUpdateRepository.version("3.4.5")!!
        assertTrue(preview > older)
        assertTrue(stable > preview)
        assertEquals(preview, AppUpdateRepository.version("3.4.5.pre"))
    }
}
