package com.piperostool

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

internal object PiperGlassBackdropStore {
    data class Backdrop(val bitmap: Bitmap, val windowWidth: Int, val windowHeight: Int)

    private val entries = WeakHashMap<Activity, Backdrop>()

    fun capture(activity: Activity, customBackground: ImageView?) {
        val decor = activity.window.decorView
        decor.post {
            val windowWidth = decor.width.coerceAtLeast(1)
            val windowHeight = decor.height.coerceAtLeast(1)
            val scale = BACKDROP_SCALE
            val bitmapWidth = (windowWidth * scale).toInt().coerceAtLeast(1)
            val bitmapHeight = (windowHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (customBackground?.drawable != null) {
                drawCenterCrop(customBackground, canvas, bitmapWidth, bitmapHeight)
                canvas.drawColor(Color.argb(82, 2, 6, 12))
            } else {
                PiperLiquidGlassBackgroundView(activity).apply {
                    val authScreen = activity is WelcomeActivity || activity is LoginActivity ||
                        activity is SignupActivity || activity is ForgotPassword
                    darkMode = authScreen || (activity.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                    layout(0, 0, bitmapWidth, bitmapHeight)
                    draw(canvas)
                }
            }
            synchronized(entries) {
                entries.put(activity, Backdrop(bitmap, windowWidth, windowHeight))?.bitmap?.recycle()
            }
            decor.invalidate()
        }
    }

    fun forView(view: View): Backdrop? {
        val activity = activityFrom(view.context) ?: return null
        return synchronized(entries) { entries[activity] }
    }

    private fun drawCenterCrop(image: ImageView, canvas: Canvas, width: Int, height: Int) {
        val source = image.drawable.constantState?.newDrawable()?.mutate() ?: image.drawable
        val sourceWidth = source.intrinsicWidth.takeIf { it > 0 } ?: width
        val sourceHeight = source.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val drawWidth = (sourceWidth * scale).toInt()
        val drawHeight = (sourceHeight * scale).toInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        source.setBounds(left, top, left + drawWidth, top + drawHeight)
        source.draw(canvas)
    }

    private fun activityFrom(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private const val BACKDROP_SCALE = 0.30f
}

/** Draws a magnified, curved sample of the real app backdrop inside one glass surface. */
internal class PiperRefractiveGlassDrawable(
    target: View,
    private val radiusPx: Float,
    private val tintColor: Int,
    private val borderColor: Int,
    private val oval: Boolean = false
) : Drawable() {
    private val targetRef = WeakReference(target)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val clipPath = Path()
    private val source = Rect()
    private val destination = RectF()
    private val location = IntArray(2)

    override fun draw(canvas: Canvas) {
        val view = targetRef.get() ?: return
        val width = bounds.width().toFloat().coerceAtLeast(1f)
        val height = bounds.height().toFloat().coerceAtLeast(1f)
        val local = RectF(bounds)
        clipPath.reset()
        if (oval) clipPath.addOval(local, Path.Direction.CW)
        else clipPath.addRoundRect(local, radiusPx, radiusPx, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)
        PiperGlassBackdropStore.forView(view)?.let { backdrop ->
            view.getLocationInWindow(location)
            drawRefractedBackdrop(canvas, backdrop, width, height)
        }

        overlayPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.argb(15, 255, 255, 255),
                tintColor,
                tintColor,
                Color.argb(22, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.16f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(local, overlayPaint)
        overlayPaint.shader = null
        canvas.restoreToCount(save)

        borderPaint.strokeWidth = view.resources.displayMetrics.density.coerceAtLeast(1f)
        borderPaint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(Color.argb(138, 255, 255, 255), borderColor, Color.argb(42, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val inset = borderPaint.strokeWidth * 0.5f
        val borderBounds = RectF(local).apply { inset(inset, inset) }
        if (oval) canvas.drawOval(borderBounds, borderPaint)
        else canvas.drawRoundRect(borderBounds, radiusPx, radiusPx, borderPaint)
        borderPaint.shader = null

        borderPaint.strokeWidth = (view.resources.displayMetrics.density * 0.55f).coerceAtLeast(0.5f)
        borderPaint.color = Color.argb(32, 255, 255, 255)
        val innerInset = view.resources.displayMetrics.density * 2.2f
        val innerBounds = RectF(local).apply { inset(innerInset, innerInset) }
        if (oval) canvas.drawOval(innerBounds, borderPaint)
        else canvas.drawRoundRect(innerBounds, (radiusPx - innerInset).coerceAtLeast(0f), (radiusPx - innerInset).coerceAtLeast(0f), borderPaint)
    }

    private fun drawRefractedBackdrop(
        canvas: Canvas,
        backdrop: PiperGlassBackdropStore.Backdrop,
        width: Float,
        height: Float
    ) {
        val scaleX = backdrop.bitmap.width.toFloat() / backdrop.windowWidth.coerceAtLeast(1)
        val scaleY = backdrop.bitmap.height.toFloat() / backdrop.windowHeight.coerceAtLeast(1)
        val centerX = location[0] + width * 0.5f
        val centerY = location[1] + height * 0.5f
        val bandHeight = height / BANDS

        for (band in 0 until BANDS) {
            val top = band * bandHeight
            val bottom = if (band == BANDS - 1) height else (band + 1) * bandHeight + 1f
            val normalized = ((top + bottom) * 0.5f / height) * 2f - 1f
            val edgeStrength = abs(normalized) * abs(normalized)
            val bandMagnification = 1f + edgeStrength * EDGE_MAGNIFICATION
            val sampledWidth = width / (1f + edgeStrength * HORIZONTAL_MAGNIFICATION)
            val curvature = normalized * edgeStrength * CURVE_DP *
                targetRef.get()!!.resources.displayMetrics.density
            val sampledCenterY = centerY + normalized * (height * 0.5f / bandMagnification)
            val sampledBandHeight = (bottom - top) / bandMagnification

            source.set(
                ((centerX - sampledWidth * 0.5f + curvature) * scaleX).toInt(),
                ((sampledCenterY - sampledBandHeight * 0.5f) * scaleY).toInt(),
                ((centerX + sampledWidth * 0.5f + curvature) * scaleX).toInt(),
                ((sampledCenterY + sampledBandHeight * 0.5f) * scaleY).toInt()
            )
            source.left = source.left.coerceIn(0, backdrop.bitmap.width - 1)
            source.top = source.top.coerceIn(0, backdrop.bitmap.height - 1)
            source.right = source.right.coerceIn(source.left + 1, backdrop.bitmap.width)
            source.bottom = source.bottom.coerceIn(source.top + 1, backdrop.bitmap.height)
            destination.set(0f, top, width, bottom)
            canvas.drawBitmap(backdrop.bitmap, source, destination, bitmapPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
        overlayPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
        overlayPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val BANDS = 20
        const val EDGE_MAGNIFICATION = 0.14f
        const val HORIZONTAL_MAGNIFICATION = 0.08f
        const val CURVE_DP = 9f
    }
}
