package com.piperostool

import android.Manifest
import android.animation.ObjectAnimator
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PiperMediaActivity : AppCompatActivity() {
    private enum class LibraryFilter { ALL, AUDIO, VIDEO, QUEUE }
    private enum class LibrarySort {
        NEWEST,
        OLDEST,
        NAME_ASC,
        NAME_DESC,
        SIZE_DESC,
        SIZE_ASC
    }

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var stage: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var discContainer: View
    private lateinit var artwork: ImageView
    private lateinit var emptyStage: TextView
    private lateinit var nowPlayingPanel: View
    private lateinit var filterBar: View
    private lateinit var sourceBar: View
    private lateinit var sourceChips: LinearLayout
    private lateinit var hint: View
    private lateinit var mediaList: ListView
    private lateinit var libraryCount: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: ImageButton
    private lateinit var refresh: ImageButton
    private lateinit var searchButton: ImageButton
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var repeatOneBadge: View
    private lateinit var fullscreen: ImageButton

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var allMedia = emptyList<PiperMediaAsset>()
    private var visibleMedia = emptyList<PiperMediaAsset>()
    private var currentFilter = LibraryFilter.ALL
    private var currentSort = LibrarySort.NEWEST
    private var currentSource: String? = null
    private val enabledSources = linkedSetOf<String>()
    private val selectedIds = linkedSetOf<String>()
    private val scanner = Executors.newSingleThreadExecutor()
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var userSeeking = false
    private var isScanning = false
    private var searchQuery = ""
    private var isVideoFullscreen = false
    private var currentVideoLandscape = true
    private var orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var searchWasVisibleBeforeFullscreen = false

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val discAnimator by lazy {
        ObjectAnimator.ofFloat(discContainer, View.ROTATION, 0f, 360f).apply {
            duration = 14_000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlaybackUi()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying(mediaItem)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                currentVideoLandscape = videoSize.width >= videoSize.height
                if (isVideoFullscreen) {
                    requestedOrientation = if (currentVideoLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
                updatePipParams(isCurrentItemVideo())
            }
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val activeController = controller
            if (!userSeeking && activeController != null) {
                val duration = activeController.duration.coerceAtLeast(0L)
                seekBar.progress = if (duration > 0L) {
                    ((activeController.currentPosition * 1000L) / duration)
                        .toInt()
                        .coerceIn(0, 1000)
                } else {
                    0
                }
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMediaPermission()) {
            scanLibrary()
        } else {
            libraryCount.setText(R.string.media_permission_needed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.activity_piper_media)

        bindViews()
        applyInsets()
        restoreQueue()
        restoreLibraryPreferences()
        setupActions()
        setupBackHandling()
        connectController()
        requestLibraryAccess()
    }

    private fun bindViews() {
        root = findViewById(R.id.mediaRoot)
        topBar = findViewById(R.id.mediaTopBar)
        stage = findViewById(R.id.mediaStage)
        playerView = findViewById(R.id.mediaPlayerView)
        discContainer = findViewById(R.id.mediaDiscContainer)
        artwork = findViewById(R.id.mediaArtwork)
        emptyStage = findViewById(R.id.mediaEmptyStage)
        nowPlayingPanel = findViewById(R.id.mediaNowPlayingPanel)
        filterBar = findViewById(R.id.mediaFilterBar)
        sourceBar = findViewById(R.id.mediaSourceBar)
        sourceChips = findViewById(R.id.mediaSourceChips)
        hint = findViewById(R.id.mediaHint)
        mediaList = findViewById(R.id.mediaList)
        libraryCount = findViewById(R.id.mediaLibraryCount)
        nowTitle = findViewById(R.id.mediaNowTitle)
        nowArtist = findViewById(R.id.mediaNowArtist)
        seekBar = findViewById(R.id.mediaSeek)
        playPause = findViewById(R.id.mediaPlayPause)
        refresh = findViewById(R.id.mediaRefresh)
        searchButton = findViewById(R.id.mediaSearch)
        searchBar = findViewById(R.id.mediaSearchBar)
        searchInput = findViewById(R.id.mediaSearchInput)
        shuffle = findViewById(R.id.mediaShuffle)
        repeat = findViewById(R.id.mediaRepeat)
        repeatOneBadge = findViewById(R.id.mediaRepeatOneBadge)
        fullscreen = findViewById(R.id.mediaFullscreen)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (isVideoFullscreen) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.mediaClose).setOnClickListener { finish() }
        fullscreen.setOnClickListener {
            if (isVideoFullscreen) exitVideoFullscreen() else enterVideoFullscreen()
        }
        refresh.setOnClickListener {
            if (!hasMediaPermission()) {
                requestLibraryAccess()
            } else {
                refresh.animate()
                    .rotationBy(360f)
                    .setDuration(550L)
                    .start()
                scanLibrary(showResult = true)
            }
        }
        searchButton.setOnClickListener {
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            searchInput.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        findViewById<View>(R.id.mediaSearchClose).setOnClickListener {
            searchInput.text?.clear()
            searchBar.visibility = View.GONE
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(searchInput.windowToken, 0)
            searchInput.clearFocus()
        }
        searchInput.doAfterTextChanged { value ->
            searchQuery = value?.toString().orEmpty().trim()
            showFilter(currentFilter)
        }
        findViewById<View>(R.id.mediaPrevious).setOnClickListener {
            controller?.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.mediaNext).setOnClickListener {
            controller?.seekToNextMediaItem()
        }
        playPause.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        shuffle.setOnClickListener {
            controller?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                Toast.makeText(
                    this,
                    if (it.shuffleModeEnabled) R.string.media_shuffle_on
                    else R.string.media_shuffle_off,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        repeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                val message = when (it.repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.string.media_repeat_one
                    Player.REPEAT_MODE_ALL -> R.string.media_repeat_all
                    else -> R.string.media_repeat_off
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val activeController = controller
                if (activeController != null && activeController.duration > 0) {
                    activeController.seekTo(
                        activeController.duration * (seekBar?.progress ?: 0) / 1000L
                    )
                }
                userSeeking = false
            }
        })

        bindFilter(R.id.mediaFilterAll, LibraryFilter.ALL)
        bindFilter(R.id.mediaFilterAudio, LibraryFilter.AUDIO)
        bindFilter(R.id.mediaFilterVideo, LibraryFilter.VIDEO)
        bindFilter(R.id.mediaFilterQueue, LibraryFilter.QUEUE)
        findViewById<View>(R.id.mediaSort).setOnClickListener { showSortDialog() }
        findViewById<View>(R.id.mediaSourceMenu).setOnClickListener {
            showSourceSelectionDialog()
        }
        findViewById<View>(R.id.mediaPlayQueue).setOnClickListener {
            val queue = allMedia.filter { it.id in selectedIds }
            if (queue.isEmpty()) {
                Toast.makeText(this, R.string.media_empty_queue, Toast.LENGTH_SHORT).show()
            } else {
                playAssets(queue, 0)
            }
        }

        mediaList.setOnItemClickListener { _, _, position, _ ->
            playAssets(visibleMedia, position)
        }
        mediaList.setOnItemLongClickListener { _, _, position, _ ->
            val asset = visibleMedia[position]
            val added = if (asset.id in selectedIds) {
                selectedIds.remove(asset.id)
                false
            } else {
                selectedIds.add(asset.id)
                true
            }
            saveQueue()
            updateFilterCounts()
            showFilter(currentFilter)
            Toast.makeText(
                this,
                getString(
                    if (added) R.string.media_added_to_queue
                    else R.string.media_removed_from_queue,
                    asset.title
                ),
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        showSelectedFilter()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isVideoFullscreen) {
                        exitVideoFullscreen()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    private fun bindFilter(buttonId: Int, filter: LibraryFilter) {
        findViewById<View>(buttonId).setOnClickListener { showFilter(filter) }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PiperPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    playerView.player = mediaController
                    mediaController.addListener(playerListener)
                    updateNowPlaying(mediaController.currentMediaItem)
                    updatePlaybackUi()
                    progressHandler.post(progressUpdater)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun requestLibraryAccess() {
        if (hasMediaPermission()) {
            scanLibrary()
            requestNotificationPermissionIfNeeded()
            return
        }

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
            permissions += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun scanLibrary(showResult: Boolean = false) {
        if (isScanning) return
        isScanning = true
        refresh.isEnabled = false
        refresh.alpha = 0.45f
        libraryCount.setText(R.string.media_scanning)
        scanner.execute {
            val found = buildList {
                addAll(queryAudio())
                addAll(queryVideo())
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allMedia = found
                initializeSources(found)
                if (selectedIds.retainAll(found.mapTo(hashSetOf()) { it.id })) {
                    saveQueue()
                }
                val audioCount = found.count { !it.isVideo }
                val videoCount = found.size - audioCount
                libraryCount.text = getString(
                    R.string.media_library_count,
                    audioCount,
                    videoCount
                )
                updateFilterCounts()
                rebuildSourceChips()
                updateSortButton()
                showFilter(currentFilter)
                isScanning = false
                refresh.isEnabled = true
                refresh.alpha = 1f
                if (showResult) {
                    Toast.makeText(
                        this,
                        getString(R.string.media_library_refreshed, audioCount, videoCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun queryAudio(): List<PiperMediaAsset> {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        ).apply {
            add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.RELATIVE_PATH
                } else {
                    MediaStore.Audio.Media.DATA
                }
            )
        }.toTypedArray()
        return queryCollection(collection, projection, false)
    }

    private fun queryVideo(): List<PiperMediaAsset> {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        ).apply {
            add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.RELATIVE_PATH
                } else {
                    MediaStore.Video.Media.DATA
                }
            )
        }.toTypedArray()
        return queryCollection(collection, projection, true)
    }

    private fun queryCollection(
        collection: Uri,
        projection: Array<String>,
        isVideo: Boolean
    ): List<PiperMediaAsset> {
        val result = mutableListOf<PiperMediaAsset>()
        val cursor = try {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DURATION} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
        } catch (_: SecurityException) {
            null
        }

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val displayColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val mimeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathColumn = it.getColumnIndex(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.MediaColumns.RELATIVE_PATH
                } else {
                    MediaStore.MediaColumns.DATA
                }
            )
            val artistColumn = if (isVideo) {
                it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            } else {
                it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            }
            val albumColumn = if (isVideo) -1 else {
                it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            }

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn)
                    ?.takeIf(String::isNotBlank)
                    ?: it.getString(displayColumn)
                    ?: "Media $id"
                val artist = if (artistColumn >= 0) {
                    it.getString(artistColumn)
                        ?.takeIf { value -> value.isNotBlank() && value != "<unknown>" }
                } else null
                val uri = ContentUris.withAppendedId(collection, id)
                val rawPath = if (pathColumn >= 0) it.getString(pathColumn).orEmpty() else ""
                val relativePath = if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    rawPath.contains('/')
                ) {
                    rawPath.substringBeforeLast('/', "")
                } else {
                    rawPath.trim('/')
                }
                val source = classifyMediaSource(relativePath)
                val artworkUri = if (albumColumn >= 0) {
                    val albumId = it.getLong(albumColumn)
                    if (albumId > 0L) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )
                    } else null
                } else {
                    uri
                }

                result += PiperMediaAsset(
                    id = "${if (isVideo) "video" else "audio"}:$id",
                    uri = uri,
                    title = title,
                    artist = artist ?: getString(R.string.media_unknown_artist),
                    durationMs = it.getLong(durationColumn),
                    sizeBytes = it.getLong(sizeColumn).coerceAtLeast(0L),
                    dateAddedMs = it.getLong(dateColumn).coerceAtLeast(0L) * 1000L,
                    mimeType = it.getString(mimeColumn)
                        ?: if (isVideo) "video/*" else "audio/*",
                    isVideo = isVideo,
                    source = source,
                    relativePath = relativePath,
                    artworkUri = artworkUri
                )
            }
        }
        return result
    }

    private fun showFilter(filter: LibraryFilter) {
        currentFilter = filter
        val filteredByType = when (filter) {
            LibraryFilter.ALL -> allMedia
            LibraryFilter.AUDIO -> allMedia.filterNot(PiperMediaAsset::isVideo)
            LibraryFilter.VIDEO -> allMedia.filter(PiperMediaAsset::isVideo)
            LibraryFilter.QUEUE -> allMedia.filter { it.id in selectedIds }
        }
        val filteredBySource = filteredByType.filter { asset ->
            asset.source in enabledSources &&
                (currentSource == null || asset.source == currentSource)
        }
        val searched = if (searchQuery.isBlank()) {
            filteredBySource
        } else {
            filteredBySource.filter { asset ->
                asset.title.contains(searchQuery, ignoreCase = true) ||
                    asset.artist.contains(searchQuery, ignoreCase = true) ||
                    asset.mimeType.contains(searchQuery, ignoreCase = true) ||
                    asset.source.contains(searchQuery, ignoreCase = true) ||
                    asset.relativePath.contains(searchQuery, ignoreCase = true)
            }
        }
        visibleMedia = when (currentSort) {
            LibrarySort.NEWEST -> searched.sortedByDescending(PiperMediaAsset::dateAddedMs)
            LibrarySort.OLDEST -> searched.sortedBy(PiperMediaAsset::dateAddedMs)
            LibrarySort.NAME_ASC -> searched.sortedBy { it.title.lowercase() }
            LibrarySort.NAME_DESC -> searched.sortedByDescending { it.title.lowercase() }
            LibrarySort.SIZE_DESC -> searched.sortedByDescending(PiperMediaAsset::sizeBytes)
            LibrarySort.SIZE_ASC -> searched.sortedBy(PiperMediaAsset::sizeBytes)
        }
        mediaList.adapter = MediaAdapter(visibleMedia)
        showSelectedFilter()
    }

    private fun initializeSources(found: List<PiperMediaAsset>) {
        val available = orderedSources(found)
        val saved = preferences.getStringSet(KEY_ENABLED_SOURCES, null)
        enabledSources.clear()
        if (saved == null) {
            enabledSources += defaultSources(available)
        } else {
            enabledSources += saved.filter { it in available }
            if (enabledSources.isEmpty()) enabledSources += defaultSources(available)
        }
        if (currentSource !in enabledSources) currentSource = null
        saveEnabledSources()
    }

    private fun orderedSources(items: List<PiperMediaAsset>): List<String> {
        val present = items.mapTo(linkedSetOf(), PiperMediaAsset::source)
        return present.sortedWith(
            compareBy<String> {
                val priority = SOURCE_PRIORITY.indexOf(it)
                if (priority == -1) Int.MAX_VALUE else priority
            }.thenBy { it.lowercase() }
        )
    }

    private fun defaultSources(available: List<String>): List<String> {
        val defaults = DEFAULT_SOURCES.filter { it in available }.toMutableList()
        available.filterNot { it in defaults }.take(DEFAULT_SOURCE_COUNT - defaults.size)
            .let(defaults::addAll)
        return defaults.take(DEFAULT_SOURCE_COUNT)
    }

    private fun rebuildSourceChips() {
        sourceChips.removeAllViews()
        sourceChips.addView(createSourceChip(getString(R.string.media_all_sources), null))
        orderedSources(allMedia)
            .filter { it in enabledSources }
            .forEach { source ->
                sourceChips.addView(createSourceChip(source, source))
            }
    }

    private fun createSourceChip(label: String, source: String?): MaterialButton {
        val selected = currentSource == source
        return MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minHeight = dp(38)
            minimumHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(6)
            backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.rgb(35, 89, 60) else Color.rgb(16, 23, 27)
            )
            strokeColor = ColorStateList.valueOf(
                if (selected) Color.rgb(57, 229, 140)
                else Color.argb(85, 255, 255, 255)
            )
            setTextColor(Color.WHITE)
            setOnClickListener {
                currentSource = source
                rebuildSourceChips()
                showFilter(currentFilter)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun showSourceSelectionDialog() {
        val sources = orderedSources(allMedia)
        if (sources.isEmpty()) {
            Toast.makeText(this, R.string.media_no_sources, Toast.LENGTH_SHORT).show()
            return
        }
        val counts = allMedia.groupingBy(PiperMediaAsset::source).eachCount()
        val pending = enabledSources.toMutableSet()
        val labels = sources.map { source ->
            getString(R.string.media_source_with_count, source, counts[source] ?: 0)
        }.toTypedArray()
        val checked = BooleanArray(sources.size) { sources[it] in pending }

        AlertDialog.Builder(this)
            .setTitle(R.string.media_choose_sources)
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                if (enabled) pending += sources[which] else pending -= sources[which]
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (pending.isEmpty()) {
                    Toast.makeText(
                        this,
                        R.string.media_choose_at_least_one_source,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                enabledSources.clear()
                enabledSources += pending
                if (currentSource !in enabledSources) currentSource = null
                saveEnabledSources()
                updateFilterCounts()
                rebuildSourceChips()
                showFilter(currentFilter)
            }
            .show()
    }

    private fun showSortDialog() {
        val modes = LibrarySort.entries
        val labels = modes.map(::sortLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.media_sort_title)
            .setSingleChoiceItems(labels, modes.indexOf(currentSort)) { dialog, which ->
                currentSort = modes[which]
                preferences.edit().putString(KEY_SORT, currentSort.name).apply()
                updateSortButton()
                showFilter(currentFilter)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sortLabel(sort: LibrarySort): String = getString(
        when (sort) {
            LibrarySort.NEWEST -> R.string.media_sort_newest
            LibrarySort.OLDEST -> R.string.media_sort_oldest
            LibrarySort.NAME_ASC -> R.string.media_sort_name_az
            LibrarySort.NAME_DESC -> R.string.media_sort_name_za
            LibrarySort.SIZE_DESC -> R.string.media_sort_size_large
            LibrarySort.SIZE_ASC -> R.string.media_sort_size_small
        }
    )

    private fun updateSortButton() {
        findViewById<MaterialButton>(R.id.mediaSort).text = sortLabel(currentSort)
    }

    private fun updateFilterCounts() {
        val sourceFiltered = allMedia.filter { it.source in enabledSources }
        findViewById<MaterialButton>(R.id.mediaFilterAll).text =
            getString(R.string.media_all_with_count, sourceFiltered.size)
        findViewById<MaterialButton>(R.id.mediaFilterAudio).text =
            getString(R.string.media_audio_with_count, sourceFiltered.count { !it.isVideo })
        findViewById<MaterialButton>(R.id.mediaFilterVideo).text =
            getString(R.string.media_video_with_count, sourceFiltered.count(PiperMediaAsset::isVideo))
        findViewById<MaterialButton>(R.id.mediaFilterQueue).text =
            getString(
                R.string.media_queue_with_count,
                sourceFiltered.count { it.id in selectedIds }
            )
    }

    private fun classifyMediaSource(path: String): String {
        val normalized = path.replace('\\', '/').trim('/').lowercase()
        val segments = normalized.split('/').filter(String::isNotBlank)
        return when {
            segments.any { it == "piperos" } -> "PiperOS"
            segments.any { it.contains("zalo") } -> "Zalo"
            segments.any { it.contains("instagram") } -> "Instagram"
            segments.any { it == "camera" } -> "Camera"
            segments.any {
                it.contains("screenrecord") ||
                    it == "screen recorder" ||
                    it == "screen_recorder"
            } -> "ScreenRecorder"
            segments.any { it.contains("messenger") } -> "Messenger"
            segments.any { it == "download" || it == "downloads" } -> "Download"
            segments.any { it.contains("whatsapp") } -> "WhatsApp"
            segments.any { it.contains("telegram") } -> "Telegram"
            segments.any { it == "dcim" } -> "DCIM"
            segments.any { it == "movies" } -> "Movies"
            segments.any { it == "music" } -> "Music"
            segments.isNotEmpty() -> segments.last().replaceFirstChar { it.titlecase() }
            else -> getString(R.string.media_other_source)
        }
    }

    private fun showSelectedFilter() {
        val mapping = mapOf(
            R.id.mediaFilterAll to LibraryFilter.ALL,
            R.id.mediaFilterAudio to LibraryFilter.AUDIO,
            R.id.mediaFilterVideo to LibraryFilter.VIDEO,
            R.id.mediaFilterQueue to LibraryFilter.QUEUE
        )
        mapping.forEach { (buttonId, filter) ->
            findViewById<MaterialButton>(buttonId).apply {
                backgroundTintList = ColorStateList.valueOf(
                    if (filter == currentFilter) Color.rgb(35, 89, 60)
                    else Color.rgb(16, 23, 27)
                )
                strokeColor = ColorStateList.valueOf(
                    if (filter == currentFilter) Color.rgb(57, 229, 140)
                    else Color.argb(85, 255, 255, 255)
                )
            }
        }
    }

    private fun playAssets(assets: List<PiperMediaAsset>, index: Int) {
        if (assets.isEmpty()) return
        val activeController = controller ?: return
        activeController.setMediaItems(
            assets.map(PiperMediaAsset::toMediaItem),
            index.coerceIn(0, assets.lastIndex),
            0L
        )
        activeController.prepare()
        activeController.play()
    }

    private fun updateNowPlaying(mediaItem: MediaItem?) {
        val metadata = mediaItem?.mediaMetadata
        val asset = mediaItem?.mediaId?.let { id -> allMedia.find { it.id == id } }
        nowTitle.text = metadata?.title ?: getString(R.string.media_nothing_playing)
        nowArtist.text = metadata?.artist ?: getString(R.string.media_local_library)

        val isVideo = mediaItem?.mediaId?.startsWith("video:") == true ||
            asset?.isVideo == true ||
            metadata?.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
        if (!isVideo && isVideoFullscreen) exitVideoFullscreen()
        playerView.visibility = if (isVideo) View.VISIBLE else View.GONE
        fullscreen.visibility = if (isVideo) View.VISIBLE else View.GONE
        discContainer.visibility = if (mediaItem != null && !isVideo) View.VISIBLE else View.GONE
        emptyStage.visibility = if (mediaItem == null) View.VISIBLE else View.GONE

        if (mediaItem != null && !isVideo) {
            Glide.with(this)
                .load(asset?.artworkUri ?: metadata?.artworkUri ?: asset?.uri)
                .placeholder(R.drawable.a3tn)
                .error(R.drawable.a3tn)
                .into(artwork)
        }
        updatePipParams(isVideo)
        updateDiscAnimation()
    }

    private fun isCurrentItemVideo(): Boolean {
        val mediaItem = controller?.currentMediaItem ?: return false
        val asset = allMedia.find { it.id == mediaItem.mediaId }
        return mediaItem.mediaId.startsWith("video:") ||
            asset?.isVideo == true ||
            mediaItem.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
    }

    private fun enterVideoFullscreen() {
        if (!isCurrentItemVideo() || isVideoFullscreen) return
        isVideoFullscreen = true
        searchWasVisibleBeforeFullscreen = searchBar.visibility == View.VISIBLE
        orientationBeforeFullscreen = requestedOrientation

        topBar.visibility = View.GONE
        searchBar.visibility = View.GONE
        nowPlayingPanel.visibility = View.GONE
        filterBar.visibility = View.GONE
        sourceBar.visibility = View.GONE
        hint.visibility = View.GONE
        mediaList.visibility = View.GONE
        root.setPadding(0, 0, 0, 0)
        applyFullscreenStageLayout(true)

        playerView.useController = true
        fullscreen.setImageResource(R.drawable.ic_media_fullscreen_exit)
        fullscreen.contentDescription = getString(R.string.media_exit_fullscreen)
        requestedOrientation = if (currentVideoLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        WindowCompat.getInsetsController(window, playerView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitVideoFullscreen() {
        if (!isVideoFullscreen) return
        isVideoFullscreen = false
        requestedOrientation = orientationBeforeFullscreen
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        topBar.visibility = View.VISIBLE
        searchBar.visibility = if (searchWasVisibleBeforeFullscreen) View.VISIBLE else View.GONE
        nowPlayingPanel.visibility = View.VISIBLE
        filterBar.visibility = View.VISIBLE
        sourceBar.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        mediaList.visibility = View.VISIBLE
        applyFullscreenStageLayout(false)
        playerView.useController = false
        fullscreen.setImageResource(R.drawable.ic_media_fullscreen)
        fullscreen.contentDescription = getString(R.string.media_enter_fullscreen)
        fullscreen.visibility = if (isCurrentItemVideo()) View.VISIBLE else View.GONE
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyFullscreenStageLayout(fullScreen: Boolean) {
        if (fullScreen) {
            stage.setBackgroundColor(Color.BLACK)
        } else {
            stage.setBackgroundResource(R.drawable.bg_media_surface)
        }
        stage.layoutParams = stage.layoutParams.apply {
            height = if (fullScreen) 0 else dp(220)
            if (this is LinearLayout.LayoutParams) {
                weight = if (fullScreen) 1f else 0f
                setMargins(
                    if (fullScreen) 0 else dp(16),
                    0,
                    if (fullScreen) 0 else dp(16),
                    0
                )
            }
        }
        playerView.requestLayout()
    }

    private fun updatePlaybackUi() {
        val activeController = controller ?: return
        playPause.setImageResource(
            if (activeController.isPlaying) R.drawable.ic_media_pause
            else R.drawable.ic_media_play
        )
        shuffle.imageTintList = ColorStateList.valueOf(
            if (activeController.shuffleModeEnabled) Color.rgb(57, 229, 140)
            else Color.WHITE
        )
        repeat.imageTintList = ColorStateList.valueOf(
            if (activeController.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE
            else Color.rgb(57, 229, 140)
        )
        repeatOneBadge.visibility =
            if (activeController.repeatMode == Player.REPEAT_MODE_ONE) View.VISIBLE else View.GONE
        updateDiscAnimation()
    }

    private fun updateDiscAnimation() {
        val active = controller
        val asset = active?.currentMediaItem?.mediaId
            ?.let { id -> allMedia.find { it.id == id } }
        if (active?.isPlaying == true && asset?.isVideo != true) {
            if (!discAnimator.isStarted) discAnimator.start() else discAnimator.resume()
        } else if (discAnimator.isStarted) {
            discAnimator.pause()
        }
    }

    private fun updatePipParams(isVideo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val videoSize = controller?.videoSize
        val width = videoSize?.width?.takeIf { it > 0 } ?: 16
        val height = videoSize?.height?.takeIf { it > 0 } ?: 9
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(isVideo)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        setPictureInPictureParams(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val activeController = controller
        val currentMediaId = activeController?.currentMediaItem?.mediaId
        val asset = currentMediaId?.let { id -> allMedia.find { it.id == id } }
        if (
            Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            activeController?.isPlaying == true &&
            (currentMediaId?.startsWith("video:") == true || asset?.isVideo == true)
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(
                        controller?.videoSize?.let { size ->
                            Rational(
                                size.width.takeIf { it > 0 } ?: 16,
                                size.height.takeIf { it > 0 } ?: 9
                            )
                        } ?: Rational(16, 9)
                    )
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val hideLibraryChrome = isInPictureInPictureMode || isVideoFullscreen
        topBar.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        nowPlayingPanel.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        filterBar.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        sourceBar.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        hint.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        mediaList.visibility = if (hideLibraryChrome) View.GONE else View.VISIBLE
        fullscreen.visibility = when {
            isInPictureInPictureMode -> View.GONE
            isCurrentItemVideo() -> View.VISIBLE
            else -> View.GONE
        }
        if (isInPictureInPictureMode || isVideoFullscreen) {
            applyFullscreenStageLayout(true)
        } else {
            applyFullscreenStageLayout(false)
        }
    }

    private fun saveQueue() {
        preferences.edit().putStringSet(KEY_QUEUE, selectedIds.toSet()).apply()
    }

    private fun saveEnabledSources() {
        preferences.edit()
            .putStringSet(KEY_ENABLED_SOURCES, enabledSources.toSet())
            .apply()
    }

    private fun restoreLibraryPreferences() {
        val savedSort = preferences.getString(KEY_SORT, LibrarySort.NEWEST.name)
        currentSort = runCatching {
            LibrarySort.valueOf(savedSort.orEmpty())
        }.getOrDefault(LibrarySort.NEWEST)
    }

    private fun restoreQueue() {
        selectedIds += preferences.getStringSet(KEY_QUEUE, emptySet()).orEmpty()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressUpdater)
        controller?.removeListener(playerListener)
        playerView.player = null
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        scanner.shutdownNow()
        discAnimator.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class MediaAdapter(
        private val items: List<PiperMediaAsset>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): PiperMediaAsset = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_piper_media, parent, false)
            val item = getItem(position)

            view.findViewById<TextView>(R.id.mediaItemTitle).text = item.title
            val format = item.mimeType
                .substringAfter('/', "")
                .substringBefore(';')
                .uppercase()
                .ifBlank { if (item.isVideo) "VIDEO" else "AUDIO" }
            view.findViewById<TextView>(R.id.mediaItemSubtitle).text = listOf(
                getString(
                    if (item.isVideo) R.string.media_type_video
                    else R.string.media_type_audio
                ),
                format,
                formatDuration(item.durationMs),
                Formatter.formatShortFileSize(this@PiperMediaActivity, item.sizeBytes)
            ).joinToString(" \u00b7 ")
            view.findViewById<TextView>(R.id.mediaItemDetails).text = buildList {
                add(item.source)
                item.artist
                    .takeIf { it.isNotBlank() && it != item.source }
                    ?.let(::add)
                if (item.dateAddedMs > 0L) {
                    add(
                        DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT
                        ).format(Date(item.dateAddedMs))
                    )
                }
            }.joinToString(" \u00b7 ")
            view.findViewById<View>(R.id.mediaItemQueued).visibility =
                if (item.id in selectedIds) View.VISIBLE else View.GONE

            Glide.with(view)
                .load(item.artworkUri ?: item.uri)
                .placeholder(if (item.isVideo) R.drawable.ic_media_library else R.drawable.a3tn)
                .error(if (item.isVideo) R.drawable.ic_media_library else R.drawable.a3tn)
                .into(view.findViewById(R.id.mediaItemArtwork))
            return view
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        private const val PREFS_NAME = "piperos_media"
        private const val KEY_QUEUE = "selected_queue"
        private const val KEY_SORT = "library_sort"
        private const val KEY_ENABLED_SOURCES = "enabled_sources"
        private const val DEFAULT_SOURCE_COUNT = 3

        private val DEFAULT_SOURCES = listOf("Download", "PiperOS", "Camera")
        private val SOURCE_PRIORITY = listOf(
            "Download",
            "PiperOS",
            "Camera",
            "DCIM",
            "ScreenRecorder",
            "Zalo",
            "Instagram",
            "Messenger",
            "WhatsApp",
            "Telegram",
            "Music",
            "Movies"
        )
    }
}
