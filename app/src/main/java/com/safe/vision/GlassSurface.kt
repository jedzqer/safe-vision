package com.safe.vision

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.ShapeAppearancePathProvider
import androidx.core.graphics.ColorUtils

/** Cached lighting painted under content; never samples or blurs private media. */
internal class SurfaceLighting(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val accent = DialogUtils.resolveThemeColor(context, R.attr.svColorAccent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val path = Path()
    private val pathProvider = ShapeAppearancePathProvider()
    private var shape: ShapeAppearanceModel? = null
    private var sheen: Shader? = null
    private var glow: Shader? = null

    fun draw(canvas: Canvas, width: Int, height: Int, model: ShapeAppearanceModel, enabled: Boolean) {
        if (width <= 0 || height <= 0) return
        if (bounds.width() != width.toFloat() || bounds.height() != height.toFloat() || shape !== model) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            shape = model
            pathProvider.calculatePath(model, 1f, bounds, path)
            sheen = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 13),
                    Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, 3)),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
            glow = RadialGradient(width * 0.75f, height.toFloat(), maxOf(width * 0.7f, 1f),
                ColorUtils.setAlphaComponent(accent, 12),
                ColorUtils.setAlphaComponent(accent, 0), Shader.TileMode.CLAMP)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.alpha = if (enabled) 255 else 90
        paint.shader = sheen
        canvas.drawRect(bounds, paint)
        paint.shader = glow
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = ColorUtils.setAlphaComponent(Color.WHITE, if (enabled) 38 else 14)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
}

/** Flat Material button; background and ripple share Material's inset-aware bounds. */
class GlassButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPath = Path()
    private val glowBounds = RectF()
    private val pathProvider = ShapeAppearancePathProvider()
    private val originalRipple = rippleColor
    private val glowStrength: Float
    private val leftInset: Int
    private val rightInset: Int
    private var glowAnimator: ValueAnimator? = null
    private var progress = 1f
    private var touchX: Float? = null
    private var touchY: Float? = null
    private var originX = 0f
    private var originY = 0f

    init {
        val attributes = context.obtainStyledAttributes(attrs, intArrayOf(
            R.attr.svButtonGlowStrength, android.R.attr.insetLeft, android.R.attr.insetRight
        ), defStyleAttr, 0)
        glowStrength = attributes.getFloat(0, 0.32f).coerceIn(0f, 1f)
        leftInset = attributes.getDimensionPixelSize(1, 0)
        rightInset = attributes.getDimensionPixelSize(2, 0)
        attributes.recycle()
        val accent = DialogUtils.resolveThemeColor(context, R.attr.svColorAccent)
        glowPaint.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(accent, ColorUtils.setAlphaComponent(accent, 150),
                ColorUtils.setAlphaComponent(accent, 40), ColorUtils.setAlphaComponent(accent, 0)),
            floatArrayOf(0f, 0.24f, 0.58f, 1f), Shader.TileMode.CLAMP)
    }

    private fun animationsEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    private fun prepareFeedback() {
        // Keep focus/hover feedback and native ripple when motion is disabled.
        rippleColor = if (glowStrength > 0f && animationsEnabled()) ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_hovered), intArrayOf()),
            intArrayOf(Color.TRANSPARENT,
                originalRipple?.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused), 0) ?: 0,
                originalRipple?.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_hovered), 0) ?: 0,
                originalRipple?.defaultColor ?: Color.TRANSPARENT)
        ) else originalRipple
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.x
            touchY = event.y
            prepareFeedback()
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            stopGlow()
            touchX = null
            touchY = null
        }
        return handled
    }

    override fun performClick(): Boolean {
        if (isEnabled) {
            prepareFeedback()
            startGlow()
        }
        touchX = null
        touchY = null
        return super.performClick()
    }

    private fun startGlow() {
        stopGlow()
        if (glowStrength <= 0f || !animationsEnabled() || width <= 0 || height <= 0) return
        glowBounds.set(leftInset.toFloat(), insetTop.toFloat(),
            (width - rightInset).toFloat(), (height - insetBottom).toFloat())
        if (glowBounds.isEmpty) return
        pathProvider.calculatePath(shapeAppearanceModel, 1f, glowBounds, glowPath)
        originX = (touchX ?: glowBounds.centerX()).coerceIn(glowBounds.left, glowBounds.right)
        originY = (touchY ?: glowBounds.centerY()).coerceIn(glowBounds.top, glowBounds.bottom)
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
        progress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (isEnabled && progress < 1f) {
            val save = canvas.save()
            // TextView can scroll its content; the light stays aligned with the background.
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            canvas.clipPath(glowPath)
            canvas.translate(originX, originY)
            val radiusX = glowBounds.width() * (0.12f + progress * 1.1f)
            val radiusY = glowBounds.height() * (0.5f + progress)
            canvas.scale(radiusX.coerceAtLeast(1f), radiusY.coerceAtLeast(1f))
            val fadeIn = (progress / 0.18f).coerceAtMost(1f)
            glowPaint.alpha = (255 * glowStrength * fadeIn * (1f - progress)).toInt()
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        stopGlow()
        touchX = null
        touchY = null
        super.onDetachedFromWindow()
    }
}

/** Lighting precedes children so thumbnails and preview colors remain faithful. */
class GlassCardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val lighting = SurfaceLighting(context)

    override fun dispatchDraw(canvas: Canvas) {
        lighting.draw(canvas, width, height, shapeAppearanceModel, isEnabled)
        super.dispatchDraw(canvas)
    }
}
