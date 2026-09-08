package com.piperostool

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object AuthScreenUi {
    fun apply(
        activity: AppCompatActivity,
        root: View,
        classicBackground: View? = null,
        classicOverlay: View? = null
    ) {
        // Authentication screens use the same transparent LiquidGlass shell
        // in every app theme so login forms never turn into opaque white cards.
        classicBackground?.visibility = View.VISIBLE
        classicOverlay?.visibility = View.GONE

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        PiperClassicGlassUi.watch(activity)

        root.alpha = 0f
        root.translationY = 8f * activity.resources.displayMetrics.density
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }
}
