package com.piperostool

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class BrowserLocationMapActivity : AppCompatActivity() {
    private lateinit var store: BrowserSessionStore
    private lateinit var map: MapView
    private lateinit var description: TextView
    private lateinit var save: MaterialButton
    private var marker: Marker? = null
    private var selected: BrowserLocation? = null
    private var selectionNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "${packageName}/${AppVersion.name(this)}"
        store = BrowserSessionStore(this)
        selected = store.browserLocation()
        buildLayout()
        configureMap()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() { map.onDetach(); super.onDestroy() }

    private fun buildLayout() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            addView(MaterialButton(this@BrowserLocationMapActivity).apply {
                text = "‹"
                textSize = 26f
                contentDescription = "Quay lại"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(50), dp(48)))
            addView(TextView(this@BrowserLocationMapActivity).apply {
                text = "Vị trí website trong Browser"
                textSize = 19f
                setTextColor(PiperModernUi.textColor(this@BrowserLocationMapActivity))
                setPadding(dp(9), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(header)
        map = MapView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
        }
        description = TextView(this).apply {
            textSize = 14f
            setTextColor(PiperModernUi.textColor(this@BrowserLocationMapActivity))
            text = selected?.let { "${it.label}\n${it.latitude}, ${it.longitude}" }
                ?: "Chạm vào bản đồ để chọn vị trí trang web. IP công cộng của máy không đổi."
            setPadding(0, 0, 0, dp(8))
        }
        footer.addView(description)
        save = MaterialButton(this).apply {
            text = "Dùng vị trí này cho Browser"
            isEnabled = selected != null
            setOnClickListener {
                selected?.let(store::setBrowserLocation)
                finish()
            }
        }
        footer.addView(save, LinearLayout.LayoutParams(-1, dp(52)))
        footer.addView(MaterialButton(this).apply {
            text = "Tắt vị trí riêng"
            setOnClickListener { store.clearBrowserLocation(); finish() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(footer)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(dp(16), dp(12) + bars.top, dp(16), dp(10))
            footer.setPadding(dp(18), dp(12), dp(18), dp(16) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        if (!PiperUiPreferences.isModern(this)) {
            header.setBackgroundColor(Color.argb(120, 17, 30, 49))
            footer.setBackgroundColor(Color.argb(165, 17, 30, 49))
        }
    }

    private fun configureMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(13.0)
        val center = selected?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(10.8231, 106.6297)
        map.controller.setCenter(center)
        map.overlays += CopyrightOverlay(this)
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean { choose(point); return true }
            override fun longPressHelper(point: GeoPoint): Boolean { choose(point); return true }
        })
        if (selected != null) showMarker(center)
    }

    private fun choose(point: GeoPoint) {
        val number = ++selectionNumber
        selected = BrowserLocation(point.latitude, point.longitude,
            String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude))
        save.isEnabled = true
        description.text = "Đang tìm địa chỉ…\n${selected?.label}"
        showMarker(point)
        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { Geocoder(this@BrowserLocationMapActivity, Locale.getDefault())
                    .getFromLocation(point.latitude, point.longitude, 1)
                    ?.firstOrNull()?.getAddressLine(0) }.getOrNull()
            }
            if (number != selectionNumber) return@launch
            val label = address?.takeIf(String::isNotBlank) ?: selected?.label.orEmpty()
            selected = BrowserLocation(point.latitude, point.longitude, label)
            description.text = "$label\n${point.latitude}, ${point.longitude}"
        }
    }

    private fun showMarker(point: GeoPoint) {
        marker?.let(map.overlays::remove)
        val size = dp(32)
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_location_pin)
            ?.toBitmap(size, size)
        marker = Marker(map).apply {
            position = point
            title = "Vị trí Browser"
            if (icon != null) this.icon = BitmapDrawable(resources, icon).apply { setBounds(0, 0, size, size) }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
