package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperRemoteGestureCoordinatesTest {
    @Test fun mapsNormalizedCoordinatesToDisplayPixelsAndKeepsEdgesInsideScreen() {
        assertEquals(0f to 0f, PiperRemoteGestureCoordinates.toScreen(0f to 0f, 1080, 2400))
        assertEquals(1079f to 2399f, PiperRemoteGestureCoordinates.toScreen(1f to 1f, 1080, 2400))
        assertEquals(0f to 2399f, PiperRemoteGestureCoordinates.toScreen(-1f to 2f, 1080, 2400))
    }
}
