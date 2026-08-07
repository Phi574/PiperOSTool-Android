package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperUiTextTest {
    @Test
    fun translatesKnownVietnameseLabels() {
        assertEquals("SETTINGS", PiperUiText.translate("CÀI ĐẶT"))
        assertEquals("Application", PiperUiText.translate("Ứng dụng"))
    }

    @Test
    fun translatesDynamicCounts() {
        assertEquals("12 apps", PiperUiText.translate("12 ứng dụng"))
        assertEquals("3 folders · 18 files", PiperUiText.translate("3 thư mục • 18 tệp"))
    }

    @Test
    fun keepsUnknownText() {
        assertEquals("PiperOS", PiperUiText.translate("PiperOS"))
    }
}
