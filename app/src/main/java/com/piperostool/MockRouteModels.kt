package com.piperostool

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MockScenarioMode {
    FIXED,
    ROUTE
}

enum class MockTravelMode(
    val defaultSpeedKmh: Double,
    val maximumSpeedKmh: Int,
    val displayName: String
) {
    WALK(5.0, 20, "Đi bộ"),
    MOTORBIKE(35.0, 180, "Xe máy"),
    CAR(50.0, 240, "Ô tô"),
    PLANE(800.0, 1_200, "Máy bay")
}

data class RoutePoint(
    val latitude: Double,
    val longitude: Double
)

data class MockScenario(
    val mode: MockScenarioMode,
    val travelMode: MockTravelMode,
    val speedKmh: Double,
    val naturalStops: Boolean,
    val loop: Boolean,
    val points: List<RoutePoint>
)

data class RoutePosition(
    val point: RoutePoint,
    val bearing: Float,
    val fraction: Double,
    val arrived: Boolean
)

class RouteProgressor(private val points: List<RoutePoint>) {
    private val cumulativeDistances = ArrayList<Double>(points.size)

    val totalDistanceMeters: Double

    init {
        require(points.isNotEmpty()) { "A route needs at least one point." }
        var distance = 0.0
        cumulativeDistances += 0.0
        for (index in 1 until points.size) {
            distance += distanceMeters(points[index - 1], points[index])
            cumulativeDistances += distance
        }
        totalDistanceMeters = distance
    }

    fun positionAt(distanceMeters: Double): RoutePosition {
        if (points.size == 1 || totalDistanceMeters <= 0.0) {
            return RoutePosition(points.first(), 0f, 1.0, true)
        }

        val clamped = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val segmentEnd = cumulativeDistances.indexOfFirst { it >= clamped }
            .coerceAtLeast(1)
        val start = points[segmentEnd - 1]
        val end = points[segmentEnd]
        val segmentStartDistance = cumulativeDistances[segmentEnd - 1]
        val segmentLength = cumulativeDistances[segmentEnd] - segmentStartDistance
        val segmentFraction = if (segmentLength > 0.0) {
            (clamped - segmentStartDistance) / segmentLength
        } else {
            0.0
        }
        return RoutePosition(
            point = RoutePoint(
                latitude = start.latitude + (end.latitude - start.latitude) * segmentFraction,
                longitude = start.longitude + (end.longitude - start.longitude) * segmentFraction
            ),
            bearing = bearingDegrees(start, end),
            fraction = clamped / totalDistanceMeters,
            arrived = clamped >= totalDistanceMeters
        )
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        fun distanceMeters(start: RoutePoint, end: RoutePoint): Double {
            val startLat = Math.toRadians(start.latitude)
            val endLat = Math.toRadians(end.latitude)
            val deltaLat = endLat - startLat
            val deltaLon = Math.toRadians(end.longitude - start.longitude)
            val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(startLat) * cos(endLat) *
                sin(deltaLon / 2) * sin(deltaLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        fun bearingDegrees(start: RoutePoint, end: RoutePoint): Float {
            val startLat = Math.toRadians(start.latitude)
            val endLat = Math.toRadians(end.latitude)
            val deltaLon = Math.toRadians(end.longitude - start.longitude)
            val y = sin(deltaLon) * cos(endLat)
            val x = cos(startLat) * sin(endLat) -
                sin(startLat) * cos(endLat) * cos(deltaLon)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }
    }
}
