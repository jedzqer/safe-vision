package com.safe.vision

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class OutputFolderAdapter(
    private val thumbnailCacheManager: ThumbnailCacheManager,
    private val onClick: (OutputFolderItem) -> Unit,
    private val onLongClick: (OutputFolderItem) -> Unit
) : RecyclerView.Adapter<OutputFolderAdapter.OutputFolderViewHolder>() {

    private val items = mutableListOf<OutputFolderItem>()

    fun submitList(newItems: List<OutputFolderItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].name == newItems[newPos].name
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OutputFolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_output_folder, parent, false)
        return OutputFolderViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: OutputFolderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: OutputFolderViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    inner class OutputFolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val indicatorDot: View = view.findViewById(R.id.indicatorDot)
        private val folderName: TextView = view.findViewById(R.id.outputFolderName)
        private val folderCount: TextView = view.findViewById(R.id.outputFolderCount)
        private val preview1: ImageView = view.findViewById(R.id.outputPreview1)
        private val preview2: ImageView = view.findViewById(R.id.outputPreview2)
        private var bindToken = 0L
        private var bindPath: String? = null

        fun bind(item: OutputFolderItem) {
            bindToken++
            val token = bindToken
            bindPath = item.name
            folderName.text = item.name
            folderCount.text = itemView.context.getString(R.string.gallery_image_count, item.count)
            indicatorDot.visibility = if (item.isSelected) View.VISIBLE else View.GONE
            bindPreview(preview1, item.previewFiles.getOrNull(0), token)
            bindPreview(preview2, item.previewFiles.getOrNull(1), token)

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                if (!item.isSystem) {
                    onLongClick(item)
                    true
                } else {
                    false
                }
            }
        }

        fun unbind() {
            bindPath = null
            bindToken++
        }

        private fun bindPreview(imageView: ImageView, file: java.io.File?, bindToken: Long) {
            val placeholder = android.R.drawable.ic_menu_gallery
            imageView.setImageResource(placeholder)
            if (file == null) return
            thumbnailCacheManager.load(file, ThumbnailCacheManager.MediaKind.IMAGE, 160) { bitmap ->
                if (this.bindToken != bindToken || bindPath == null) return@load
                if (bitmap != null) imageView.setImageBitmap(bitmap) else imageView.setImageResource(placeholder)
            }
        }
    }
}
