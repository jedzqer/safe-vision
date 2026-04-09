package com.safe.vision

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 批量处理结果列表适配器
 */
class BatchResultsAdapter : RecyclerView.Adapter<BatchResultsAdapter.ViewHolder>() {
    
    private var results: List<BatchProcessingManager.BatchProcessingResult> = emptyList()
    
    fun updateResults(newResults: List<BatchProcessingManager.BatchProcessingResult>) {
        results = newResults
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_result, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(results[position])
    }
    
    override fun getItemCount(): Int = results.size

    private fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int) =
        BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, this)
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
            inJustDecodeBounds = false
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(filePath, this)
        }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.resultImageView)
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val detectionsText: TextView = itemView.findViewById(R.id.detectionsText)
        
        fun bind(result: BatchProcessingManager.BatchProcessingResult) {
            fileNameText.text = result.task.fileName
            val context = itemView.context
            
            if (result.success) {
                statusText.text = context.getString(R.string.batch_result_success)
                statusText.setTextColor(context.getColor(android.R.color.holo_green_dark))
                
                if (result.hasDetections) {
                    detectionsText.text = context.getString(
                        R.string.batch_result_detected_count,
                        result.detections.size
                    )
                    detectionsText.setTextColor(context.getColor(android.R.color.holo_orange_dark))
                } else {
                    detectionsText.text = context.getString(R.string.batch_result_no_detection)
                    detectionsText.setTextColor(context.getColor(android.R.color.holo_blue_dark))
                }
                
                // 显示图片缩略图
                result.imageFile?.let { file ->
                    imageView.tag = file.absolutePath
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val targetWidth = imageView.width.takeIf { it > 0 }
                                ?: (imageView.resources.displayMetrics.density * 60).toInt()
                            val targetHeight = imageView.height.takeIf { it > 0 }
                                ?: (imageView.resources.displayMetrics.density * 60).toInt()
                            val bitmap = decodeSampledBitmap(file.absolutePath, targetWidth, targetHeight)
                            withContext(Dispatchers.Main) {
                                if (imageView.tag != file.absolutePath) return@withContext
                                if (bitmap != null) {
                                    imageView.setImageBitmap(bitmap)
                                } else {
                                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                        }
                    }
                }
            } else {
                statusText.text = context.getString(R.string.batch_result_failed)
                statusText.setTextColor(context.getColor(android.R.color.holo_red_dark))
                detectionsText.text = result.errorMessage ?: context.getString(R.string.common_unknown_error)
                detectionsText.setTextColor(context.getColor(android.R.color.holo_red_dark))
                
                // 显示错误占位图
                imageView.setImageResource(android.R.drawable.ic_dialog_alert)
            }
        }
    }
}
