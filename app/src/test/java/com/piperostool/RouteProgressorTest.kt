package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressorTest {
    @Test
    fun halfwayDistanceProducesHalfwayCoordinate() {
        val route = RouteProgressor(
            listOf(
                RoutePoint(21.0, 105.0),
                RoutePoint(21.0, 106.0)
            )
        )

        val position = route.positionAt(route.totalDistanceMeters / 2.0)

        assertEquals(21.0, position.point.latitude, 0.00001)
        assertEquals(105.5, position.point.longitude, 0.00001)
        assertEquals(0.5, position.fraction, 0.001)
        assertFalse(position.arrived)
    }

    @Test
    fun distancePastRouteStopsAtDestination() {
        val destination = RoutePoint(10.8, 106.7)
        val route = RouteProgressor(
            listOf(
                RoutePoint(10.7, 106.6),
                destination
            )
        )

        val position = route.positionAt(route.totalDistanceMeters * 2.0)

        assertEquals(destination, position.point)
        assertEquals(1.0, position.fraction, 0.0)
        assertTrue(position.arrived)
    }

    @Test
    fun singlePointRouteIsACompletedFixedLocation() {
        val point = RoutePoint(16.0544, 108.2022)
        val position = RouteProgressor(listOf(point)).positionAt(0.0)

        assertEquals(point, position.point)
        assertTrue(position.arrived)
    }
}
