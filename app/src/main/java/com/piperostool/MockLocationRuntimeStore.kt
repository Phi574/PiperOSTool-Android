package com.piperostool

import android.content.Context

data class MockLocationCheckpoint(
    val active: Boolean,
    val distanceMeters: Double,
    val forward: Boolean,
    val paused: Boolean,
    val arrived: Boolean,
    val point: RoutePoint?
)

object MockLocationRuntimeStore {
    private const val PREFERENCES = "PiperMockLocationRuntime"
    private const val KEY_ACTIVE = "active"
    private const val KEY_DISTANCE = "distance"
    private const val KEY_FORWARD = "forward"
    private const val KEY_PAUSED = "paused"
    private const val KEY_ARRIVED = "arrived"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_HAS_POINT = "hasPoint"

    fun save(context: Context, checkpoint: MockLocationCheckpoint) {
        preferences(context).edit()
            .putBoolean(KEY_ACTIVE, checkpoint.active)
            .putLong(KEY_DISTANCE, checkpoint.distanceMeters.toBits())
            .putBoolean(KEY_FORWARD, checkpoint.forward)
            .putBoolean(KEY_PAUSED, checkpoint.paused)
            .putBoolean(KEY_ARRIVED, checkpoint.arrived)
            .putBoolean(KEY_HAS_POINT, checkpoint.point != null)
            .apply {
                checkpoint.point?.let { point ->
                    putLong(KEY_LATITUDE, point.latitude.toBits())
                    putLong(KEY_LONGITUDE, point.longitude.toBits())
                }
            }
            .apply()
    }

    fun load(context: Context): MockLocationCheckpoint? {
        val preferences = preferences(context)
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null
        val point = if (preferences.getBoolean(KEY_HAS_POINT, false)) {
            RoutePoint(
                Double.fromBits(preferences.getLong(KEY_LATITUDE, 0L)),
                Double.fromBits(preferences.getLong(KEY_LONGITUDE, 0L))
            )
        } else {
            null
        }
        return MockLocationCheckpoint(
            active = true,
            distanceMeters = Double.fromBits(preferences.getLong(KEY_DISTANCE, 0L)),
            forward = preferences.getBoolean(KEY_FORWARD, true),
            paused = preferences.getBoolean(KEY_PAUSED, false),
            arrived = preferences.getBoolean(KEY_ARRIVED, false),
            point = point
        )
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) =
        AccountDataScope.preferences(context, PREFERENCES)

    fun clearRawAccount(context: Context, previousAccount: String?) {
        if (previousAccount == null) return
        val name = "PiperAccount_${previousAccount}_$PREFERENCES"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
