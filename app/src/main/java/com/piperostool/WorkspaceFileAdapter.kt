package com.piperostool

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class WorkspaceFileAdapter(
    private val onClick: (ApkWorkspaceEntry) -> Unit,
    private val onLongClick: (ApkWorkspaceEntry) -> Unit = {}
) : RecyclerView.Adapter<WorkspaceFileAdapter.Holder>() {
    private var entries = emptyList<ApkWorkspaceEntry>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.archiveEntryIcon)
        val name: TextView = view.findViewById(R.id.archiveEntryName)
        val meta: TextView = view.findViewById(R.id.archiveEntryMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_archive_entry, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.name.text = entry.name
        holder.icon.setImageResource(if (entry.isDirectory) R.drawable.module else fileIcon(entry.name))
        holder.meta.text = when {
            entry.isDirectory -> "Thư mục"
            entry.extractedFile != null ->
                "Đã giải nén • ${Formatter.formatShortFileSize(holder.itemView.context, entry.size)}"
            else -> "Trong APK • ${Formatter.formatShortFileSize(holder.itemView.context, entry.size)}"
        }
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener {
            onLongClick(entry)
            true
        }
    }

    override fun getItemCount(): Int = entries.size

    fun submit(values: List<ApkWorkspaceEntry>) {
        entries = values
        notifyDataSetChanged()
    }

    private fun fileIcon(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        "apk", "aab" -> R.drawable.apk
        "xml", "json", "txt", "properties", "yml", "yaml" -> R.drawable.details
        "dex" -> R.drawable.ic_terminal
        "so" -> R.drawable.system
        "png", "jpg", "jpeg", "webp" -> R.drawable.a2tn
        else -> R.drawable.backup
    }
}
