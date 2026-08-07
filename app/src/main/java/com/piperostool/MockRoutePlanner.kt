package com.piperostool

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PlannedMockRoute(
    val points: List<RoutePoint>,
    val usedFallback: Boolean,
    val distanceMeters: Double = runCatching { RouteProgressor(points).totalDistanceMeters }.getOrDefault(0.0)
)

object MockRoutePlanner {
    fun plan(
        start: RoutePoint,
        end: RoutePoint,
        mode: MockTravelMode
    ): PlannedMockRoute {
        if (mode == MockTravelMode.PLANE) {
            return PlannedMockRoute(listOf(start, end), usedFallback = false)
        }
        return runCatching {
            requestRoadRoutes(listOf(start, end)).first()
        }.getOrElse {
            PlannedMockRoute(listOf(start, end), usedFallback = true)
        }
    }

    fun planAlternatives(
        controlPoints: List<RoutePoint>,
        mode: MockTravelMode
    ): List<PlannedMockRoute> {
        require(controlPoints.size >= 2) { "Cần điểm đầu và điểm cuối" }
        if (mode == MockTravelMode.PLANE) {
            return listOf(PlannedMockRoute(controlPoints, usedFallback = false))
        }
        return runCatching { requestRoadRoutes(controlPoints) }
            .getOrElse { listOf(PlannedMockRoute(controlPoints, usedFallback = true)) }
    }

    private fun requestRoadRoutes(controlPoints: List<RoutePoint>): List<PlannedMockRoute> {
        val coordinates = controlPoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/$coordinates" +
                "?overview=full&geometries=geojson&steps=false&alternatives=3"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PiperOSTool-Android")
        }
        try {
            check(connection.responseCode in 200..299) {
                "Route server returned HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            check(root.optString("code") == "Ok") { "No route found" }
            val routes = root.getJSONArray("routes")
            return buildList {
                for (routeIndex in 0 until routes.length()) {
                    val route = routes.getJSONObject(routeIndex)
                    val coordinatesJson = route.getJSONObject("geometry").getJSONArray("coordinates")
                    val points = buildList {
                        for (index in 0 until coordinatesJson.length()) {
                            val coordinate = coordinatesJson.getJSONArray(index)
                            add(RoutePoint(coordinate.getDouble(1), coordinate.getDouble(0)))
                        }
                    }
                    if (points.size >= 2) {
                        add(
                            PlannedMockRoute(
                                points = points,
                                usedFallback = false,
                                distanceMeters = route.optDouble("distance", RouteProgressor(points).totalDistanceMeters)
                            )
                        )
                    }
                }
            }
                .also { check(it.isNotEmpty()) { "Route geometry is empty" } }
        } finally {
            connection.disconnect()
        }
    }
}
