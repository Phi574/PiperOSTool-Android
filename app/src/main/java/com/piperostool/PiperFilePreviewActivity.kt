package com.piperostool

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import java.io.File
import java.util.Locale

internal enum class FilePreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, UNSUPPORTED }

class PiperFilePreviewActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var file: File
    private var player: ExoPlayer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_file_preview)
        root = findViewById(R.id.filePreviewRoot)
        toolbar = findViewById(R.id.filePreviewToolbar)
        applyInsets()

        file = File(intent.getStringExtra(EXTRA_FILE_PATH).orEmpty())
        if (!file.isFile) {
            Toast.makeText(this, "Tệp không còn tồn tại", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<View>(R.id.btnFilePreviewBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFilePreviewExternal).setOnClickListener { openExternal() }
        findViewById<View>(R.id.btnFilePreviewEdit).setOnClickListener {
            startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
            )
        }
        findViewById<TextView>(R.id.filePreviewTitle).text = file.name
        findViewById<TextView>(R.id.filePreviewText)
            .setTag(R.id.piper_auto_font_ignore, true)
        findViewById<TextView>(R.id.filePreviewMeta).text =
            "${typeLabel(file)} • ${Formatter.formatShortFileSize(this, file.length())}"
        showPreview()
    }

    private fun applyInsets() {
        val initialTop = toolbar.paddingTop
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbar.paddingLeft,
                initialTop + bars.top,
                toolbar.paddingRight,
                toolbar.paddingBottom
            )
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, initialBottom + bars.bottom)
            insets
        }
    }

    private fun showPreview() {
        when (previewKind(file.name)) {
            FilePreviewKind.IMAGE -> showImage()
            FilePreviewKind.VIDEO -> showMedia(false)
            FilePreviewKind.AUDIO -> showMedia(true)
            FilePreviewKind.PDF -> showPdf()
            FilePreviewKind.TEXT -> {
                if (isProbablyText(file)) showText()
                else showUnsupported("Tệp này dùng định dạng văn bản nhị phân và không thể hiển thị như text.")
            }
            FilePreviewKind.UNSUPPORTED -> showUnsupported()
        }
    }

    private fun showImage() {
        val image = findViewById<ImageView>(R.id.filePreviewImage)
        image.visibility = View.VISIBLE
        Glide.with(this).load(file).fitCenter().into(image)
    }

    private fun showMedia(audioOnly: Boolean) {
        val playerView = findViewById<PlayerView>(R.id.filePreviewPlayer)
        playerView.visibility = View.VISIBLE
        findViewById<View>(R.id.filePreviewAudioArt).visibility =
            if (audioOnly) View.VISIBLE else View.GONE
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            it.prepare()
            it.playWhenReady = false
        }
    }

    private fun showText() {
        findViewById<View>(R.id.btnFilePreviewEdit).visibility = View.VISIBLE
        val text = findViewById<TextView>(R.id.filePreviewText)
        text.visibility = View.VISIBLE
        text.text = if (file.length() > MAX_TEXT_BYTES) {
            file.inputStream().bufferedReader().use { reader ->
                val buffer = CharArray(MAX_TEXT_BYTES.toInt())
                val count = reader.read(buffer)
                String(buffer, 0, count.coerceAtLeast(0)) + "\n\n--- Nội dung đã được rút gọn ---"
            }
        } else {
            file.readText()
        }
    }

    private fun showPdf() {
        runCatching {
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(requireNotNull(pdfDescriptor))
            require(pdfRenderer!!.pageCount > 0) { "PDF không có trang" }
            findViewById<View>(R.id.filePreviewPdfPanel).visibility = View.VISIBLE
            findViewById<View>(R.id.btnPdfPrevious).setOnClickListener {
                if (pdfPage > 0) renderPdfPage(pdfPage - 1)
            }
            findViewById<View>(R.id.btnPdfNext).setOnClickListener {
                if (pdfPage + 1 < pdfRenderer!!.pageCount) renderPdfPage(pdfPage + 1)
            }
            renderPdfPage(0)
        }.onFailure {
            showUnsupported("Không đọc được PDF: ${it.message}")
        }
    }

    private fun renderPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        renderer.openPage(index).use { page ->
            val maxWidth = resources.displayMetrics.widthPixels.coerceAtMost(1600)
            val scale = maxWidth.toFloat() / page.width
            val bitmap = Bitmap.createBitmap(
                maxWidth,
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            findViewById<ImageView>(R.id.filePreviewPdfImage).setImageBitmap(bitmap)
        }
        pdfPage = index
        findViewById<TextView>(R.id.filePreviewPdfCounter).text =
            "Trang ${index + 1} / ${renderer.pageCount}"
        findViewById<View>(R.id.btnPdfPrevious).isEnabled = index > 0
        findViewById<View>(R.id.btnPdfNext).isEnabled = index + 1 < renderer.pageCount
    }

    private fun showUnsupported(message: String = "Định dạng này chưa có trình xem nội bộ.") {
        findViewById<TextView>(R.id.filePreviewUnsupported).apply {
            visibility = View.VISIBLE
            text = "$message\n\n${file.name}\n${Formatter.formatShortFileSize(this@PiperFilePreviewActivity, file.length())}"
        }
    }

    private fun isProbablyText(file: File): Boolean = file.inputStream().use { input ->
        val sample = ByteArray(1024)
        val count = input.read(sample)
        count <= 0 || sample.take(count).none { it == 0.toByte() }
    }

    private fun openExternal() {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(this, "Không có ứng dụng phù hợp", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        pdfRenderer?.close()
        pdfDescriptor?.close()
        super.onDestroy()
    }

    private fun mimeType(file: File): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun typeLabel(file: File): String =
        file.extension.uppercase().ifBlank { "TỆP" }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
        private val TEXT_EXTENSIONS = setOf(
            "xml", "json", "txt", "html", "htm", "css", "js", "md", "properties", "yml", "yaml", "log", "csv"
        )

        internal fun previewKind(name: String): FilePreviewKind =
            when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                in IMAGE_EXTENSIONS -> FilePreviewKind.IMAGE
                in VIDEO_EXTENSIONS -> FilePreviewKind.VIDEO
                in AUDIO_EXTENSIONS -> FilePreviewKind.AUDIO
                "pdf" -> FilePreviewKind.PDF
                in TEXT_EXTENSIONS -> FilePreviewKind.TEXT
                else -> FilePreviewKind.UNSUPPORTED
            }
    }
}
