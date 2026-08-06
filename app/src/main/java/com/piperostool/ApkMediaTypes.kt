package com.piperostool

import java.util.Locale

object ApkMediaTypes {
    val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico")
    val videoExtensions = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")

    fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US)

    fun isImage(name: String): Boolean = extension(name) in imageExtensions
    fun isVideo(name: String): Boolean = extension(name) in videoExtensions
    fun isVisualMedia(name: String): Boolean = isImage(name) || isVideo(name)
}
