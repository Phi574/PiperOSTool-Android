package com.piperostool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockLocationServiceInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun stopService() {
        MockLocationService.sendAction(context, MockLocationService.ACTION_STOP)
    }

    @Test
    fun fixedScenarioIsPublishedWhenMockAppIsSelected() {
        assumeTrue(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
        assumeTrue(MockLocationService.isMockLocationEnabled(context))
        val expected = RoutePoint(21.0278, 105.8342)
        MockRouteStore.save(
            context,
            MockScenario(
                mode = MockScenarioMode.FIXED,
                travelMode = MockTravelMode.WALK,
                speedKmh = 5.0,
                naturalStops = false,
                loop = false,
                points = listOf(expected)
            )
        )

        MockLocationService.start(context)
        waitUntil { MockLocationService.snapshot.point != null }

        val state = MockLocationService.snapshot
        assertTrue(state.running)
        assertEquals(expected.latitude, state.point?.latitude ?: 0.0, 0.00001)
        assertEquals(expected.longitude, state.point?.longitude ?: 0.0, 0.00001)
    }

    private fun waitUntil(
        timeoutMs: Long = 8_000L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(100L)
        }
        assertTrue("Timed out waiting for mock location service", condition())
    }
}
