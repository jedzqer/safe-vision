package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

class ScreenMaskOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var overlayBitmap: Bitmap? = null
    private var contentOffsetX: Float = 0f
    private var contentOffsetY: Float = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        overlayBitmap?.takeIf { !it.isRecycled }?.let {
            canvas.drawBitmap(it, -contentOffsetX, -contentOffsetY, null)
        }
    }

    fun setOverlayBitmap(bitmap: Bitmap?) {
        if (overlayBitmap === bitmap) {
            invalidate()
            return
        }
        overlayBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        overlayBitmap = bitmap
        invalidate()
    }

    fun setContentOffset(x: Int, y: Int) {
        val newX = x.toFloat()
        val newY = y.toFloat()
        if (contentOffsetX == newX && contentOffsetY == newY) return
        contentOffsetX = newX
        contentOffsetY = newY
        invalidate()
    }

    fun release() {
        setOverlayBitmap(null)
    }
}
