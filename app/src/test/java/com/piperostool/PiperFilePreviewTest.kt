package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperFilePreviewTest {
    @Test
    fun recognizesSupportedApkAssetFormats() {
        mapOf(
            "image.png" to FilePreviewKind.IMAGE,
            "animation.GIF" to FilePreviewKind.IMAGE,
            "movie.mp4" to FilePreviewKind.VIDEO,
            "sound.flac" to FilePreviewKind.AUDIO,
            "document.pdf" to FilePreviewKind.PDF,
            "strings.xml" to FilePreviewKind.TEXT,
            "classes.dex" to FilePreviewKind.UNSUPPORTED
        ).forEach { (name, expected) ->
            assertEquals(expected, PiperFilePreviewActivity.previewKind(name))
        }
    }
}
