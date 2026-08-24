package com.piperostool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PiperRemoteFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var frame: Bitmap? = null
    var touchListener: ((Int, Float, Float) -> Unit)? = null
    private var lastMoveAt = 0L

    fun setFrame(bitmap: Bitmap) {
        val previous = frame
        frame = bitmap
        if (previous !== bitmap) previous?.recycle()
        if (width == 0 || height == 0) requestLayout()
        invalidate()
    }

    fun clearFrame() {
        frame?.recycle()
        frame = null
        destination.setEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8, 11, 14))
        val bitmap = frame ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        destination.set((width - drawWidth) / 2f, (height - drawHeight) / 2f, (width + drawWidth) / 2f, (height + drawHeight) / 2f)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        destination.setEmpty()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frame == null || !destination.contains(event.x, event.y)) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMoveAt < 16L) return true
                lastMoveAt = now
                2
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                1
            }
            MotionEvent.ACTION_CANCEL -> 1
            else -> return true
        }
        val x = ((event.x - destination.left) / destination.width()).coerceIn(0f, 1f)
        val y = ((event.y - destination.top) / destination.height()).coerceIn(0f, 1f)
        touchListener?.invoke(action, x, y)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        clearFrame()
        super.onDetachedFromWindow()
    }
}
