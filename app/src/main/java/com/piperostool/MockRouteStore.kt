package com.piperostool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MockRouteStore {
    private const val PREFERENCES = "PiperMockLocation"
    private const val KEY_SCENARIO = "scenario"

    fun save(context: Context, scenario: MockScenario) {
        val points = JSONArray()
        scenario.points.forEach { point ->
            points.put(
                JSONObject()
                    .put("lat", point.latitude)
                    .put("lon", point.longitude)
            )
        }
        val value = JSONObject()
            .put("mode", scenario.mode.name)
            .put("travelMode", scenario.travelMode.name)
            .put("speedKmh", scenario.speedKmh)
            .put("naturalStops", scenario.naturalStops)
            .put("loop", scenario.loop)
            .put("points", points)
        preferences(context).edit().putString(KEY_SCENARIO, value.toString()).apply()
    }

    fun load(context: Context): MockScenario? {
        val raw = preferences(context).getString(KEY_SCENARIO, null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            val pointsJson = value.getJSONArray("points")
            val points = buildList {
                for (index in 0 until pointsJson.length()) {
                    val point = pointsJson.getJSONObject(index)
                    add(RoutePoint(point.getDouble("lat"), point.getDouble("lon")))
                }
            }
            MockScenario(
                mode = MockScenarioMode.valueOf(value.getString("mode")),
                travelMode = MockTravelMode.valueOf(value.getString("travelMode")),
                speedKmh = value.getDouble("speedKmh"),
                naturalStops = value.optBoolean("naturalStops"),
                loop = value.optBoolean("loop"),
                points = points
            )
        }.getOrNull()?.takeIf { it.points.isNotEmpty() }
    }

    private fun preferences(context: Context) =
        AccountDataScope.preferences(context, PREFERENCES)
}
