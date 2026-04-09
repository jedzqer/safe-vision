package com.safe.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetectionEditorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onBoxLongPress: ((String) -> Unit)? = null
    var onDataChanged: (() -> Unit)? = null

    private var items: MutableList<EditableDetection> = mutableListOf()
    private var imageMatrix: Matrix = Matrix()
    private var inverseMatrix: Matrix = Matrix()
    private var imageBounds: RectF = RectF()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((255 * 0.22f).toInt(), 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(12f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleRadius = dp(7f)
    private val labelPaddingH = dp(8f)
    private val labelPaddingV = dp(4f)
    private val minSizePx = dp(24f)

    private var activeId: String? = null
    private var resizeTargetId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var lastImageX = 0f
    private var lastImageY = 0f
    private var moved = false
    private var activeHandle: ResizeHandle = ResizeHandle.NONE
    private var longPressTriggered = false
    private var pendingLongPress: Runnable? = null

    enum class ResizeHandle { NONE, TL, TR, BL, BR }

    fun setEditorData(
        list: MutableList<EditableDetection>,
        matrix: Matrix,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        items = list
        imageMatrix = Matrix(matrix)
        imageBounds = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        imageMatrix.invert(inverseMatrix)
        invalidate()
    }

    fun setImageMatrix(matrix: Matrix) {
        imageMatrix = Matrix(matrix)
        imageMatrix.invert(inverseMatrix)
        invalidate()
    }

    fun removeById(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            onDataChanged?.invoke()
            invalidate()
        }
    }

    fun enableResizeMode(id: String) {
        resizeTargetId = id
        activeId = id
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            val viewRect = mapRectToView(item.rect)
            canvas.drawRect(viewRect, fillPaint)
            canvas.drawRect(viewRect, borderPaint)
            drawLabel(canvas, item.label, viewRect)
            if (item.id == resizeTargetId) {
                drawHandles(canvas, viewRect)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                moved = false
                downX = event.x
                downY = event.y
                val imagePoint = mapPointToImage(event.x, event.y)
                val hit = findHitItem(imagePoint.x, imagePoint.y)
                activeId = hit?.id
                activeHandle = hit?.let {
                    if (it.id == resizeTargetId) resolveHandle(it, event.x, event.y) else ResizeHandle.NONE
                } ?: ResizeHandle.NONE
                lastImageX = imagePoint.x
                lastImageY = imagePoint.y
                scheduleLongPress()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = activeId ?: return true
                val imagePoint = mapPointToImage(event.x, event.y)
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    moved = true
                    clearPendingLongPress()
                }
                val idx = items.indexOfFirst { it.id == id }
                if (idx < 0) return true
                val current = items[idx]
                val updatedRect = if (activeHandle == ResizeHandle.NONE) {
                    val dx = imagePoint.x - lastImageX
                    val dy = imagePoint.y - lastImageY
                    RectF(current.rect).apply { offset(dx, dy) }
                } else {
                    resizeRect(current.rect, imagePoint.x, imagePoint.y, activeHandle)
                }
                val clamped = clampRect(updatedRect)
                items[idx] = current.copy(rect = clamped)
                lastImageX = imagePoint.x
                lastImageY = imagePoint.y
                onDataChanged?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clearPendingLongPress()
                activeHandle = ResizeHandle.NONE
            }
        }
        return true
    }

    private fun drawLabel(canvas: Canvas, label: String, rect: RectF) {
        val display = DetectionConfig.getDisplayName(label)
        val textWidth = labelTextPaint.measureText(display)
        val textHeight = labelTextPaint.fontMetrics.run { bottom - top }
        val left = rect.left
        val top = max(0f, rect.top - textHeight - labelPaddingV * 2f)
        val bg = RectF(left, top, left + textWidth + labelPaddingH * 2f, top + textHeight + labelPaddingV * 2f)
        canvas.drawRoundRect(bg, dp(6f), dp(6f), labelBgPaint)
        val baseline = bg.top + labelPaddingV - labelTextPaint.fontMetrics.top
        canvas.drawText(display, bg.left + labelPaddingH, baseline, labelTextPaint)
    }

    private fun drawHandles(canvas: Canvas, rect: RectF) {
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    private fun mapRectToView(src: RectF): RectF {
        val out = RectF(src)
        imageMatrix.mapRect(out)
        return out
    }

    private fun mapPointToImage(x: Float, y: Float): android.graphics.PointF {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return android.graphics.PointF(pts[0], pts[1])
    }

    private fun findHitItem(imageX: Float, imageY: Float): EditableDetection? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item.rect.contains(imageX, imageY)) return item
        }
        return null
    }

    private fun resolveHandle(item: EditableDetection, touchX: Float, touchY: Float): ResizeHandle {
        val vr = mapRectToView(item.rect)
        val r = handleRadius * 2f
        fun near(x: Float, y: Float) = abs(touchX - x) <= r && abs(touchY - y) <= r
        return when {
            near(vr.left, vr.top) -> ResizeHandle.TL
            near(vr.right, vr.top) -> ResizeHandle.TR
            near(vr.left, vr.bottom) -> ResizeHandle.BL
            near(vr.right, vr.bottom) -> ResizeHandle.BR
            else -> ResizeHandle.NONE
        }
    }

    private fun resizeRect(src: RectF, x: Float, y: Float, handle: ResizeHandle): RectF {
        val r = RectF(src)
        when (handle) {
            ResizeHandle.TL -> {
                r.left = min(x, r.right - minSizePx)
                r.top = min(y, r.bottom - minSizePx)
            }
            ResizeHandle.TR -> {
                r.right = max(x, r.left + minSizePx)
                r.top = min(y, r.bottom - minSizePx)
            }
            ResizeHandle.BL -> {
                r.left = min(x, r.right - minSizePx)
                r.bottom = max(y, r.top + minSizePx)
            }
            ResizeHandle.BR -> {
                r.right = max(x, r.left + minSizePx)
                r.bottom = max(y, r.top + minSizePx)
            }
            ResizeHandle.NONE -> Unit
        }
        return r
    }

    private fun clampRect(src: RectF): RectF {
        val w = src.width().coerceAtLeast(minSizePx)
        val h = src.height().coerceAtLeast(minSizePx)
        var left = src.left
        var top = src.top
        if (left < imageBounds.left) left = imageBounds.left
        if (top < imageBounds.top) top = imageBounds.top
        if (left + w > imageBounds.right) left = imageBounds.right - w
        if (top + h > imageBounds.bottom) top = imageBounds.bottom - h
        return RectF(left, top, left + w, top + h)
    }

    private fun scheduleLongPress() {
        clearPendingLongPress()
        val id = activeId ?: return
        pendingLongPress = Runnable {
            if (!moved) {
                longPressTriggered = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onBoxLongPress?.invoke(id)
            }
        }
        postDelayed(pendingLongPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun clearPendingLongPress() {
        pendingLongPress?.let { removeCallbacks(it) }
        pendingLongPress = null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
