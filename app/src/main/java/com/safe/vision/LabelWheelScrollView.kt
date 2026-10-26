package com.safe.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keeps chips tappable; a vertical drag owns the entire gesture, including release. */
class LabelWheelScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {
    var labels: () -> List<Pair<String, String>> = { emptyList() }
    var isLabelEnabled: (String) -> Boolean = { false }
    var onLabelSelected: ((String) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var columnAnchorX = 0f
    private var popup: PopupWindow? = null
    private var wheel: WheelView? = null
    private var items = emptyList<Pair<String, String>>()
    private var dragging = false
    private var cancelled = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dismissWheel()
            downY = event.y
            downX = event.x
            cancelled = false
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        // A second pointer cancels selection rather than accidentally opening an editor.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelled = true
            dismissWheel()
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dragging || cancelled) return true
        return beginDrag(event)
    }

    private fun beginDrag(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            abs(event.y - downY) > slop && abs(event.y - downY) > abs(event.x - downX)) {
            items = labels().toList()
            if (items.isNotEmpty()) {
                dragging = true
                showWheel()
                // Start with the middle item centered; subsequent movement scrolls the wheel.
                downY = event.y
                columnAnchorX = event.x
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cancelled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) beginDrag(event)
                updateSelection(event)
            }
            MotionEvent.ACTION_UP -> {
                updateSelection(event)
                val selected = if (dragging) wheel?.selectedLabel else null
                dismissWheel()
                if (selected != null) onLabelSelected?.invoke(selected) else performClick()
            }
            MotionEvent.ACTION_CANCEL -> dismissWheel()
        }
        return true
    }

    private fun updateSelection(event: MotionEvent) {
        wheel?.let {
            it.moveBy((downY - event.y) / it.rowHeight)
            downY = event.y
            val horizontal = event.x - columnAnchorX
            if (abs(horizontal) > maxOf(slop.toFloat(), 24f * resources.displayMetrics.density)) {
                it.selectColumn(if (horizontal > 0f) 1 else 0)
                columnAnchorX = event.x
            }
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun showWheel() {
        val density = resources.displayMetrics.density
        val frame = Rect().also { getWindowVisibleDisplayFrame(it) }
        val content = WheelView(context, labelWheelRows(items))
        wheel = content
        popup = PopupWindow(
            content,
            (frame.width() - 32 * density).roundToInt().coerceAtLeast(1),
            (frame.height() * 0.68f).roundToInt().coerceAtLeast(1),
            false
        ).apply {
            // Keep the original finger stream in this view, even outside its bounds.
            isTouchable = false
            isOutsideTouchable = false
            elevation = 16 * density
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(themeColor(R.attr.svColorSurface))
                cornerRadius = 24 * density
                setStroke(density.roundToInt().coerceAtLeast(1), themeColor(R.attr.svColorBorder))
            })
            // Visible frame excludes system bars/IME; convert screen coordinates to window coordinates.
            val screen = IntArray(2).also { this@LabelWheelScrollView.getLocationOnScreen(it) }
            val window = IntArray(2).also { this@LabelWheelScrollView.getLocationInWindow(it) }
            showAtLocation(this@LabelWheelScrollView, Gravity.TOP or Gravity.LEFT,
                frame.left + (frame.width() - width) / 2 - (screen[0] - window[0]),
                frame.top + (frame.height() - height) / 2 - (screen[1] - window[1]))
        }
        content.alpha = 0f
        content.scaleX = 0.94f
        content.scaleY = 0.94f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    fun dismissWheel() {
        cancelled = true
        wheel?.animate()?.cancel()
        popup?.dismiss()
        popup = null
        wheel = null
        dragging = false
        items = emptyList()
    }

    override fun onDetachedFromWindow() {
        dismissWheel()
        super.onDetachedFromWindow()
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private inner class WheelView(context: Context, private val rows: List<List<Pair<String, String>>>) : View(context) {
        val rowHeight = 56f * resources.displayMetrics.density
        private var position = (rows.size / 2).toFloat()
        val selectedIndex: Int get() = position.roundToInt().coerceIn(rows.indices)
        private var column = 0
        val selectedLabel: String get() = rows[selectedIndex].let { it[if (it.size == 1) 0 else column].first }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val accent = themeColor(R.attr.svColorAccent)
        private val text = themeColor(R.attr.svColorTextSecondary)
        private val border = themeColor(R.attr.svColorBorder)
        private val band = RectF()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(ColorUtils.setAlphaComponent(accent, 70),
                    ColorUtils.setAlphaComponent(accent, 25), ColorUtils.setAlphaComponent(accent, 0)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        }

        fun moveBy(delta: Float) {
            val old = selectedIndex
            position = (position + delta).coerceIn(0f, rows.lastIndex.toFloat())
            if (old != selectedIndex) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

        fun selectColumn(value: Int) {
            val old = selectedLabel
            column = value
            if (old != selectedLabel) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val center = height / 2f
            paint.style = Paint.Style.FILL
            val selectedEnabled = isLabelEnabled(selectedLabel)
            val selectedRow = rows[selectedIndex]
            val cellWidth = (width - 24 * density) / 2f
            val bandLeft = if (selectedRow.size == 1) 12 * density else 12 * density + column * cellWidth
            val bandRight = if (selectedRow.size == 1) width - 12 * density else bandLeft + cellWidth
            band.set(bandLeft, center - rowHeight / 2, bandRight, center + rowHeight / 2)
            if (selectedEnabled) {
                paint.color = accent
                paint.alpha = 18
                canvas.drawRoundRect(band, 14 * density, 14 * density, paint)
                val save = canvas.save()
                canvas.clipRect(bandLeft, 0f, bandRight, height.toFloat())
                canvas.translate(band.centerX(), center)
                canvas.scale(band.width() * 0.6f, rowHeight * 1.5f)
                canvas.drawCircle(0f, 0f, 1f, glowPaint)
                canvas.restoreToCount(save)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = if (selectedEnabled) accent else border
            paint.alpha = if (selectedEnabled) 100 else 180
            canvas.drawRoundRect(band, 14 * density, 14 * density, paint)
            paint.style = Paint.Style.FILL
            for (index in rows.indices) {
                val distance = index - position
                val y = center + distance * rowHeight
                if (y < -rowHeight || y > height + rowHeight) continue
                val prominence = (1f - abs(distance) / (height / (2f * rowHeight)).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val row = rows[index]
                row.forEachIndexed { side, label ->
                    val selected = index == selectedIndex && (row.size == 1 || side == column)
                    paint.textSize = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 14f + 6f * prominence, resources.displayMetrics
                    )
                    paint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    val enabled = isLabelEnabled(label.first)
                    paint.color = if (enabled) accent else text
                    paint.alpha = (255 * (0.2f + 0.8f * prominence)).roundToInt()
                    val available = (if (row.size == 1) width - 40 * density else cellWidth - 16 * density)
                        .coerceAtLeast(1f)
                    val measured = paint.measureText(label.second)
                    if (measured > available) paint.textSize *= available / measured
                    val baseline = y - 9 * density - (paint.ascent() + paint.descent()) / 2f
                    val x = if (row.size == 1) width / 2f else 12 * density + (side + 0.5f) * cellWidth
                    canvas.drawText(label.second, x, baseline, paint)
                    // Shape also conveys state for users who cannot distinguish the theme colors.
                    paint.color = if (enabled) accent else text
                    paint.alpha = (255 * (0.45f + 0.55f * prominence)).roundToInt()
                    paint.style = if (enabled) Paint.Style.FILL else Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * density
                    canvas.drawCircle(x, y + 15 * density, 3f * density, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }
}

/** Pair model variants by stable IDs, independently of translated/custom display names. */
internal fun labelWheelRows(labels: List<Pair<String, String>>): List<List<Pair<String, String>>> =
    labels.groupBy { (id, _) -> id.removeSuffix("_COVERED").removeSuffix("_EXPOSED") }
        .values.map { group ->
            group.sortedBy { (id, _) -> if (id.endsWith("_COVERED")) 0 else 1 }
        }
