package com.piperostool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class PiperRemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
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

        fun gesture(points: List<Pair<Float, Float>>, width: Int, height: Int) {
            val service = instance ?: return
            if (points.isEmpty()) return
            val path = Path().apply {
                moveTo(points.first().first * width, points.first().second * height)
                points.drop(1).forEach { (x, y) -> lineTo(x * width, y * height) }
            }
            val start = points.first()
            val end = points.last()
            val distance = kotlin.math.abs(end.first - start.first) + kotlin.math.abs(end.second - start.second)
            val duration = if (distance < 0.015f) 65L else (90L + points.size * 8L).coerceAtMost(650L)
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
                null,
                null
            )
        }

        fun back() { instance?.performGlobalAction(GLOBAL_ACTION_BACK) }
        fun home() { instance?.performGlobalAction(GLOBAL_ACTION_HOME) }
    }
}
