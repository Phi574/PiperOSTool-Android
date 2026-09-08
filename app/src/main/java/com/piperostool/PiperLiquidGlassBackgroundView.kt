package com.piperostool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Full-screen color field that gives translucent Classic surfaces visible depth. */
class PiperLiquidGlassBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var animationStart = 0L
    private var phase = 0f
    private val ticker = object : Runnable {
        override fun run() {
            val elapsed = (SystemClock.elapsedRealtime() - animationStart) % CYCLE_MS
            phase = elapsed.toFloat() / CYCLE_MS
            invalidate()
            postDelayed(this, FRAME_DELAY_MS)
        }
    }

    var darkMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationStart == 0L) animationStart = SystemClock.elapsedRealtime()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE && isAttachedToWindow) post(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val drift = kotlin.math.sin(phase * Math.PI * 2.0).toFloat()

        paint.shader = LinearGradient(
            0f,
            0f,
            w,
            h,
            if (darkMode) {
                intArrayOf(Color.rgb(7, 10, 17), Color.rgb(17, 21, 30), Color.rgb(5, 8, 13))
            } else {
                intArrayOf(Color.rgb(232, 243, 252), Color.rgb(247, 239, 249), Color.rgb(226, 244, 242))
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        drawEdgeWash(
            canvas,
            w * (-0.18f + drift * 0.05f),
            h * 0.24f,
            maxOf(w, h) * 1.15f,
            if (darkMode) Color.argb(95, 17, 93, 173) else Color.argb(112, 80, 171, 235)
        )
        drawEdgeWash(
            canvas,
            w * (1.16f - drift * 0.05f),
            h * 0.76f,
            maxOf(w, h) * 1.05f,
            if (darkMode) Color.argb(78, 186, 79, 60) else Color.argb(92, 244, 149, 112)
        )
        paint.shader = LinearGradient(
            -w * 0.2f,
            h * (0.25f + drift * 0.04f),
            w * 1.2f,
            h * (0.78f + drift * 0.04f),
            intArrayOf(Color.TRANSPARENT, Color.argb(if (darkMode) 24 else 40, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawEdgeWash(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x,
            y,
            radius,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private companion object {
        const val CYCLE_MS = 32_000L
        const val FRAME_DELAY_MS = 900L
    }
}
