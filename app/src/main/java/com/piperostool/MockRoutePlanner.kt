package com.piperostool

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PlannedMockRoute(
    val points: List<RoutePoint>,
    val usedFallback: Boolean
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
            requestRoadRoute(start, end)
        }.getOrElse {
            PlannedMockRoute(listOf(start, end), usedFallback = true)
        }
    }

    private fun requestRoadRoute(
        start: RoutePoint,
        end: RoutePoint
    ): PlannedMockRoute {
        val coordinates =
            "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/$coordinates" +
                "?overview=full&geometries=geojson&steps=false"
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
            val coordinatesJson = root
                .getJSONArray("routes")
                .getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            val points = buildList {
                for (index in 0 until coordinatesJson.length()) {
                    val coordinate = coordinatesJson.getJSONArray(index)
                    add(
                        RoutePoint(
                            latitude = coordinate.getDouble(1),
                            longitude = coordinate.getDouble(0)
                        )
                    )
                }
            }
            check(points.size >= 2) { "Route geometry is empty" }
            return PlannedMockRoute(points, usedFallback = false)
        } finally {
            connection.disconnect()
        }
    }
}
