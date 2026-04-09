package com.safe.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Simple circular countdown indicator with center text.
 * Draws from 12 o'clock, shrinking clockwise as remaining time decreases.
 */
class CircularCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#40FFFFFF")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 8f
        color = ContextCompat.getColor(context, android.R.color.white)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val arcBounds = RectF()

    private var totalSeconds: Int = 1
    private var remainingSeconds: Int = 0

    fun setCountdown(totalSeconds: Int, remainingSeconds: Int) {
        this.totalSeconds = totalSeconds.coerceAtLeast(1)
        this.remainingSeconds = remainingSeconds.coerceIn(0, this.totalSeconds)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val radiusInset = trackPaint.strokeWidth / 2f + 2f
        arcBounds.set(
            (width - size) / 2f + radiusInset,
            (height - size) / 2f + radiusInset,
            (width + size) / 2f - radiusInset,
            (height + size) / 2f - radiusInset
        )

        // Track circle
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        // Progress arc (remaining)
        val fraction = if (totalSeconds <= 0) 0f else remainingSeconds.toFloat() / totalSeconds
        val sweep = 360f * fraction
        if (sweep > 0f) {
            canvas.drawArc(arcBounds, -90f, sweep, false, progressPaint)
        }

        // Center text
        val text = remainingSeconds.coerceAtLeast(0).toString()
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, width / 2f, textY, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default to a 48dp square if unspecified
        val defaultSize = (48 * resources.displayMetrics.density).toInt()
        val resolvedWidth = resolveSize(defaultSize, widthMeasureSpec)
        val resolvedHeight = resolveSize(defaultSize, heightMeasureSpec)
        val size = minOf(resolvedWidth, resolvedHeight)
        setMeasuredDimension(size, size)
    }
}
