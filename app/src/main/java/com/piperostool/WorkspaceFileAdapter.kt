package com.piperostool

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class WorkspaceFileAdapter(
    private val onClick: (ApkWorkspaceEntry) -> Unit,
    private val onLongClick: (ApkWorkspaceEntry) -> Unit = {},
    private val onThumbnailRequested: (ApkWorkspaceEntry, ImageView) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<WorkspaceFileAdapter.Holder>() {
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
        holder.name.text = entry.name
        holder.icon.contentDescription = entry.archivePath
        Glide.with(holder.icon).clear(holder.icon)
        holder.icon.setColorFilter(null)
        val iconInset = dp(holder.icon, 6)
        holder.icon.setPadding(iconInset, iconInset, iconInset, iconInset)
        holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        holder.icon.setImageResource(if (entry.isDirectory) R.drawable.module else fileIcon(entry.name))
        if (!entry.isDirectory && ApkMediaTypes.isVisualMedia(entry.name)) {
            holder.icon.setPadding(0, 0, 0, 0)
            holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
            entry.extractedFile?.takeIf { it.isFile }?.let { file ->
                Glide.with(holder.icon).load(file).dontAnimate().fitCenter().into(holder.icon)
            } ?: onThumbnailRequested(entry, holder.icon)
        }

        val selected = entry.archivePath in selectedPaths
        holder.itemView.setBackgroundColor(if (selected) 0x2534D399 else android.graphics.Color.TRANSPARENT)
        holder.trailing.setImageResource(if (selected) R.drawable.check_circle else R.drawable.ic_chevron_right)
        holder.trailing.setColorFilter(
            ContextCompat.getColor(holder.itemView.context, if (selected) R.color.green_neon else android.R.color.darker_gray)
        )
        holder.meta.text = when {
            entry.isDirectory && entry.childCount > 0 -> "Thư mục • ${entry.childCount} mục"
            entry.isDirectory -> "Thư mục"
            entry.extractedFile != null -> "Đã chỉnh / giải nén • ${Formatter.formatShortFileSize(holder.itemView.context, entry.size)}"
            else -> "${fileType(entry.name)} • ${Formatter.formatShortFileSize(holder.itemView.context, entry.size)}"
        }
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
        PiperAutoFont.apply(holder.itemView)
    }

    override fun getItemCount(): Int = entries.size

    fun submit(values: List<ApkWorkspaceEntry>) {
        entries = values
        notifyDataSetChanged()
    }

    fun setSelected(paths: Set<String>) {
        selectedPaths = paths
        notifyDataSetChanged()
    }

    private fun fileType(name: String): String = name.substringAfterLast('.', "Tệp").uppercase().ifBlank { "TỆP" }

    private fun fileIcon(name: String): Int = when (ApkMediaTypes.extension(name)) {
        "apk", "aab" -> R.drawable.apk
        "xml", "json", "txt", "properties", "yml", "yaml" -> R.drawable.details
        "dex" -> R.drawable.ic_terminal
        "so" -> R.drawable.system
        in ApkMediaTypes.imageExtensions -> R.drawable.a2tn
        in ApkMediaTypes.videoExtensions -> R.drawable.nhacvideo
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> R.drawable.ic_media_library
        "pdf" -> R.drawable.details
        else -> R.drawable.backup
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
