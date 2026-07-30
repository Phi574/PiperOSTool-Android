package com.piperostool

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.random.Random

class MockLocationService : Service() {
    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val arrived: Boolean = false,
        val progress: Double = 0.0,
        val point: RoutePoint? = null,
        val error: String? = null
    )

    private lateinit var locationManager: LocationManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var scenario: MockScenario? = null
    private var progressor: RouteProgressor? = null
    private var distanceMeters = 0.0
    private var forward = true
    private var paused = false
    private var arrived = false
    private var providersReady = false
    private var lastTickElapsed = 0L
    private var nextNaturalStopElapsed = Long.MAX_VALUE
    private var dwellUntilElapsed = 0L
    private var lastPoint: RoutePoint? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (snapshot.running) worker.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        workerThread = HandlerThread("PiperMockLocation", Process.THREAD_PRIORITY_BACKGROUND)
        workerThread.start()
        worker = Handler(workerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMocking()
            ACTION_PAUSE -> {
                paused = true
                publishState()
                updateNotification()
            }
            ACTION_RESUME -> {
                paused = false
                arrived = false
                lastTickElapsed = SystemClock.elapsedRealtime()
                publishState()
                updateNotification()
            }
            else -> startMocking()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        if (providersReady) removeMockProviders()
        workerThread.quitSafely()
        snapshot = State()
        sendStateBroadcast()
        super.onDestroy()
    }

    private fun startMocking() {
        val loaded = MockRouteStore.load(this)
        if (loaded == null) {
            fail(getString(R.string.fake_map_select_point))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail(getString(R.string.fake_map_permission_needed))
            return
        }
        startAsForeground(buildNotification(loaded))
        if (!isMockLocationEnabled(this)) {
            fail(getString(R.string.fake_map_mock_not_selected))
            return
        }

        runCatching { prepareMockProviders() }
            .onFailure {
                fail(getString(R.string.fake_map_provider_error))
                return
            }

        scenario = loaded
        progressor = RouteProgressor(loaded.points)
        distanceMeters = 0.0
        forward = true
        paused = false
        arrived = false
        lastTickElapsed = SystemClock.elapsedRealtime()
        scheduleNextNaturalStop(lastTickElapsed)
        snapshot = State(running = true)
        worker.removeCallbacks(ticker)
        worker.post(ticker)
    }

    private fun tick() {
        val active = scenario ?: return
        val route = progressor ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsedSeconds = ((now - lastTickElapsed).coerceAtLeast(0L)) / 1_000.0
        lastTickElapsed = now

        if (active.naturalStops && !paused && !arrived) {
            if (dwellUntilElapsed > now) {
                publishLastPoint(speedMetersPerSecond = 0f)
                return
            }
            if (now >= nextNaturalStopElapsed) {
                dwellUntilElapsed = now + Random.nextLong(3_000L, 8_001L)
                scheduleNextNaturalStop(dwellUntilElapsed)
                publishLastPoint(speedMetersPerSecond = 0f)
                return
            }
        }

        if (paused) {
            publishLastPoint(speedMetersPerSecond = 0f)
            return
        }

        val position = if (active.mode == MockScenarioMode.FIXED) {
            route.positionAt(0.0)
        } else {
            val speedMetersPerSecond = active.speedKmh / 3.6
            val naturalVariance = Random.nextDouble(0.96, 1.041)
            val delta = speedMetersPerSecond * elapsedSeconds * naturalVariance
            distanceMeters += if (forward) delta else -delta
            distanceMeters = distanceMeters.coerceIn(0.0, route.totalDistanceMeters)
            var calculated = route.positionAt(distanceMeters)
            if (!forward) {
                calculated = calculated.copy(
                    bearing = (calculated.bearing + 180f) % 360f
                )
            }
            val reachedBoundary = if (forward) {
                distanceMeters >= route.totalDistanceMeters
            } else {
                distanceMeters <= 0.0
            }
            if (reachedBoundary) {
                if (active.loop) {
                    forward = !forward
                    arrived = false
                } else {
                    arrived = true
                }
                calculated = calculated.copy(arrived = arrived)
            }
            calculated
        }

        lastPoint = position.point
        val speed = if (arrived || active.mode == MockScenarioMode.FIXED) {
            0f
        } else {
            (active.speedKmh / 3.6).toFloat()
        }
        publishMockLocation(position.point, speed, position.bearing)
        val progress = if (route.totalDistanceMeters > 0.0) {
            distanceMeters / route.totalDistanceMeters
        } else {
            1.0
        }
        snapshot = State(
            running = true,
            paused = paused,
            arrived = arrived,
            progress = progress,
            point = position.point
        )
        sendStateBroadcast()
        updateNotification()
    }

    private fun publishLastPoint(speedMetersPerSecond: Float) {
        val point = lastPoint ?: scenario?.points?.firstOrNull() ?: return
        publishMockLocation(point, speedMetersPerSecond, 0f)
        publishState()
    }

    private fun publishState() {
        snapshot = snapshot.copy(
            running = true,
            paused = paused,
            arrived = arrived,
            point = lastPoint
        )
        sendStateBroadcast()
    }

    @Suppress("DEPRECATION")
    private fun prepareMockProviders() {
        MOCK_PROVIDERS.forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
            locationManager.addTestProvider(
                provider,
                false,
                provider == LocationManager.GPS_PROVIDER,
                false,
                false,
                true,
                true,
                true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        }
        providersReady = true
    }

    private fun publishMockLocation(
        point: RoutePoint,
        speedMetersPerSecond: Float,
        bearing: Float
    ) {
        MOCK_PROVIDERS.forEach { provider ->
            val location = Location(provider).apply {
                latitude = point.latitude
                longitude = point.longitude
                altitude = 0.0
                accuracy = Random.nextDouble(3.0, 7.0).toFloat()
                speed = speedMetersPerSecond
                this.bearing = bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 4f
                    speedAccuracyMetersPerSecond = 0.5f
                    bearingAccuracyDegrees = 3f
                }
            }
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    private fun removeMockProviders() {
        MOCK_PROVIDERS.forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
        }
        providersReady = false
    }

    private fun stopMocking() {
        snapshot = State()
        sendStateBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        snapshot = State(error = message)
        sendStateBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleNextNaturalStop(fromElapsed: Long) {
        nextNaturalStopElapsed = fromElapsed + Random.nextLong(45_000L, 105_001L)
    }

    private fun updateNotification() {
        val active = scenario ?: return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(active))
    }

    private fun buildNotification(active: MockScenario): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, FakeMapActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_PAUSE,
            Intent(this, MockLocationService::class.java).setAction(pauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = when {
            paused -> getString(R.string.fake_map_notification_paused)
            active.mode == MockScenarioMode.FIXED -> {
                val point = snapshot.point ?: active.points.first()
                getString(
                    R.string.fake_map_notification_fixed,
                    coordinateLabel(point)
                )
            }
            else -> getString(
                R.string.fake_map_notification_route,
                snapshot.progress.coerceIn(0.0, 1.0) * 100.0,
                active.travelMode.displayName
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_pin)
            .setContentTitle(getString(R.string.fake_map_notification_title))
            .setContentText(content)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                100,
                (snapshot.progress * 100.0).roundToInt().coerceIn(0, 100),
                active.mode == MockScenarioMode.FIXED
            )
            .addAction(
                if (paused) R.drawable.ic_media_play else R.drawable.ic_media_pause,
                getString(if (paused) R.string.fake_map_resume else R.string.fake_map_pause),
                pauseIntent
            )
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.fake_map_notification_stop),
                stopIntent
            )
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fake_map_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fake_map_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun sendStateBroadcast() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
        )
    }

    private fun coordinateLabel(point: RoutePoint): String =
        String.format("%.5f, %.5f", point.latitude, point.longitude)

    companion object {
        const val ACTION_START = "com.piperostool.mock.START"
        const val ACTION_STOP = "com.piperostool.mock.STOP"
        const val ACTION_PAUSE = "com.piperostool.mock.PAUSE"
        const val ACTION_RESUME = "com.piperostool.mock.RESUME"
        const val ACTION_STATE_CHANGED = "com.piperostool.mock.STATE_CHANGED"
        private const val CHANNEL_ID = "piperos_mock_location"
        private const val NOTIFICATION_ID = 4801
        private const val REQUEST_OPEN = 4802
        private const val REQUEST_PAUSE = 4803
        private const val REQUEST_STOP = 4804
        private const val UPDATE_INTERVAL_MS = 1_000L
        private val MOCK_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        @Volatile
        var snapshot = State()
            private set

        fun isMockLocationEnabled(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MockLocationService::class.java).setAction(ACTION_START)
            )
        }

        fun sendAction(context: Context, action: String) {
            context.startService(
                Intent(context, MockLocationService::class.java).setAction(action)
            )
        }
    }
}
