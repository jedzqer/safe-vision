package com.safe.vision

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout

/** Text navigation lit by the selection indicator's diffuse, theme-colored glow. */
class GlowTabLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TabLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var pulse = 0f
    private var spread = 1f
    private var pressedTab = -1
    private var touching = false

    init {
        tabRippleColor = null
        val accent = themeColor(R.attr.svColorAccent)
        val card = themeColor(R.attr.svColorCard)
        val radius = 16f * density
        // Glass supplies translucency only. All illumination comes from the local light below.
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(ColorUtils.setAlphaComponent(card, 178))
            setStroke(
                density.toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(themeColor(R.attr.svColorBorder), 100)
            )
        }
        // Only the background is translucent; text and the indicator keep their opacity.
        clipToOutline = true
        glowPaint.shader = radialLight(
            accent, intArrayOf(125, 82, 28, 0), floatArrayOf(0f, 0.24f, 0.58f, 1f)
        )
        haloPaint.shader = radialLight(
            accent, intArrayOf(230, 155, 40, 0), floatArrayOf(0f, 0.2f, 0.52f, 1f)
        )
        addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: Tab) {
                invalidate()
                // Keyboard/accessibility navigation also lights the newly selected tab.
                if (!touching && isLaidOut) flash(tab.position)
            }

            override fun onTabUnselected(tab: Tab) { invalidate() }

            override fun onTabReselected(tab: Tab) {
                if (!touching) flash(tab.position)
            }
        })
    }

    private fun radialLight(color: Int, alphas: IntArray, stops: FloatArray) = RadialGradient(
        0f, 0f, 1f,
        alphas.map { ColorUtils.setAlphaComponent(color, it) }.toIntArray(),
        stops, Shader.TileMode.CLAMP
    )

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun tabLeft(tab: View): Float = tab.x + (tab.parent as View).x

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                pressedTab = (0 until tabCount).firstOrNull { index ->
                    val tab = getTabAt(index)?.view ?: return@firstOrNull false
                    val left = tabLeft(tab) - scrollX
                    event.x >= left && event.x < left + tab.width &&
                        event.y >= paddingTop && event.y < height - paddingBottom
                } ?: -1
                animator?.cancel()
                pulse = if (pressedTab >= 0) 0.65f else 0f
                spread = 0.8f
                if (pressedTab >= 0) animateGlow(1f, 1f, 160L)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Let TabLayout finish selection before releasing our touch state.
                val handled = super.dispatchTouchEvent(event)
                touching = false
                animateGlow(0f, 1.25f, if (event.actionMasked == MotionEvent.ACTION_UP) 600L else 160L)
                return handled
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun flash(position: Int) {
        animator?.cancel()
        pressedTab = position
        pulse = 1f
        spread = 0.9f
        animateGlow(0f, 1.25f, 600L)
    }

    private fun animateGlow(targetPulse: Float, targetSpread: Float, durationMs: Long) {
        animator?.cancel()
        val initialPulse = pulse
        val initialSpread = spread
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                pulse = initialPulse + (targetPulse - initialPulse) * fraction
                spread = initialSpread + (targetSpread - initialSpread) * fraction
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tab = getTabAt(selectedTabPosition)?.view ?: return
        if (tab.width <= 0) return
        val strip = tab.parent as View
        val indicator = tabSelectedIndicator.bounds
        // Follow the actual indicator during its slide between tabs, including RTL layouts.
        val x = if (indicator.width() > 0) strip.x + indicator.exactCenterX()
            else tabLeft(tab) + tab.width / 2f
        val indicatorHeight = indicator.height().takeIf { it > 0 }?.toFloat() ?: (3f * density)
        val y = strip.y + strip.height - indicatorHeight / 2f
        val indicatorWidth = indicator.width().takeIf { it > 0 }?.toFloat() ?: (24f * density)
        val strength = 0.64f + 0.36f * pulse
        // Return smoothly to the resting radius after the outward pulse.
        val expansion = 1f + (spread - 1f) * pulse
        drawLight(canvas, glowPaint, x, y,
            tab.width * 1.1f * expansion, height * 1.05f * expansion, strength)
        // A wide, shallow halo makes the existing bar the light source; no separate dot.
        drawLight(canvas, haloPaint, x, y,
            (indicatorWidth / 2f + 14f * density) * expansion, 16f * density * expansion, strength)
    }

    private fun drawLight(
        canvas: Canvas, paint: Paint, x: Float, y: Float,
        radiusX: Float, radiusY: Float, strength: Float
    ) {
        paint.alpha = (255 * strength).toInt().coerceIn(0, 255)
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(radiusX, radiusY)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        pulse = 0f
        pressedTab = -1
        touching = false
        super.onDetachedFromWindow()
    }
}
