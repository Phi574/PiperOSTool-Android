package com.piperostool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.os.SystemClock

/** A quiet, slowly moving color wash used behind the modern interface. */
class PiperAmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animationStart = 0L
    private val animationTick = object : Runnable {
        override fun run() {
            val elapsed = (SystemClock.elapsedRealtime() - animationStart) % CYCLE_DURATION_MS
            val normalized = elapsed.toFloat() / HALF_CYCLE_MS
            phase = if (normalized <= 1f) normalized else 2f - normalized
            invalidate()
            postDelayed(this, FRAME_DELAY_MS)
        }
    }

    var darkMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationTick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnimation() else removeCallbacks(animationTick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnimation() else removeCallbacks(animationTick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat().coerceAtLeast(1f)
        val height = height.toFloat().coerceAtLeast(1f)
        canvas.drawColor(if (darkMode) DARK_BASE else LIGHT_BASE)

        val radius = maxOf(width, height) * 1.08f
        drawWash(
            canvas,
            width * (-0.10f + phase * 0.24f),
            height * (0.02f + phase * 0.12f),
            radius,
            if (darkMode) DARK_BLUE else LIGHT_BLUE
        )
        drawWash(
            canvas,
            width * (1.08f - phase * 0.18f),
            height * (0.72f + phase * 0.18f),
            radius * 0.92f,
            if (darkMode) DARK_PINK else LIGHT_PINK
        )
    }

    private fun drawWash(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x,
            y,
            radius,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun startAnimation() {
        removeCallbacks(animationTick)
        if (animationStart == 0L) animationStart = SystemClock.elapsedRealtime()
        post(animationTick)
    }

    private companion object {
        val LIGHT_BASE = Color.rgb(248, 248, 246)
        val LIGHT_BLUE = Color.argb(78, 174, 212, 255)
        val LIGHT_PINK = Color.argb(64, 255, 194, 219)
        val DARK_BASE = Color.rgb(18, 18, 22)
        val DARK_BLUE = Color.argb(60, 57, 96, 158)
        val DARK_PINK = Color.argb(50, 139, 66, 105)
        const val HALF_CYCLE_MS = 18_000L
        const val CYCLE_DURATION_MS = HALF_CYCLE_MS * 2L
        const val FRAME_DELAY_MS = 750L
    }
}
