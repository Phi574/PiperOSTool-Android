package com.piperostool

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class PiperMediaAsset(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val source: String,
    val relativePath: String,
    val artworkUri: Uri? = null
) {
    fun toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setDisplayTitle(title)
            .setMediaType(
                if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO
                else MediaMetadata.MEDIA_TYPE_MUSIC
            )
            .apply { artworkUri?.let(::setArtworkUri) }
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(metadata)
            .build()
    }
}
