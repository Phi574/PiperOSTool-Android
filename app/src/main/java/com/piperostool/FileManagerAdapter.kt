package com.piperostool

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.Glide

class FileManagerAdapter(
    private val onClick: (ApkWorkspaceEntry) -> Unit,
    private val onLongClick: (ApkWorkspaceEntry) -> Unit,
    private val onSpecialIcon: (ApkWorkspaceEntry, ImageView) -> Unit
) : RecyclerView.Adapter<FileManagerAdapter.Holder>() {
    private var entries = emptyList<ApkWorkspaceEntry>()
    private var selectedPaths = emptySet<String>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.archiveEntryIcon)
        val name: TextView = view.findViewById(R.id.archiveEntryName)
        val meta: TextView = view.findViewById(R.id.archiveEntryMeta)
        val trailing: ImageView = view.findViewById(R.id.archiveEntryTrailing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_archive_entry, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val file = entry.extractedFile
        holder.name.text = entry.name
        holder.icon.contentDescription = entry.archivePath
        Glide.with(holder.icon).clear(holder.icon)
        val iconInset = dp(holder.icon, 6)
        holder.icon.setPadding(iconInset, iconInset, iconInset, iconInset)
        holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconResource = iconFor(entry)
        holder.icon.setImageResource(iconResource)
        styleIcon(holder.icon, entry, iconResource)
        if (file?.isFile == true && ApkMediaTypes.isVisualMedia(file.name)) {
            holder.icon.setPadding(0, 0, 0, 0)
            holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(holder.icon).load(file).dontAnimate().fitCenter().into(holder.icon)
        } else if (file != null && (file.isDirectory || file.extension.equals("apk", true))) {
            val specialInset = dp(holder.icon, 4)
            holder.icon.setPadding(specialInset, specialInset, specialInset, specialInset)
            onSpecialIcon(entry, holder.icon)
        }
        holder.meta.text = when {
            entry.isDirectory -> directoryMeta(file)
            else -> "${fileType(entry.name)} • ${Formatter.formatShortFileSize(holder.itemView.context, entry.size)}"
        }
        holder.trailing.setImageResource(R.drawable.ic_chevron_right)
        if (PiperUiPreferences.isModern(holder.itemView.context)) {
            holder.name.setTextColor(PiperModernUi.textColor(holder.itemView.context))
            holder.meta.setTextColor(PiperModernUi.secondaryTextColor(holder.itemView.context))
            holder.trailing.imageTintList = ColorStateList.valueOf(
                if (entry.archivePath in selectedPaths) PiperModernUi.accentColor(holder.itemView.context)
                else PiperModernUi.secondaryTextColor(holder.itemView.context)
            )
            holder.trailing.setImageResource(
                if (entry.archivePath in selectedPaths) R.drawable.check_circle
                else R.drawable.ic_chevron_right
            )
            PiperModernUi.apply(holder.itemView)
            if (entry.archivePath in selectedPaths) {
                holder.itemView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(
                        ColorUtils.blendARGB(
                            PiperModernUi.surfaceColor(holder.itemView.context),
                            PiperModernUi.accentColor(holder.itemView.context),
                            0.14f
                        )
                    )
                    cornerRadius = dp(holder.itemView, 8).toFloat()
                    setStroke(dp(holder.itemView, 1), PiperModernUi.accentColor(holder.itemView.context))
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
    }

    override fun getItemCount(): Int = entries.size

    fun submit(values: List<ApkWorkspaceEntry>) {
        entries = values
        notifyDataSetChanged()
    }

    fun updateSelection(paths: Set<String>) {
        selectedPaths = paths.toSet()
        notifyDataSetChanged()
    }

    private fun directoryMeta(file: java.io.File?): String {
        val count = file?.list()?.size
        return if (count == null) "Thư mục" else "Thư mục • $count mục"
    }

    private fun fileType(name: String): String = name.substringAfterLast('.', "Tệp").uppercase().ifBlank { "TỆP" }

    private fun iconFor(entry: ApkWorkspaceEntry): Int {
        if (entry.isDirectory) {
            return when (entry.name.lowercase()) {
                "music", "alarms", "ringtones", "notifications", "podcasts" -> R.drawable.ic_file_audio
                "movies", "video", "videos", "screenrecorder" -> R.drawable.ic_file_video
                "dcim", "camera", "pictures", "images", "screenshots" -> R.drawable.ic_file_image
                "download", "downloads" -> R.drawable.ic_browser_download
                "documents" -> R.drawable.ic_file_document
                "android", "data", "obb" -> R.drawable.system
                else -> R.drawable.module
            }
        }
        return when (ApkMediaTypes.extension(entry.name)) {
            "apk", "aab" -> R.drawable.apk
            in ApkMediaTypes.imageExtensions -> R.drawable.a2tn
            in ApkMediaTypes.videoExtensions -> R.drawable.nhacvideo
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> R.drawable.ic_media_library
            "zip", "7z", "gz", "bz2", "xz", "lz4", "zst", "zstd", "tar" -> R.drawable.backup
            "txt", "xml", "json", "pdf", "doc", "docx" -> R.drawable.details
            else -> R.drawable.packaget
        }
    }

    private fun styleIcon(icon: ImageView, entry: ApkWorkspaceEntry, iconResource: Int) {
        if (!PiperUiPreferences.isModern(icon.context)) return
        val density = icon.resources.displayMetrics.density
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(PiperModernUi.surfaceColor(icon.context))
            cornerRadius = 8f * density
            setStroke(density.toInt().coerceAtLeast(1), PiperModernUi.borderColor(icon.context))
        }
        val file = entry.extractedFile
        val usesRealArtwork = file?.isFile == true &&
            (ApkMediaTypes.isVisualMedia(file.name) || file.extension.equals("apk", true))
        val usesInstalledAppIcon = file?.isDirectory == true &&
            file.parentFile?.name in setOf("data", "obb") &&
            file.parentFile?.parentFile?.name.equals("Android", true)
        icon.imageTintList = if (usesRealArtwork || usesInstalledAppIcon) {
            null
        } else {
            ColorStateList.valueOf(PiperModernUi.accentColor(icon.context))
        }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
