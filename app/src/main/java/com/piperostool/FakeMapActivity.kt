package com.piperostool

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class FakeMapActivity : AppCompatActivity() {
    private enum class SelectionTarget {
        FIXED,
        START,
        END
    }

    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var controls: View
    private lateinit var map: MapView
    private lateinit var statusView: TextView
    private lateinit var selectionHint: TextView
    private lateinit var routeInfo: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var pointGroup: MaterialButtonToggleGroup
    private lateinit var travelGroup: MaterialButtonToggleGroup
    private lateinit var travelScroll: View
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var routeOptions: View
    private lateinit var naturalStops: SwitchMaterial
    private lateinit var routeLoop: SwitchMaterial
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private var scenarioMode = MockScenarioMode.FIXED
    private var selectionTarget = SelectionTarget.FIXED
    private var travelMode = MockTravelMode.MOTORBIKE
    private var speedKmh = travelMode.defaultSpeedKmh
    private var fixedPoint: RoutePoint? = null
    private var routeStart: RoutePoint? = null
    private var routeEnd: RoutePoint? = null
    private var routePoints = emptyList<RoutePoint>()
    private var routeLoading = false
    private var fixedMarker: Marker? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var receiverRegistered = false
    private var lastErrorShown: String? = null

    private val routeExecutor = Executors.newSingleThreadExecutor()
    private val routeGeneration = AtomicInteger()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val locationGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (locationGranted) {
            pendingPermissionAction?.invoke()
        } else {
            Toast.makeText(
                this,
                R.string.fake_map_permission_needed,
                Toast.LENGTH_LONG
            ).show()
        }
        pendingPermissionAction = null
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderServiceState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue =
            "${packageName}/${AppVersion.name(this)}"
        setContentView(R.layout.activity_fake_map)
        bindViews()
        applyInsets()
        configureMap()
        configureControls()
        restoreScenario()
        renderMode()
        renderServiceState()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(MockLocationService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        renderServiceState()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        routeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.fakeMapRoot)
        toolbar = findViewById(R.id.fakeMapToolbar)
        controls = findViewById(R.id.fakeMapControls)
        map = findViewById(R.id.fakeMapView)
        statusView = findViewById(R.id.fakeMapStatus)
        selectionHint = findViewById(R.id.fakeMapSelectionHint)
        routeInfo = findViewById(R.id.fakeMapRouteInfo)
        modeGroup = findViewById(R.id.fakeMapModeGroup)
        pointGroup = findViewById(R.id.fakeMapPointGroup)
        travelGroup = findViewById(R.id.fakeMapTravelGroup)
        travelScroll = findViewById(R.id.fakeMapTravelScroll)
        speedLabel = findViewById(R.id.fakeMapSpeedLabel)
        speedSeek = findViewById(R.id.fakeMapSpeed)
        routeOptions = findViewById(R.id.fakeMapRouteOptions)
        naturalStops = findViewById(R.id.switchNaturalStops)
        routeLoop = findViewById(R.id.switchRouteLoop)
        startButton = findViewById(R.id.btnStartMock)
        stopButton = findViewById(R.id.btnStopMock)
    }

    private fun applyInsets() {
        val toolbarTop = toolbar.paddingTop
        val controlsBottom = controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbar.paddingLeft,
                toolbarTop + bars.top,
                toolbar.paddingRight,
                toolbar.paddingBottom
            )
            toolbar.layoutParams = toolbar.layoutParams.apply {
                height = resources.getDimensionPixelSize(R.dimen.fake_map_toolbar_height) +
                    bars.top
            }
            controls.setPadding(
                controls.paddingLeft,
                controls.paddingTop,
                controls.paddingRight,
                controlsBottom + bars.bottom
            )
            insets
        }
    }

    private fun configureMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(14.0)
        map.controller.setCenter(DEFAULT_CENTER)
        map.overlays += CopyrightOverlay(this)
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                selectPoint(RoutePoint(point.latitude, point.longitude))
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean {
                selectPoint(RoutePoint(point.latitude, point.longitude))
                return true
            }
        })
    }

    private fun configureControls() {
        findViewById<View>(R.id.btnFakeMapBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMockSettings).setOnClickListener {
            openMockLocationSettings()
        }
        findViewById<ImageButton>(R.id.btnUseCurrentLocation).setOnClickListener {
            withLocationPermission(::useCurrentLocation)
        }

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            scenarioMode = if (checkedId == R.id.btnModeRoute) {
                MockScenarioMode.ROUTE
            } else {
                MockScenarioMode.FIXED
            }
            selectionTarget = if (scenarioMode == MockScenarioMode.FIXED) {
                SelectionTarget.FIXED
            } else {
                SelectionTarget.START
            }
            renderMode()
        }
        pointGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectionTarget = if (checkedId == R.id.btnPickEnd) {
                SelectionTarget.END
            } else {
                SelectionTarget.START
            }
            renderSelectionHint()
        }
        travelGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            travelMode = when (checkedId) {
                R.id.btnTravelWalk -> MockTravelMode.WALK
                R.id.btnTravelCar -> MockTravelMode.CAR
                R.id.btnTravelPlane -> MockTravelMode.PLANE
                else -> MockTravelMode.MOTORBIKE
            }
            configureSpeed(travelMode.defaultSpeedKmh)
            if (routeStart != null && routeEnd != null) calculateRoute()
        }
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedKmh = (progress + 1).toDouble()
                renderSpeed()
                renderRouteInfo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        startButton.setOnClickListener {
            val state = MockLocationService.snapshot
            when {
                state.running && state.paused ->
                    MockLocationService.sendAction(this, MockLocationService.ACTION_RESUME)
                state.running ->
                    MockLocationService.sendAction(this, MockLocationService.ACTION_PAUSE)
                else -> beginSimulation()
            }
        }
        stopButton.setOnClickListener {
            MockLocationService.sendAction(this, MockLocationService.ACTION_STOP)
        }
    }

    private fun restoreScenario() {
        val saved = MockRouteStore.load(this) ?: return
        scenarioMode = saved.mode
        travelMode = saved.travelMode
        speedKmh = saved.speedKmh
        naturalStops.isChecked = saved.naturalStops
        routeLoop.isChecked = saved.loop
        if (saved.mode == MockScenarioMode.FIXED) {
            fixedPoint = saved.points.firstOrNull()
            modeGroup.check(R.id.btnModeFixed)
        } else {
            routePoints = saved.points
            routeStart = saved.points.firstOrNull()
            routeEnd = saved.points.lastOrNull()
            modeGroup.check(R.id.btnModeRoute)
            travelGroup.check(travelButtonId(saved.travelMode))
        }
        configureSpeed(saved.speedKmh)
        redrawMap()
        saved.points.firstOrNull()?.let {
            map.controller.setCenter(GeoPoint(it.latitude, it.longitude))
        }
    }

    private fun renderMode() {
        val routeMode = scenarioMode == MockScenarioMode.ROUTE
        pointGroup.visibility = if (routeMode) View.VISIBLE else View.GONE
        travelScroll.visibility = if (routeMode) View.VISIBLE else View.GONE
        speedLabel.visibility = if (routeMode) View.VISIBLE else View.GONE
        speedSeek.visibility = if (routeMode) View.VISIBLE else View.GONE
        routeOptions.visibility = if (routeMode) View.VISIBLE else View.GONE
        if (!routeMode) selectionTarget = SelectionTarget.FIXED
        renderSelectionHint()
        renderRouteInfo()
        redrawMap()
    }

    private fun renderSelectionHint() {
        selectionHint.setText(
            when (selectionTarget) {
                SelectionTarget.FIXED -> R.string.fake_map_pick_fixed
                SelectionTarget.START -> R.string.fake_map_pick_start
                SelectionTarget.END -> R.string.fake_map_pick_end
            }
        )
    }

    private fun selectPoint(point: RoutePoint) {
        when (selectionTarget) {
            SelectionTarget.FIXED -> {
                fixedPoint = point
                saveDraftIfReady()
            }
            SelectionTarget.START -> {
                routeStart = point
                routePoints = emptyList()
                selectionTarget = SelectionTarget.END
                pointGroup.check(R.id.btnPickEnd)
            }
            SelectionTarget.END -> {
                routeEnd = point
                routePoints = emptyList()
            }
        }
        redrawMap()
        renderRouteInfo()
        if (scenarioMode == MockScenarioMode.ROUTE &&
            routeStart != null &&
            routeEnd != null
        ) {
            calculateRoute()
        }
    }

    private fun calculateRoute() {
        val start = routeStart ?: return
        val end = routeEnd ?: return
        val generation = routeGeneration.incrementAndGet()
        routeLoading = true
        routeInfo.setText(R.string.fake_map_route_loading)
        startButton.isEnabled = false
        routeExecutor.execute {
            val result = MockRoutePlanner.plan(start, end, travelMode)
            runOnUiThread {
                if (generation != routeGeneration.get() || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                routeLoading = false
                routePoints = result.points
                startButton.isEnabled = true
                redrawMap()
                renderRouteInfo()
                saveDraftIfReady()
                if (result.usedFallback) {
                    Toast.makeText(
                        this,
                        R.string.fake_map_route_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun redrawMap() {
        listOfNotNull(fixedMarker, startMarker, endMarker, routeLine).forEach {
            map.overlays.remove(it)
        }
        fixedMarker = null
        startMarker = null
        endMarker = null
        routeLine = null

        fixedPoint?.takeIf { scenarioMode == MockScenarioMode.FIXED }?.let { point ->
            fixedMarker = addMarker(
                point,
                getString(R.string.fake_map_fixed),
                R.drawable.ic_map_marker_start
            )
        }
        if (scenarioMode == MockScenarioMode.ROUTE) {
            routeStart?.let {
                startMarker = addMarker(
                    it,
                    getString(R.string.fake_map_pick_start),
                    R.drawable.ic_map_marker_start
                )
            }
            routeEnd?.let {
                endMarker = addMarker(
                    it,
                    getString(R.string.fake_map_pick_end),
                    R.drawable.ic_map_marker_end
                )
            }
            if (routePoints.size >= 2) {
                routeLine = Polyline(map).apply {
                    outlinePaint.color = 0xFF39E879.toInt()
                    outlinePaint.strokeWidth = 7f
                    setPoints(routePoints.map { GeoPoint(it.latitude, it.longitude) })
                }
                map.overlays.add(routeLine)
                map.post {
                    val geoPoints = routePoints.map { GeoPoint(it.latitude, it.longitude) }
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true, 72)
                }
            }
        }
        map.invalidate()
    }

    private fun addMarker(
        point: RoutePoint,
        label: String,
        iconResource: Int
    ): Marker =
        Marker(map).apply {
            position = GeoPoint(point.latitude, point.longitude)
            title = label
            icon = ContextCompat.getDrawable(this@FakeMapActivity, iconResource)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }

    private fun configureSpeed(value: Double) {
        speedSeek.max = (travelMode.maximumSpeedKmh - 1).coerceAtLeast(1)
        speedSeek.progress = (value.roundToInt() - 1).coerceIn(0, speedSeek.max)
        speedKmh = speedSeek.progress + 1.0
        renderSpeed()
    }

    private fun renderSpeed() {
        speedLabel.text = getString(R.string.fake_map_speed, speedKmh)
    }

    private fun renderRouteInfo() {
        if (scenarioMode == MockScenarioMode.FIXED) {
            routeInfo.text = fixedPoint?.let {
                getString(R.string.fake_map_coordinates, it.latitude, it.longitude)
            }.orEmpty()
            return
        }
        if (routeLoading) {
            routeInfo.setText(R.string.fake_map_route_loading)
            return
        }
        if (routePoints.size < 2) {
            routeInfo.text = listOfNotNull(
                routeStart?.let { "A: %.5f, %.5f".format(it.latitude, it.longitude) },
                routeEnd?.let { "B: %.5f, %.5f".format(it.latitude, it.longitude) }
            ).joinToString("  •  ")
            return
        }
        val distance = RouteProgressor(routePoints).totalDistanceMeters
        val durationSeconds = distance / (speedKmh / 3.6)
        routeInfo.text = getString(
            R.string.fake_map_route_ready,
            distance / 1_000.0,
            formatDuration(durationSeconds)
        )
    }

    private fun beginSimulation() {
        val scenario = createScenario() ?: return
        MockRouteStore.save(this, scenario)
        withLocationPermission {
            if (!MockLocationService.isMockLocationEnabled(this)) {
                Toast.makeText(
                    this,
                    R.string.fake_map_mock_not_selected,
                    Toast.LENGTH_LONG
                ).show()
                openMockLocationSettings()
            } else {
                MockLocationService.start(this)
            }
        }
    }

    private fun createScenario(): MockScenario? {
        val points = if (scenarioMode == MockScenarioMode.FIXED) {
            listOfNotNull(fixedPoint)
        } else {
            routePoints
        }
        if (points.isEmpty()) {
            Toast.makeText(
                this,
                if (scenarioMode == MockScenarioMode.FIXED) {
                    R.string.fake_map_select_point
                } else {
                    R.string.fake_map_select_route_points
                },
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        return MockScenario(
            mode = scenarioMode,
            travelMode = travelMode,
            speedKmh = speedKmh,
            naturalStops = scenarioMode == MockScenarioMode.ROUTE && naturalStops.isChecked,
            loop = scenarioMode == MockScenarioMode.ROUTE && routeLoop.isChecked,
            points = points
        )
    }

    private fun saveDraftIfReady() {
        createScenarioSilently()?.let { MockRouteStore.save(this, it) }
    }

    private fun createScenarioSilently(): MockScenario? {
        val points = if (scenarioMode == MockScenarioMode.FIXED) {
            listOfNotNull(fixedPoint)
        } else {
            routePoints
        }
        if (points.isEmpty()) return null
        return MockScenario(
            mode = scenarioMode,
            travelMode = travelMode,
            speedKmh = speedKmh,
            naturalStops = naturalStops.isChecked,
            loop = routeLoop.isChecked,
            points = points
        )
    }

    private fun renderServiceState() {
        val state = MockLocationService.snapshot
        if (state.error != null && state.error != lastErrorShown) {
            lastErrorShown = state.error
            Toast.makeText(this, state.error, Toast.LENGTH_LONG).show()
        }
        val pointLabel = state.point?.let {
            getString(R.string.fake_map_coordinates, it.latitude, it.longitude)
        }.orEmpty()
        statusView.text = when {
            state.arrived -> getString(R.string.fake_map_arrived)
            state.running && state.paused ->
                getString(R.string.fake_map_paused, pointLabel)
            state.running ->
                getString(R.string.fake_map_running, pointLabel)
            MockLocationService.isMockLocationEnabled(this) ->
                getString(R.string.fake_map_ready)
            else -> getString(R.string.fake_map_setup_required)
        }
        startButton.setText(
            when {
                state.running && state.paused -> R.string.fake_map_resume
                state.running -> R.string.fake_map_pause
                else -> R.string.fake_map_start
            }
        )
        startButton.setIconResource(
            if (state.running && !state.paused) {
                R.drawable.ic_media_pause
            } else {
                R.drawable.ic_media_play
            }
        )
        stopButton.visibility = if (state.running) View.VISIBLE else View.GONE
    }

    private fun useCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                R.string.fake_map_permission_needed,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val location = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (location == null) {
            Toast.makeText(
                this,
                R.string.fake_map_current_unavailable,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val point = RoutePoint(location.latitude, location.longitude)
        selectPoint(point)
        map.controller.animateTo(GeoPoint(point.latitude, point.longitude))
        map.controller.setZoom(16.0)
    }

    private fun withLocationPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingPermissionAction = action
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openMockLocationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        Toast.makeText(
            this,
            R.string.fake_map_mock_not_selected,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun travelButtonId(mode: MockTravelMode): Int = when (mode) {
        MockTravelMode.WALK -> R.id.btnTravelWalk
        MockTravelMode.MOTORBIKE -> R.id.btnTravelMotorbike
        MockTravelMode.CAR -> R.id.btnTravelCar
        MockTravelMode.PLANE -> R.id.btnTravelPlane
    }

    private fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        return if (totalMinutes < 60) {
            "$totalMinutes phút"
        } else {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0) "$hours giờ" else "$hours giờ $minutes phút"
        }
    }

    companion object {
        private val DEFAULT_CENTER = GeoPoint(21.0278, 105.8342)
    }
}
