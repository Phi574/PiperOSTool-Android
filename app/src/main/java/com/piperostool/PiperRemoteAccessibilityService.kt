package com.piperostool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class PiperRemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        // Keep the XML-declared minimal event subscription. Some OEM builds disconnect
        // gesture-only services after eventTypes is reset to zero at runtime.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: PiperRemoteAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun gesture(points: List<Pair<Float, Float>>, width: Int, height: Int): Boolean {
            val service = instance ?: return false
            if (points.isEmpty() || width <= 0 || height <= 0) return false
            val path = Path().apply {
                val start = PiperRemoteGestureCoordinates.toScreen(points.first(), width, height)
                moveTo(start.first, start.second)
                points.drop(1).forEach { point ->
                    val mapped = PiperRemoteGestureCoordinates.toScreen(point, width, height)
                    lineTo(mapped.first, mapped.second)
                }
            }
            val start = points.first()
            val end = points.last()
            val distance = kotlin.math.abs(end.first - start.first) + kotlin.math.abs(end.second - start.second)
            val duration = if (distance < 0.015f) 65L else (90L + points.size * 8L).coerceAtMost(650L)
            return service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
                null,
                null
            )
        }

        fun back() { instance?.performGlobalAction(GLOBAL_ACTION_BACK) }
        fun home() { instance?.performGlobalAction(GLOBAL_ACTION_HOME) }
    }
}

internal object PiperRemoteGestureCoordinates {
    fun toScreen(point: Pair<Float, Float>, width: Int, height: Int): Pair<Float, Float> =
        point.first.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0) to
            point.second.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)
}
