package com.piperostool

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PiperMediaGalleryActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var title: TextView
    private lateinit var counter: TextView
    private lateinit var pager: ViewPager2
    private lateinit var adapter: GalleryAdapter
    private var paths = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_media_gallery)
        root = findViewById(R.id.mediaGalleryRoot)
        toolbar = findViewById(R.id.mediaGalleryToolbar)
        title = findViewById(R.id.mediaGalleryTitle)
        counter = findViewById(R.id.mediaGalleryCounter)
        pager = findViewById(R.id.mediaGalleryPager)
        applyInsets()
        findViewById<View>(R.id.btnMediaGalleryBack).setOnClickListener { finish() }

        val workspace = intent.getStringExtra(EXTRA_WORKSPACE_ROOT)?.let(ApkWorkspace::restore)
        val directFiles = intent.getBooleanExtra(EXTRA_DIRECT_FILES, false)
        paths = intent.getStringArrayListExtra(EXTRA_MEDIA_PATHS).orEmpty()
        if ((!directFiles && workspace == null) || paths.isEmpty()) {
            finish()
            return
        }
        adapter = GalleryAdapter(workspace, directFiles)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.activate(position)
                updateHeader(position)
            }
        })
        val initial = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(paths.indices)
        pager.setCurrentItem(initial, false)
        updateHeader(initial)
    }

    private fun updateHeader(position: Int) {
        title.text = paths[position].substringAfterLast('/')
        counter.text = "${position + 1} / ${paths.size}"
    }

    private fun applyInsets() {
        val initialTop = toolbar.paddingTop
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, initialTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, initialBottom + bars.bottom)
            insets
        }
    }

    override fun onStop() {
        adapter.pauseAll()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::adapter.isInitialized) adapter.activate(pager.currentItem)
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.releaseAll()
        super.onDestroy()
    }

    private inner class GalleryAdapter(
        private val workspace: ApkWorkspace?,
        private val directFiles: Boolean
    ) :
        RecyclerView.Adapter<GalleryAdapter.Holder>() {
        private val players = mutableMapOf<Int, ExoPlayer>()
        private var activePosition = 0

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.galleryPageImage)
            val playerView: PlayerView = view.findViewById(R.id.galleryPagePlayer)
            val progress: ProgressBar = view.findViewById(R.id.galleryPageProgress)
            var loadJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_piper_media_gallery, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.loadJob?.cancel()
            players.remove(position)?.release()
            holder.image.visibility = View.GONE
            holder.playerView.visibility = View.GONE
            holder.progress.visibility = View.VISIBLE
            val path = paths[position]
            holder.loadJob = lifecycleScope.launch(Dispatchers.IO) {
                val file = runCatching {
                    if (directFiles) File(path).takeIf { it.isFile }
                    else workspace?.previewFile(path)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (file == null || holder.bindingAdapterPosition != position || isDestroyed) return@withContext
                    holder.progress.visibility = View.GONE
                    if (ApkMediaTypes.isImage(path)) {
                        holder.image.visibility = View.VISIBLE
                        Glide.with(holder.image).load(file).fitCenter().into(holder.image)
                    } else {
                        showVideo(holder, position, file)
                    }
                }
            }
        }

        private fun showVideo(holder: Holder, position: Int, file: File) {
            holder.playerView.visibility = View.VISIBLE
            val player = ExoPlayer.Builder(this@PiperMediaGalleryActivity).build().also {
                it.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                it.prepare()
                it.playWhenReady = position == activePosition
            }
            holder.playerView.player = player
            players[position] = player
        }

        override fun onViewRecycled(holder: Holder) {
            holder.loadJob?.cancel()
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) players.remove(position)?.release()
            holder.playerView.player = null
            Glide.with(holder.image).clear(holder.image)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = paths.size

        fun activate(position: Int) {
            activePosition = position
            players.forEach { (index, player) -> player.playWhenReady = index == position }
        }

        fun pauseAll() = players.values.forEach { it.pause() }

        fun releaseAll() {
            players.values.forEach(ExoPlayer::release)
            players.clear()
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_ROOT = "workspace_root"
        const val EXTRA_MEDIA_PATHS = "media_paths"
        const val EXTRA_INITIAL_INDEX = "initial_index"
        const val EXTRA_DIRECT_FILES = "direct_files"
    }
}
