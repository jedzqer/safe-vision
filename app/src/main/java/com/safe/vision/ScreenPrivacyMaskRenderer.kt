package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class ScreenPrivacyMaskRenderer(context: Context) {
    enum class RenderedBitmapSpace {
        CAPTURE,
        SCREEN
    }

    data class DrawTask(
        val label: String,
        val renderMode: Int,
        val drawRect: Rect,
        val allowCircular: Boolean,
        val usesEyeStrip: Boolean,
        val eyePath: Path?,
        val rotationDegrees: Float,
        val drawOutline: Boolean,
        val renderedBitmap: Bitmap? = null,
        val renderedBitmapSpace: RenderedBitmapSpace = RenderedBitmapSpace.CAPTURE
    ) {
        fun release() {
            renderedBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    data class ClearRegion(
        val rect: Rect,
        val circular: Boolean,
        val path: Path?,
        val drawOutline: Boolean
    )

    data class OverlayFrame(
        val sourceBitmap: Bitmap,
        val drawTasks: List<DrawTask>,
        val reverseMode: Int?,
        val reverseRegions: List<ClearRegion>,
        val reversePreRender: Boolean,
        val reverseStickerLabel: String? = null,
        val preRenderedMaskBitmap: Bitmap? = null
    ) {
        val requiresFullscreenOverlay: Boolean
            get() = reverseMode != null

        fun release() {
            preRenderedMaskBitmap?.takeIf { !it.isRecycled }?.recycle()
            drawTasks.forEach { it.release() }
            sourceBitmap.takeIf { !it.isRecycled }?.recycle()
        }
    }

    companion object {
        private const val EYE_STRIP_ASPECT_MAX = 3f
    }

    private val privacySettings = PrivacySettingsManager.getInstance(context.applicationContext)
    private val appContext = context.applicationContext
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private fun shouldPreRenderHdSticker(task: DrawTask): Boolean {
        return task.renderMode == PrivacySettingsManager.BLUR_MODE_STICKER
    }

    fun shouldRenderOverlay(
        detections: List<YoloOnnxRunner.Detection>,
        labelProfile: DetectionConfig.LabelProfile,
        overlayMode: ScreenOverlayMode
    ): Boolean {
        if (detections.isNotEmpty()) return true
        return shouldFullscreenFallback(
            detections = detections,
            labelProfile = labelProfile,
            overlayMode = overlayMode
        )
    }

    fun render(
        sourceBitmap: Bitmap,
        detections: List<YoloOnnxRunner.Detection>,
        labelProfile: DetectionConfig.LabelProfile,
        overlayMode: ScreenOverlayMode,
        outputScaleX: Float = 1f,
        outputScaleY: Float = 1f
    ): OverlayFrame? {
        val defaultBlurMode = privacySettings.getBlurMode(labelProfile)
        val labelOverrides = privacySettings.getLabelEffectOverrides(labelProfile)
        val reverseLabels = privacySettings.getReverseLabels(labelProfile).toSet()
        val useCircularMask = privacySettings.isCircularMaskEnabled()
        val maskOutlineEnabled = privacySettings.isMaskOutlineEnabled()
        val maskOutlineLabels = privacySettings.getMaskOutlineLabels(labelProfile).toSet()
        val shouldFullscreenFallback = shouldFullscreenFallback(
            detections = detections,
            labelProfile = labelProfile,
            overlayMode = overlayMode
        )

        if (shouldFullscreenFallback) {
            val firstReverseLabel = privacySettings.getReverseLabels(labelProfile).firstOrNull()
            val rawMode = firstReverseLabel?.let { labelOverrides[it] } ?: defaultBlurMode
            val fullscreenMode = if (rawMode == PrivacySettingsManager.BLUR_MODE_EYES) {
                PrivacySettingsManager.BLUR_MODE_MOSAIC
            } else {
                rawMode
            }
            val preRenderedMaskBitmap = renderFullscreenOverlayBitmap(
                sourceBitmap = sourceBitmap,
                drawTasks = emptyList(),
                reverseMode = fullscreenMode,
                reverseRegions = emptyList(),
                reversePreRender = privacySettings.isReversePreRenderEnabled(),
                reverseStickerLabel = firstReverseLabel
            )
            return OverlayFrame(
                sourceBitmap = sourceBitmap,
                drawTasks = emptyList(),
                reverseMode = fullscreenMode,
                reverseRegions = emptyList(),
                reversePreRender = privacySettings.isReversePreRenderEnabled(),
                reverseStickerLabel = firstReverseLabel,
                preRenderedMaskBitmap = preRenderedMaskBitmap
            )
        }

        if (detections.isEmpty()) return null

        fun shouldOutline(label: String): Boolean {
            if (!maskOutlineEnabled) return false
            if (maskOutlineLabels.isEmpty()) return true
            return maskOutlineLabels.contains(label)
        }

        fun resolveRenderMode(mode: Int, fallback: Int): Int {
            if (mode != PrivacySettingsManager.BLUR_MODE_EYES) return mode
            return if (fallback == PrivacySettingsManager.BLUR_MODE_EYES) {
                PrivacySettingsManager.BLUR_MODE_MOSAIC
            } else {
                fallback
            }
        }

        data class EyeTarget(
            val rect: Rect,
            val path: Path? = null,
            val rotationDegrees: Float = 0f
        )

        data class PendingReverseRegion(
            val rect: Rect,
            val circular: Boolean,
            val path: Path?,
            val drawOutline: Boolean
        )

        fun resolveEyeTarget(detection: YoloOnnxRunner.Detection, faceRect: Rect): EyeTarget {
            val leftEye = detection.leftEye
            val rightEye = detection.rightEye
            if (leftEye != null && rightEye != null) {
                val strip = BlurEffects.eyeStripFromEyes(
                    faceRect,
                    leftEye,
                    rightEye,
                    sourceBitmap.width,
                    sourceBitmap.height
                )
                if (strip != null && strip.bounds.width() > 0 && strip.bounds.height() > 0) {
                    return EyeTarget(
                        rect = strip.bounds,
                        path = strip.path,
                        rotationDegrees = Math.toDegrees(
                            atan2(
                                (rightEye.y - leftEye.y).toDouble(),
                                (rightEye.x - leftEye.x).toDouble()
                            )
                        ).toFloat()
                    )
                }
            }

            return EyeTarget(
                BlurEffects.capAspectRatio(
                    BlurEffects.cropToEyeStrip(faceRect, sourceBitmap.width, sourceBitmap.height),
                    EYE_STRIP_ASPECT_MAX,
                    sourceBitmap.width,
                    sourceBitmap.height
                )
            )
        }

        val drawTasks = mutableListOf<DrawTask>()
        val reverseRegions = mutableListOf<PendingReverseRegion>()
        var reverseBlurMode: Int? = null
        var reverseModeMixed = false

        detections.forEach { detection ->
            val className = detection.className
            if (!privacySettings.isLabelBlocked(className, labelProfile)) return@forEach

            val rect = BlurEffects.clampRect(
                Rect(
                    detection.box.left.roundToInt(),
                    detection.box.top.roundToInt(),
                    detection.box.right.roundToInt(),
                    detection.box.bottom.roundToInt()
                ),
                sourceBitmap.width,
                sourceBitmap.height
            )
            if (rect.width() <= 0 || rect.height() <= 0) return@forEach

            val blurMode = labelOverrides[className] ?: defaultBlurMode
            val usesEyeStrip = DetectionConfig.isEyeRegionLabel(className)
            val eyeTarget = if (usesEyeStrip) resolveEyeTarget(detection, rect) else null
            val targetRect = eyeTarget?.rect ?: rect
            val maskScale = privacySettings.getEffectiveMaskScale(className)
            val scaledTargetRect = BlurEffects.scaleRect(
                targetRect,
                maskScale,
                sourceBitmap.width,
                sourceBitmap.height
            )
            val scaledEyePath = if (usesEyeStrip && eyeTarget?.path != null && abs(maskScale - 1f) > 0.0001f) {
                Path(eyeTarget.path).apply {
                    transform(
                        Matrix().apply {
                            setScale(
                                maskScale,
                                maskScale,
                                targetRect.exactCenterX(),
                                targetRect.exactCenterY()
                            )
                        }
                    )
                }
            } else {
                eyeTarget?.path
            }
            val renderMode = resolveRenderMode(blurMode, defaultBlurMode)
            val allowCircular = useCircularMask && !usesEyeStrip
            val safeRect = BlurEffects.clampRect(
                scaledTargetRect,
                sourceBitmap.width,
                sourceBitmap.height
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) return@forEach

            if (reverseLabels.contains(className)) {
                reverseRegions.add(
                    PendingReverseRegion(
                        rect = Rect(safeRect),
                        circular = allowCircular,
                        path = scaledEyePath?.let(::Path),
                        drawOutline = shouldOutline(className)
                    )
                )
                if (reverseBlurMode == null) {
                    reverseBlurMode = renderMode
                } else if (reverseBlurMode != renderMode) {
                    reverseModeMixed = true
                }
            } else {
                drawTasks.add(
                    DrawTask(
                        label = className,
                        renderMode = renderMode,
                        drawRect = Rect(safeRect),
                        allowCircular = allowCircular,
                        usesEyeStrip = usesEyeStrip,
                        eyePath = scaledEyePath?.let(::Path),
                        rotationDegrees = if (usesEyeStrip) eyeTarget?.rotationDegrees ?: 0f else 0f,
                        drawOutline = shouldOutline(className) &&
                            renderMode != PrivacySettingsManager.BLUR_MODE_STICKER
                    )
                )
            }
        }

        if (drawTasks.isEmpty() && reverseRegions.isEmpty()) {
            return null
        }

        if (reverseRegions.isNotEmpty() && reverseModeMixed) {
            DebugLogManager.addLog(
                "屏幕遮挡",
                "反向遮挡存在多种效果，已使用默认效果: ${privacySettings.getBlurModeName(defaultBlurMode)}"
            )
        }

        val sortedDrawTasks = drawTasks.sortedWith(
            compareBy<DrawTask> { it.label }
                .thenBy { it.drawRect.centerX() }
                .thenBy { it.drawRect.centerY() }
        )
        val finalReverseRegions = reverseRegions.map { region ->
            ClearRegion(
                rect = Rect(region.rect),
                circular = region.circular,
                path = region.path?.let(::Path),
                drawOutline = region.drawOutline
            )
        }
        val finalReverseMode = if (reverseRegions.isNotEmpty()) {
            if (reverseModeMixed) defaultBlurMode else reverseBlurMode ?: defaultBlurMode
        } else {
            null
        }
        val reversePreRender = privacySettings.isReversePreRenderEnabled()

        val hdStickerDrawTasks = sortedDrawTasks.filter(::shouldPreRenderHdSticker)
        val preRenderedDrawTasks = sortedDrawTasks.filterNot(::shouldPreRenderHdSticker)
        val preRenderedMaskBitmap = if (overlayMode != ScreenOverlayMode.SYSTEM_ALERT_WINDOW || finalReverseMode != null) {
            renderFullscreenOverlayBitmap(
                sourceBitmap = sourceBitmap,
                drawTasks = preRenderedDrawTasks,
                reverseMode = finalReverseMode,
                reverseRegions = finalReverseRegions,
                reversePreRender = reversePreRender,
                reverseStickerLabel = null
            )
        } else {
            null
        }
        val preparedDrawTasks = if (overlayMode == ScreenOverlayMode.SYSTEM_ALERT_WINDOW && finalReverseMode == null) {
            sortedDrawTasks.map { task ->
                if (shouldPreRenderHdSticker(task)) {
                    task.copy(
                        renderedBitmap = renderTaskBitmap(
                            sourceBitmap = sourceBitmap,
                            task = task,
                            includeOutline = false,
                            outputScaleX = outputScaleX,
                            outputScaleY = outputScaleY,
                            outputSpace = RenderedBitmapSpace.SCREEN
                        ),
                        renderedBitmapSpace = RenderedBitmapSpace.SCREEN
                    )
                } else {
                    task.copy(
                        renderedBitmap = renderTaskBitmap(sourceBitmap, task, includeOutline = true)
                    )
                }
            }
        } else if (preRenderedMaskBitmap != null) {
            hdStickerDrawTasks.map { task ->
                task.copy(
                    renderedBitmap = renderTaskBitmap(
                        sourceBitmap = sourceBitmap,
                        task = task,
                        includeOutline = false,
                        outputScaleX = outputScaleX,
                        outputScaleY = outputScaleY,
                        outputSpace = RenderedBitmapSpace.SCREEN
                    ),
                    renderedBitmapSpace = RenderedBitmapSpace.SCREEN
                )
            }
        } else {
            sortedDrawTasks
        }

        return OverlayFrame(
            sourceBitmap = sourceBitmap,
            drawTasks = preparedDrawTasks,
            reverseMode = finalReverseMode,
            reverseRegions = finalReverseRegions,
            reversePreRender = reversePreRender,
            preRenderedMaskBitmap = preRenderedMaskBitmap
        )
    }

    private fun renderFullscreenOverlayBitmap(
        sourceBitmap: Bitmap,
        drawTasks: List<DrawTask>,
        reverseMode: Int?,
        reverseRegions: List<ClearRegion>,
        reversePreRender: Boolean,
        reverseStickerLabel: String?
    ): Bitmap? {
        if (sourceBitmap.width <= 0 || sourceBitmap.height <= 0) return null
        val overlayBitmap = Bitmap.createBitmap(
            sourceBitmap.width,
            sourceBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(overlayBitmap)

        if (reverseMode != null && reversePreRender) {
            applyReverseMask(canvas, sourceBitmap, reverseMode, reverseRegions, reverseStickerLabel)
        }

        val outlineShapes = mutableListOf<BlurEffects.OutlineShape>()
        drawTasks.forEach { task ->
            val taskBitmap = renderTaskBitmap(sourceBitmap, task, includeOutline = false)
            if (taskBitmap != null) {
                try {
                    canvas.drawBitmap(taskBitmap, task.drawRect.left.toFloat(), task.drawRect.top.toFloat(), null)
                } finally {
                    if (!taskBitmap.isRecycled) taskBitmap.recycle()
                }
            }
            collectTaskOutlineShape(task)?.let { outlineShapes += it }
        }

        if (reverseMode != null && !reversePreRender) {
            applyReverseMask(canvas, sourceBitmap, reverseMode, reverseRegions, reverseStickerLabel)
        }
        if (reverseMode != null) {
            outlineShapes.addAll(collectReverseOutlineShapes(reverseRegions))
        }
        if (outlineShapes.isNotEmpty()) {
            BlurEffects.drawUnionOutline(canvas, outlineShapes)
        }
        return overlayBitmap
    }

    private fun renderTaskBitmap(
        sourceBitmap: Bitmap,
        task: DrawTask,
        includeOutline: Boolean,
        outputScaleX: Float = 1f,
        outputScaleY: Float = 1f,
        outputSpace: RenderedBitmapSpace = RenderedBitmapSpace.CAPTURE
    ): Bitmap? {
        val rect = task.drawRect
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val width = when (outputSpace) {
            RenderedBitmapSpace.CAPTURE -> rect.width()
            RenderedBitmapSpace.SCREEN -> (rect.width() * outputScaleX).roundToInt().coerceAtLeast(1)
        }
        val height = when (outputSpace) {
            RenderedBitmapSpace.CAPTURE -> rect.height()
            RenderedBitmapSpace.SCREEN -> (rect.height() * outputScaleY).roundToInt().coerceAtLeast(1)
        }
        val localRect = Rect(0, 0, width, height)
        val localEyePath = if (outputSpace == RenderedBitmapSpace.CAPTURE) {
            task.eyePath?.let { path ->
                Path(path).apply {
                    offset(-rect.left.toFloat(), -rect.top.toFloat())
                }
            }
        } else {
            null
        }
        val patch = Bitmap.createBitmap(localRect.width(), localRect.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(patch)

        if (task.allowCircular) {
            BlurEffects.drawWithCircularClip(canvas, localRect) {
                drawTaskEffect(canvas, sourceBitmap, task, localRect)
            }
        } else if (task.usesEyeStrip && localEyePath != null && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
            val checkpoint = canvas.save()
            canvas.clipPath(localEyePath)
            drawTaskEffect(canvas, sourceBitmap, task, localRect)
            canvas.restoreToCount(checkpoint)
        } else {
            drawTaskEffect(canvas, sourceBitmap, task, localRect)
        }

        if (includeOutline && task.drawOutline) {
            when {
                task.allowCircular -> BlurEffects.drawCircularOutline(canvas, localRect)
                task.usesEyeStrip && localEyePath != null -> BlurEffects.drawPathOutline(canvas, localEyePath, localRect)
                else -> BlurEffects.drawRectOutline(canvas, localRect)
            }
        }
        return patch
    }

    private fun drawTaskEffect(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        task: DrawTask,
        destRect: Rect
    ) {
        when (task.renderMode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                renderProcessedRegion(sourceBitmap, task.drawRect) { region ->
                    BlurEffects.createMosaicBitmap(region, privacySettings.getMosaicBlockSize())
                }?.useOnCanvas(canvas)
            }
            PrivacySettingsManager.BLUR_MODE_BLACK -> BlurEffects.drawBlack(canvas, destRect)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                renderProcessedRegion(sourceBitmap, task.drawRect) { region ->
                    BlurEffects.createGaussianBitmap(region, privacySettings.getGaussianRadius())
                }?.useOnCanvas(canvas)
            }
            PrivacySettingsManager.BLUR_MODE_STICKER -> {
                val sticker = StickerLoader.loadSticker(appContext, privacySettings, task.label)
                if (sticker != null) {
                    BlurEffects.drawSticker(
                        canvas,
                        sticker,
                        destRect,
                        destRect.width(),
                        destRect.height(),
                        fitInsideRect = task.usesEyeStrip,
                        rotationDegrees = task.rotationDegrees
                    )
                } else {
                    renderProcessedRegion(sourceBitmap, task.drawRect) { region ->
                        BlurEffects.createMosaicBitmap(region, privacySettings.getMosaicBlockSize())
                    }?.useOnCanvas(canvas)
                }
            }
            PrivacySettingsManager.BLUR_MODE_SOBEL -> {
                renderProcessedRegion(sourceBitmap, task.drawRect) { region ->
                    BlurEffects.createSobelBitmap(region)
                }?.useOnCanvas(canvas)
            }
            else -> {
                renderProcessedRegion(sourceBitmap, task.drawRect) { region ->
                    BlurEffects.createMosaicBitmap(region, privacySettings.getMosaicBlockSize())
                }?.useOnCanvas(canvas)
            }
        }
    }

    private fun renderProcessedRegion(
        sourceBitmap: Bitmap,
        rect: Rect,
        process: (Bitmap) -> Bitmap?
    ): Bitmap? {
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val region = Bitmap.createBitmap(
            sourceBitmap,
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
        return try {
            process(region)
        } finally {
            if (!region.isRecycled && region !== sourceBitmap) region.recycle()
        }
    }

    private fun Bitmap.useOnCanvas(canvas: Canvas) {
        try {
            canvas.drawBitmap(this, 0f, 0f, null)
        } finally {
            if (!isRecycled) recycle()
        }
    }

    private fun applyReverseMask(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        mode: Int,
        reverseRegions: List<ClearRegion>,
        reverseStickerLabel: String?
    ) {
        val fullRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
        when (mode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                BlurEffects.createMosaicBitmap(sourceBitmap, privacySettings.getMosaicBlockSize())?.let { bitmap ->
                    bitmap.useOnCanvas(canvas)
                }
            }
            PrivacySettingsManager.BLUR_MODE_BLACK -> canvas.drawColor(android.graphics.Color.BLACK)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                BlurEffects.createGaussianBitmap(sourceBitmap, privacySettings.getGaussianRadius()).useOnCanvas(canvas)
            }
            PrivacySettingsManager.BLUR_MODE_STICKER -> {
                val sticker = StickerLoader.loadSticker(appContext, privacySettings, reverseStickerLabel)
                if (sticker != null) {
                    BlurEffects.drawSticker(canvas, sticker, fullRect, sourceBitmap.width, sourceBitmap.height)
                } else {
                    BlurEffects.createMosaicBitmap(sourceBitmap, privacySettings.getMosaicBlockSize())?.let { bitmap ->
                        bitmap.useOnCanvas(canvas)
                    }
                }
            }
            PrivacySettingsManager.BLUR_MODE_SOBEL -> {
                BlurEffects.createSobelBitmap(sourceBitmap)?.let { bitmap ->
                    bitmap.useOnCanvas(canvas)
                }
            }
            else -> {
                BlurEffects.createMosaicBitmap(sourceBitmap, privacySettings.getMosaicBlockSize())?.let { bitmap ->
                    bitmap.useOnCanvas(canvas)
                }
            }
        }

        reverseRegions.forEach { clearRegion ->
            if (clearRegion.circular) {
                clearCircularRegion(canvas, clearRegion.rect)
            } else if (clearRegion.path != null) {
                canvas.drawPath(clearRegion.path, clearPaint)
            } else {
                canvas.drawRect(clearRegion.rect, clearPaint)
            }
        }
    }

    private fun clearCircularRegion(canvas: Canvas, rect: Rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        canvas.drawCircle(
            rect.exactCenterX(),
            rect.exactCenterY(),
            kotlin.math.hypot(rect.width() / 2f, rect.height() / 2f),
            clearPaint
        )
    }

    private fun collectTaskOutlineShape(task: DrawTask): BlurEffects.OutlineShape? {
        if (!task.drawOutline) return null
        return if (task.allowCircular) {
            BlurEffects.OutlineShape.CircleShape(task.drawRect)
        } else if (task.usesEyeStrip && task.eyePath != null) {
            BlurEffects.OutlineShape.PathShape(task.eyePath, task.drawRect)
        } else {
            BlurEffects.OutlineShape.RectShape(task.drawRect)
        }
    }

    private fun collectReverseOutlineShapes(reverseRegions: List<ClearRegion>): List<BlurEffects.OutlineShape> {
        return reverseRegions.mapNotNull { clearRegion ->
            if (!clearRegion.drawOutline) return@mapNotNull null
            if (clearRegion.circular) {
                BlurEffects.OutlineShape.CircleShape(clearRegion.rect)
            } else if (clearRegion.path != null) {
                BlurEffects.OutlineShape.PathShape(clearRegion.path, clearRegion.rect)
            } else {
                BlurEffects.OutlineShape.RectShape(clearRegion.rect)
            }
        }
    }

    private fun shouldFullscreenFallback(
        detections: List<YoloOnnxRunner.Detection>,
        labelProfile: DetectionConfig.LabelProfile,
        overlayMode: ScreenOverlayMode
    ): Boolean {
        val reverseLabels = privacySettings.getReverseLabels(labelProfile).toSet()
        if (reverseLabels.isEmpty()) return false
        val hasReverseHit = detections.any { detection ->
            reverseLabels.contains(detection.className) &&
                privacySettings.isLabelBlocked(detection.className, labelProfile)
        }
        if (
            overlayMode != ScreenOverlayMode.SYSTEM_ALERT_WINDOW &&
            privacySettings.isAccessibilityEmptyReverseFullscreenEnabled() &&
            detections.none { privacySettings.isLabelBlocked(it.className, labelProfile) }
        ) {
            return true
        }
        return overlayMode != ScreenOverlayMode.SYSTEM_ALERT_WINDOW &&
            privacySettings.isReverseLabelMissFullscreenEnabled() &&
            detections.isNotEmpty() &&
            !hasReverseHit
    }
}
