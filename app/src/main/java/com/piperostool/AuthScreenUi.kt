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
        val modern = PiperUiPreferences.isModern(activity)
        classicBackground?.visibility = if (modern) View.GONE else View.VISIBLE
        classicOverlay?.visibility = if (modern) View.GONE else View.VISIBLE

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
    }
}
