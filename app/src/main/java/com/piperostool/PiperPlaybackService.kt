package com.piperostool

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
class PiperPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enrichedMediaIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        enrichArtwork(mediaItem)
                    }
                })
            }

        val openPlayerIntent = Intent(this, PiperMediaActivity::class.java)
        val sessionActivity = PendingIntent.getActivity(
            this,
            4102,
            openPlayerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repeatCommand = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
        val shuffleCommand = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
        val customButtons = listOf(
            CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                .setDisplayName(getString(R.string.media_repeat))
                .setSessionCommand(repeatCommand)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                .setDisplayName(getString(R.string.media_shuffle))
                .setSessionCommand(shuffleCommand)
                .build()
        )
        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(repeatCommand)
                    .add(shuffleCommand)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_REPEAT -> {
                        session.player.repeatMode = when (session.player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                    ACTION_SHUFFLE -> {
                        session.player.shuffleModeEnabled =
                            !session.player.shuffleModeEnabled
                    }
                }
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(callback)
            .setCustomLayout(customButtons)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MediaSessionService keeps itself alive while playback is active.
        if (mediaSession?.player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        artworkExecutor.shutdownNow()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun enrichArtwork(mediaItem: MediaItem?) {
        val item = mediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri ?: return
        if (!enrichedMediaIds.add(mediaId) || item.mediaMetadata.artworkData != null) return

        artworkExecutor.execute {
            val artworkData = runCatching {
                if (mediaId.startsWith("video:")) {
                    loadVideoThumbnail(uri)
                } else {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(this@PiperPlaybackService, uri)
                            embeddedPicture
                        } finally {
                            release()
                        }
                    }
                }
            }.getOrNull() ?: return@execute

            mainHandler.post {
                val player = mediaSession?.player ?: return@post
                val index = (0 until player.mediaItemCount)
                    .firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
                    ?: return@post
                val current = player.getMediaItemAt(index)
                val metadata = current.mediaMetadata.buildUpon()
                    .setArtworkData(
                        artworkData,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
                    )
                    .build()
                player.replaceMediaItem(
                    index,
                    current.buildUpon().setMediaMetadata(metadata).build()
                )
            }
        }
    }

    private fun loadVideoThumbnail(uri: android.net.Uri): ByteArray? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.loadThumbnail(uri, Size(640, 360), null)
        } else {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(this@PiperPlaybackService, uri)
                    getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    release()
                }
            }
        } ?: return null

        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    companion object {
        private const val ACTION_REPEAT = "com.piperostool.media.REPEAT"
        private const val ACTION_SHUFFLE = "com.piperostool.media.SHUFFLE"
    }
}
