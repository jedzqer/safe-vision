package com.safe.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenDetectionService : Service() {
    companion object {
        private const val ACTION_START = "com.safe.vision2.action.START_SCREEN_DETECTION"
        private const val ACTION_STOP = "com.safe.vision2.action.STOP_SCREEN_DETECTION"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_CHANNEL_ID = "screen_detection_channel"
        private const val NOTIFICATION_ID = 3002
        private const val FLICKER_WINDOW_SIZE = 4
        private const val SHOW_THRESHOLD = 2
        private const val HIDE_TIMEOUT_ACCESSIBILITY_MS = 500L
        private const val HIDE_TIMEOUT_SYSTEM_ALERT_WINDOW_MS = 300L
        private const val ACCESSIBILITY_OVERLAY_CONNECT_TIMEOUT_MS = 2_000L
        private const val ACCESSIBILITY_OVERLAY_CONNECT_POLL_MS = 100L
        private const val VISIBILITY_RESUME_GRACE_MS = 150L
        // 静帧跳过：把整帧缩到极小灰度图做差分，变化低于阈值则跳过整条 YOLO 链路
        private const val STATIC_FRAME_SIGNATURE_SIZE = 32
        private const val STATIC_FRAME_DIFF_THRESHOLD = 2.0
        // 采集端降分辨率：把镜像捕获面的短边压到该目标值，遮挡坐标在叠加边界按比例还原。
        // 模型输入只有 320，全屏挡/黑块/马赛克对像素精度不敏感，缩小可显著降低每帧 RGBA 搬运与缩放开销。
        private const val CAPTURE_TARGET_SHORT_EDGE = 480

        fun createStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenDetectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenDetectionService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detectionJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var yoloRunner: YoloOnnxRunner? = null
    private var currentVariant: DetectionModelVariant? = null
    private var overlayRenderer: ScreenPrivacyMaskRenderer? = null
    private var detectionIntervalMs: Long = 500L
    private var overlayMetrics: OverlayMetrics? = null
    private var overlayMode: ScreenOverlayMode = ScreenOverlayMode.ACCESSIBILITY
    private var analysisBitmap: Bitmap? = null
    private var analysisCanvas: Canvas? = null
    private var stagingBitmap: Bitmap? = null
    private val analysisDstRect = Rect()
    private var captureVisibilityMonitoringEnabled = false
    private var isCapturedContentVisible = true
    private var visibilityResumeAfterMs: Long = 0L

    // 静帧跳过：上一帧的小图灰度指纹与采样缓存
    private var signatureBitmap: Bitmap? = null
    private var signatureCanvas: Canvas? = null
    private val signaturePixels = IntArray(STATIC_FRAME_SIGNATURE_SIZE * STATIC_FRAME_SIGNATURE_SIZE)
    private var lastSignature: FloatArray? = null
    private val signatureDstRect = Rect(0, 0, STATIC_FRAME_SIGNATURE_SIZE, STATIC_FRAME_SIGNATURE_SIZE)

    // 抗闪烁：滑动窗口 + 延迟清除
    private val detectionWindow = ArrayDeque<Boolean>(FLICKER_WINDOW_SIZE)
    private var overlayVisible = false
    private var lastPositiveTimeMs: Long = 0L

    // 去抖：仅在状态文案变化时刷新通知/状态/日志，避免每帧抢主线程做 notify()
    private var lastPublishedStatus: String? = null

    private val projectionCallback = object : Callback() {
        override fun onStop() {
            DebugLogManager.addLog("屏幕检测", "MediaProjection 已停止")
            stopDetection(getString(R.string.screen_detection_status_stopped))
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            if (!captureVisibilityMonitoringEnabled || mediaProjection == null) return
            handleCapturedContentVisibilityChanged(isVisible)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogManager.initialize(applicationContext)
        createNotificationChannel()
        overlayRenderer = ScreenPrivacyMaskRenderer(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDetection(getString(R.string.screen_detection_status_stopped))
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundInternal(getString(R.string.screen_detection_notification_starting))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableIntentExtra(EXTRA_RESULT_DATA)
                if (resultCode == 0 || resultData == null) {
                    DebugLogManager.addLog("屏幕检测", "缺少录屏授权结果，无法启动", DebugLogManager.LogLevel.ERROR)
                    stopDetection(getString(R.string.screen_detection_status_start_failed))
                    return START_REDELIVER_INTENT
                }
                startDetection(resultCode, resultData)
                return START_REDELIVER_INTENT
            }
        }
        DebugLogManager.addLog("屏幕检测", "服务重启时未收到启动参数，停止保活", DebugLogManager.LogLevel.WARN)
        stopDetection(getString(R.string.screen_detection_status_start_failed))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDetection(resultCode: Int, resultData: Intent) {
        if (detectionJob?.isActive == true) {
            DebugLogManager.addLog("屏幕检测", "检测已在运行，忽略重复启动")
            return
        }

        detectionJob = serviceScope.launch {
            try {
                ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_starting))
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection 初始化失败")
                mediaProjection = projection
                projection.registerCallback(projectionCallback, null)

                val appSettings = AppSettingsManager.getInstance(applicationContext)
                overlayMode = appSettings.getScreenDetectionOverlayMode()
                ensureOverlayReady()

                val metrics = ScreenOverlayController.resolveOverlayMetrics(applicationContext, overlayMode)
                overlayMetrics = metrics
                val (captureWidth, captureHeight) = resolveCaptureSize(
                    metrics.widthPixels,
                    metrics.heightPixels
                )
                imageReader = ImageReader.newInstance(
                    captureWidth,
                    captureHeight,
                    PixelFormat.RGBA_8888,
                    2
                )
                virtualDisplay = projection.createVirtualDisplay(
                    "safe-vision-screen-detection",
                    captureWidth,
                    captureHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )

                val variant = if (appSettings.isScreenDetectionAnimeModelEnabled()) {
                    DetectionModelVariant.ANIME
                } else {
                    DetectionModelVariant.STANDARD
                }
                detectionIntervalMs = (appSettings.getScreenDetectionIntervalSeconds() * 1000f)
                    .toLong()
                    .coerceIn(10L, 1000L)
                if (yoloRunner == null || currentVariant != variant) {
                    yoloRunner = YoloOnnxRunner(applicationContext, variant)
                    currentVariant = variant
                }
                captureVisibilityMonitoringEnabled = appSettings.isScreenLossAutoPauseEnabled() &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                isCapturedContentVisible = true
                visibilityResumeAfterMs = 0L

                DebugLogManager.addLog(
                    "屏幕检测",
                    "屏幕检测已启动: 屏幕=${metrics.widthPixels}x${metrics.heightPixels}, 采集=${captureWidth}x${captureHeight}, 偏移=${metrics.contentOffsetX},${metrics.contentOffsetY}, 模型=${variant.runtimeLabel}"
                )
                ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_running))
                updateNotification(getString(R.string.screen_detection_notification_running))

                detectionLoop(variant)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                DebugLogManager.addLog("屏幕检测", "启动失败: ${error.message}", DebugLogManager.LogLevel.ERROR)
                DebugLogManager.addLog("屏幕检测", error.stackTraceToString(), DebugLogManager.LogLevel.ERROR)
                stopDetection(
                    error.message ?: getString(R.string.screen_detection_status_start_failed),
                    cancelJob = false
                )
            }
        }
    }

    private suspend fun detectionLoop(variant: DetectionModelVariant) {
        while (serviceScope.isActive) {
            val cycleStart = android.os.SystemClock.elapsedRealtime()
            val bitmap = withContext(Dispatchers.Default) {
                imageReader?.acquireLatestImage()?.use { image ->
                    image.copyToReusableBitmap()
                }
            }

            if (bitmap == null) {
                delay(50)
                continue
            }

            val isStaticFrame = withContext(Dispatchers.Default) {
                isFrameStatic(bitmap)
            }
            if (isStaticFrame) {
                // 画面静止：跳过整条 YOLO 链路，遮挡与状态保持上一帧不动
                delayRemainingInterval(cycleStart)
                continue
            }

            val renderResult = withContext(Dispatchers.Default) {
                val detections = yoloRunner?.run(
                    bitmap,
                    // Skip face-landmark enrichment during realtime screen detection to reduce latency.
                    enrichFaceLandmarks = false
                ).orEmpty()
                val profile = if (variant == DetectionModelVariant.ANIME) {
                    DetectionConfig.LabelProfile.ANIME
                } else {
                    DetectionConfig.LabelProfile.STANDARD
                }
                val shouldRenderOverlay =
                    overlayRenderer?.shouldRenderOverlay(detections, profile, overlayMode) == true
                val overlayFrame = if (shouldRenderOverlay) {
                    val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    overlayRenderer?.render(overlayBitmap, detections, profile, overlayMode).also { frame ->
                        if (frame == null && !overlayBitmap.isRecycled) {
                            overlayBitmap.recycle()
                        }
                    }
                } else {
                    null
                }
                RenderResult(detections.size, overlayFrame)
            }

            applyOverlayFrame(renderResult.overlayFrame)

            val status = if (renderResult.detectionCount == 0) {
                getString(R.string.screen_detection_status_clear)
            } else {
                getString(R.string.screen_detection_status_detected, renderResult.detectionCount)
            }
            // 仅在状态变化时刷新,避免每帧 notify() 持续抢占主线程
            if (status != lastPublishedStatus) {
                lastPublishedStatus = status
                ScreenDetectionStateHolder.setRunning(status, renderResult.detectionCount)
                updateNotification(status)
                DebugLogManager.addLog("屏幕检测", "检测状态更新: ${renderResult.detectionCount} 个目标")
            }

            delayRemainingInterval(cycleStart)
        }
    }

    // 把处理耗时计入周期：实际周期 = max(间隔, 处理耗时),而非「间隔 + 处理耗时」,降低端到端延迟。
    private suspend fun delayRemainingInterval(cycleStart: Long) {
        val elapsed = android.os.SystemClock.elapsedRealtime() - cycleStart
        val remaining = detectionIntervalMs - elapsed
        if (remaining > 0) delay(remaining)
    }

    private fun applyOverlayFrame(frame: ScreenPrivacyMaskRenderer.OverlayFrame?) {
        if (shouldSuppressOverlayForHiddenCapture()) {
            ScreenOverlayController.clearMaskOverlays(overlayMode)
            detectionWindow.clear()
            overlayVisible = false
            lastPositiveTimeMs = 0L
            frame?.sourceBitmap?.recycle()
            return
        }

        val metrics = overlayMetrics ?: return
        val hasDetection = frame != null
        val hideTimeoutMs = when (overlayMode) {
            ScreenOverlayMode.ACCESSIBILITY -> HIDE_TIMEOUT_ACCESSIBILITY_MS
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> HIDE_TIMEOUT_SYSTEM_ALERT_WINDOW_MS
        }

        // 滑动窗口采样
        if (detectionWindow.size >= FLICKER_WINDOW_SIZE) detectionWindow.removeFirst()
        detectionWindow.addLast(hasDetection)
        if (hasDetection) lastPositiveTimeMs = System.currentTimeMillis()

        val positiveCount = detectionWindow.count { it }
        val withinHideTimeout = if (overlayVisible) {
            System.currentTimeMillis() - lastPositiveTimeMs < hideTimeoutMs
        } else {
            false
        }

        // 迟滞判断：已显示时要求更低的正帧数才维持；未显示时要求更高才触发
        val shouldShow = if (overlayVisible) {
            positiveCount > 1 || withinHideTimeout
        } else {
            positiveCount >= SHOW_THRESHOLD
        }

        if (!shouldShow) {
            if (overlayVisible) {
                ScreenOverlayController.clearMaskOverlays(overlayMode)
                overlayVisible = false
            }
            frame?.sourceBitmap?.recycle()
            return
        }

        // 窗口判断为应显示，但当前帧无内容（延迟清除保护期内），保持上一帧不动
        if (frame == null) return

        overlayVisible = true

        if (frame.requiresFullscreenOverlay || frame.drawTasks.isEmpty()) {
            val shown = ScreenOverlayController.showFullscreenOverlay(
                applicationContext,
                frame,
                metrics,
                overlayMode
            )
            if (!shown) {
                frame.sourceBitmap.recycle()
            }
            ScreenOverlayController.clearRegionOverlays(overlayMode)
            return
        }

        ScreenOverlayController.clearFullscreenOverlay(overlayMode)
        val shown = ScreenOverlayController.showRegionOverlays(
            applicationContext,
            frame,
            metrics,
            overlayMode
        )
        if (!shown) {
            frame.sourceBitmap.recycle()
        }
    }

    private fun stopDetection(status: String, cancelJob: Boolean = true) {
        if (cancelJob) {
            detectionJob?.cancel()
        }
        detectionJob = null
        ScreenDetectionStateHolder.setIdle(status)
        updateNotification(status)
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        ScreenOverlayController.removeOverlayViews()
        detectionWindow.clear()
        overlayVisible = false
        lastPositiveTimeMs = 0L
        lastPublishedStatus = null
        captureVisibilityMonitoringEnabled = false
        isCapturedContentVisible = true
        visibilityResumeAfterMs = 0L
        runCatching { virtualDisplay?.release() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "释放 VirtualDisplay 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        virtualDisplay = null
        runCatching { imageReader?.close() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "关闭 ImageReader 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        imageReader = null
        releaseFrameBitmaps()
        mediaProjection?.unregisterCallback(projectionCallback)
        runCatching { mediaProjection?.stop() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "停止 MediaProjection 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        mediaProjection = null
        runCatching { yoloRunner?.close() }
        yoloRunner = null
        currentVariant = null
        overlayMetrics = null
    }

    private fun overlayUnavailableMessage(mode: ScreenOverlayMode): String {
        return when (mode) {
            ScreenOverlayMode.ACCESSIBILITY -> getString(R.string.screen_detection_status_accessibility_missing)
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> getString(R.string.screen_detection_status_overlay_missing)
        }
    }

    private suspend fun ensureOverlayReady() {
        if (ScreenOverlayController.isOverlayReady(applicationContext, overlayMode)) return
        if (!ScreenOverlayController.isOverlayPermissionGranted(applicationContext, overlayMode)) {
            error(overlayUnavailableMessage(overlayMode))
        }
        if (overlayMode != ScreenOverlayMode.ACCESSIBILITY) {
            error(overlayUnavailableMessage(overlayMode))
        }

        DebugLogManager.addLog("屏幕检测", "等待无障碍遮挡服务连接")
        val deadline = System.currentTimeMillis() + ACCESSIBILITY_OVERLAY_CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(ACCESSIBILITY_OVERLAY_CONNECT_POLL_MS)
            if (ScreenOverlayController.isOverlayReady(applicationContext, overlayMode)) {
                DebugLogManager.addLog("屏幕检测", "无障碍遮挡服务已连接，继续启动")
                return
            }
        }
        error(overlayUnavailableMessage(overlayMode))
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun startForegroundInternal(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.screen_detection_notification_title))
            .setContentText(status)
            .setContentIntent(stopIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.screen_detection_action_stop),
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.screen_detection_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.screen_detection_notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableIntentExtra(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    // 按短边目标值等比缩小采集面，保持宽高比、对齐偶数、且绝不放大（屏幕本就小于目标时原样采集）。
    private fun resolveCaptureSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        if (screenWidth <= 0 || screenHeight <= 0) return screenWidth to screenHeight
        val shortEdge = minOf(screenWidth, screenHeight)
        if (shortEdge <= CAPTURE_TARGET_SHORT_EDGE) return screenWidth to screenHeight
        val scale = CAPTURE_TARGET_SHORT_EDGE.toFloat() / shortEdge.toFloat()
        val width = (screenWidth * scale).toInt().coerceAtLeast(1) and 1.inv()
        val height = (screenHeight * scale).toInt().coerceAtLeast(1) and 1.inv()
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    private fun isFrameStatic(bitmap: Bitmap): Boolean {
        val size = STATIC_FRAME_SIGNATURE_SIZE
        val small = signatureBitmap ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            signatureBitmap = it
            signatureCanvas = Canvas(it)
        }
        signatureCanvas?.drawBitmap(bitmap, null, signatureDstRect, null)
        small.getPixels(signaturePixels, 0, size, 0, 0, size, size)

        val current = FloatArray(signaturePixels.size)
        for (i in signaturePixels.indices) {
            val pixel = signaturePixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // 加权灰度，整数即可，存为 Float 便于差分
            current[i] = (r * 0.299f + g * 0.587f + b * 0.114f)
        }

        val previous = lastSignature
        lastSignature = current
        if (previous == null) return false

        var sum = 0.0
        for (i in current.indices) {
            sum += kotlin.math.abs(current[i] - previous[i])
        }
        val meanDiff = sum / current.size
        return meanDiff < STATIC_FRAME_DIFF_THRESHOLD
    }

    private fun Image.copyToReusableBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        buffer.rewind()
        val crop = cropRect
        val targetWidth = crop.width()
        val targetHeight = crop.height()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val sourceWidth = width
        val sourceHeight = height
        val paddedWidth = rowStride / pixelStride

        val targetBitmap = ensureAnalysisBitmap(targetWidth, targetHeight)
        val requiresStaging = paddedWidth != sourceWidth ||
            crop.left != 0 ||
            crop.top != 0 ||
            targetWidth != sourceWidth ||
            targetHeight != sourceHeight

        if (!requiresStaging) {
            targetBitmap.copyPixelsFromBuffer(buffer)
            return targetBitmap
        }

        val paddedBitmap = ensureStagingBitmap(paddedWidth, sourceHeight)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        analysisDstRect.set(0, 0, targetWidth, targetHeight)
        analysisCanvas?.drawBitmap(paddedBitmap, crop, analysisDstRect, null)
        return targetBitmap
    }

    private fun ensureAnalysisBitmap(width: Int, height: Int): Bitmap {
        val currentBitmap = analysisBitmap
        if (currentBitmap != null &&
            currentBitmap.width == width &&
            currentBitmap.height == height &&
            !currentBitmap.isRecycled
        ) {
            return currentBitmap
        }
        currentBitmap?.takeIf { !it.isRecycled }?.recycle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        analysisBitmap = bitmap
        analysisCanvas = Canvas(bitmap)
        return bitmap
    }

    private fun ensureStagingBitmap(width: Int, height: Int): Bitmap {
        val currentBitmap = stagingBitmap
        if (currentBitmap != null &&
            currentBitmap.width == width &&
            currentBitmap.height == height &&
            !currentBitmap.isRecycled
        ) {
            return currentBitmap
        }
        currentBitmap?.takeIf { !it.isRecycled }?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            stagingBitmap = bitmap
        }
    }

    private fun releaseFrameBitmaps() {
        analysisCanvas = null
        analysisBitmap?.takeIf { !it.isRecycled }?.recycle()
        analysisBitmap = null
        stagingBitmap?.takeIf { !it.isRecycled }?.recycle()
        stagingBitmap = null
        signatureCanvas = null
        signatureBitmap?.takeIf { !it.isRecycled }?.recycle()
        signatureBitmap = null
        lastSignature = null
    }

    private data class RenderResult(
        val detectionCount: Int,
        val overlayFrame: ScreenPrivacyMaskRenderer.OverlayFrame?
    )

    private fun shouldSuppressOverlayForHiddenCapture(): Boolean {
        if (!captureVisibilityMonitoringEnabled) return false
        if (isCapturedContentVisible) return false
        return android.os.SystemClock.elapsedRealtime() >= visibilityResumeAfterMs
    }

    private fun handleCapturedContentVisibilityChanged(isVisible: Boolean) {
        if (isCapturedContentVisible == isVisible) return
        isCapturedContentVisible = isVisible
        if (isVisible) {
            visibilityResumeAfterMs =
                android.os.SystemClock.elapsedRealtime() + VISIBILITY_RESUME_GRACE_MS
            lastSignature = null
            DebugLogManager.addLog("屏幕检测", "捕获内容重新可见，允许恢复遮挡")
        } else {
            visibilityResumeAfterMs = 0L
            ScreenOverlayController.removeOverlayViews()
            detectionWindow.clear()
            overlayVisible = false
            lastPositiveTimeMs = 0L
            DebugLogManager.addLog("屏幕检测", "捕获内容不可见，暂停遮挡显示")
        }
    }

}
