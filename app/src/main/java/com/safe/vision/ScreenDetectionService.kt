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
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
        private const val HIDE_TIMEOUT_ACCESSIBILITY_MS = 180L
        private const val HIDE_TIMEOUT_SYSTEM_ALERT_WINDOW_MS = 120L
        private const val ACCESSIBILITY_OVERLAY_CONNECT_TIMEOUT_MS = 2_000L
        private const val ACCESSIBILITY_OVERLAY_CONNECT_POLL_MS = 100L
        private const val VISIBILITY_RESUME_GRACE_MS = 150L
        private const val STATIC_FRAME_SIGNATURE_SIZE = 64
        private const val PERFORMANCE_LOG_INTERVAL_MS = 5_000L
        // 采集端降分辨率：把镜像捕获面的短边压到该目标值，遮挡坐标在叠加边界按比例还原。
        // 模型输入只有 320，全屏挡/黑块/马赛克对像素精度不敏感，缩小可显著降低每帧 RGBA 搬运与缩放开销。
        private const val CAPTURE_TARGET_SHORT_EDGE = 480
        private const val STATIC_RECHECK_MS = 500L

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
    private val frameAvailable = Channel<Unit>(Channel.CONFLATED)
    private var hideOverlayJob: Job? = null
    private var lastDetectionTimeMs = 0L
    private var mediaProjection: MediaProjection? = null
    private var captureSource: ScreenCaptureSource? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var yoloRunner: YoloOnnxRunner? = null
    private var currentVariant: DetectionModelVariant? = null
    private var overlayRenderer: ScreenPrivacyMaskRenderer? = null
    private var detectionIntervalMs: Long = 100L
    private var overlayMetrics: OverlayMetrics? = null
    private var overlayMode: ScreenOverlayMode = ScreenOverlayMode.ACCESSIBILITY
    private var analysisBitmap: Bitmap? = null
    private var analysisCanvas: Canvas? = null
    private var stagingBitmap: Bitmap? = null
    private val analysisDstRect = Rect()
    private var captureVisibilityMonitoringEnabled = false
    private var isCapturedContentVisible = true
    private var pendingVisibilityResume = false
    private var visibilityResumeAfterMs: Long = 0L
    private var captureVisibilityGeneration = 0L

    // 使用彩色指纹，避免亮度相近的颜色变化和局部移动被整屏平均值淹没。
    private var signatureBitmap: Bitmap? = null
    private var signatureCanvas: Canvas? = null
    private val signaturePixels = IntArray(STATIC_FRAME_SIGNATURE_SIZE * STATIC_FRAME_SIGNATURE_SIZE)
    private val frameChangeDetector = ScreenFrameChangeDetector(STATIC_FRAME_SIGNATURE_SIZE)
    private val signaturePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val signatureDstRect = Rect(0, 0, STATIC_FRAME_SIGNATURE_SIZE, STATIC_FRAME_SIGNATURE_SIZE)

    // 首次命中立即显示；阴性延迟清除以容忍短暂漏检。
    private var overlayVisible = false
    private var overlayUnavailableLogged = false

    // 去抖：仅在状态文案变化时刷新通知/状态/日志，避免每帧抢主线程做 notify()
    private var lastPublishedStatus: String? = null
    private var performanceWindowStartMs = 0L
    private var performanceSamples = 0
    private var captureTimeTotalMs = 0L
    private var detectionTimeTotalMs = 0L
    private var renderTimeTotalMs = 0L
    private var submitTimeTotalMs = 0L
    private var cycleTimeTotalMs = 0L
    private var cycleTimeMaxMs = 0L

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
                    return START_NOT_STICKY
                }
                startDetection(resultCode, resultData)
                return START_NOT_STICKY
            }
        }
        DebugLogManager.addLog("屏幕检测", "服务重启时未收到启动参数，停止保活", DebugLogManager.LogLevel.WARN)
        stopDetection(getString(R.string.screen_detection_status_start_failed))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detectionJob?.cancel()
        releaseResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDetection(resultCode: Int, resultData: Intent) {
        if (detectionJob?.isActive == true) {
            DebugLogManager.addLog("屏幕检测", "检测已在运行，忽略重复启动")
            return
        }

        val previousJob = detectionJob
        detectionJob = serviceScope.launch {
            // A cancelled inference can still be using its bitmaps until the native call returns.
            previousJob?.join()
            try {
                ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_starting))
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection initialization failed")
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
                captureSource = ScreenCaptureSource(captureWidth, captureHeight) {
                    frameAvailable.trySend(Unit)
                }
                virtualDisplay = projection.createVirtualDisplay(
                    "safe-vision-screen-detection",
                    captureWidth,
                    captureHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSource?.surface,
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
                    yoloRunner = withContext(Dispatchers.IO) {
                        YoloModelProvider.getRunner(applicationContext, variant)
                    }
                    currentVariant = variant
                }
                captureVisibilityMonitoringEnabled = appSettings.isScreenLossAutoPauseEnabled() &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                isCapturedContentVisible = true
                pendingVisibilityResume = false
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
            } finally {
                withContext(NonCancellable + Dispatchers.Default) {
                    releaseFrameBitmaps()
                }
            }
        }
    }

    private suspend fun detectionLoop(variant: DetectionModelVariant) {
        var pendingFrame: ScreenPrivacyMaskRenderer.OverlayFrame? = null
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                // New frames drive normal work. A bounded timeout also rechecks a retained static
                // capture (MediaProjection may stop producing buffers for an unchanged screen).
                val recheckInterval = when {
                    analysisBitmap == null || !isCapturedContentVisible -> STATIC_RECHECK_MS
                    hideOverlayJob != null -> 1L
                    else -> (lastDetectionTimeMs + STATIC_RECHECK_MS -
                        android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                }
                withTimeoutOrNull(recheckInterval) { frameAvailable.receive() }
                if (captureVisibilityMonitoringEnabled && shouldSuppressOverlayForHiddenCapture()) {
                    withContext(Dispatchers.Default) {
                        captureSource?.withLatestImage { /* Drain hidden content without copying. */ }
                    }
                    continue
                }
                val cycleStart = android.os.SystemClock.elapsedRealtime()
                val visibilityGeneration = captureVisibilityGeneration
                val capture = withContext(Dispatchers.Default) {
                    val bitmap = captureSource?.withLatestImage { image ->
                        image.copyToReusableBitmap()
                    }
                    val frame = bitmap ?: analysisBitmap
                    frame?.let {
                        CapturedFrame(
                            it,
                            if (bitmap != null) analyseFrame(it) else frameChangeDetector.hasChanged(signaturePixels)
                        )
                    }
                }
                val frameBitmap = capture?.bitmap ?: continue
                if (visibilityGeneration != captureVisibilityGeneration) continue
                val now = android.os.SystemClock.elapsedRealtime()
                val needsConfirmation = hideOverlayJob != null
                if (!capture.hasChanged && !needsConfirmation &&
                    now - lastDetectionTimeMs < STATIC_RECHECK_MS) {
                    // 画面静止：跳过整条 YOLO 链路，遮挡与状态保持上一帧不动
                    delayRemainingInterval(cycleStart)
                    continue
                }

                val renderResult = withContext(Dispatchers.Default) {
                    runDetectionAndRender(frameBitmap, variant).also {
                        pendingFrame = it.overlayFrame
                    }
                }
                if (visibilityGeneration != captureVisibilityGeneration) {
                    pendingFrame?.release()
                    pendingFrame = null
                    continue
                }
                // Only advance the baseline after an accepted inference; slow changes accumulate.
                frameChangeDetector.markDetected(signaturePixels)
                lastDetectionTimeMs = now
                val applyStart = android.os.SystemClock.elapsedRealtime()
                applyOverlayFrame(renderResult.overlayFrame)
                pendingFrame = null // Ownership transferred to the window host (or released there).
                recordPerformance(cycleStart, now, applyStart, renderResult)

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
        } finally {
            // withContext may be cancelled after rendering but before it can deliver the result.
            pendingFrame?.release()
        }
    }

    private suspend fun runDetectionAndRender(
        bitmap: Bitmap,
        variant: DetectionModelVariant
    ): RenderResult {
        val detectionStart = android.os.SystemClock.elapsedRealtime()
        val detections = yoloRunner?.run(
            bitmap,
            // Skip face-landmark enrichment during realtime screen detection to reduce latency.
            enrichFaceLandmarks = false
        ).orEmpty()
        val renderStart = android.os.SystemClock.elapsedRealtime()
        val profile = if (variant == DetectionModelVariant.ANIME) {
            DetectionConfig.LabelProfile.ANIME
        } else {
            DetectionConfig.LabelProfile.STANDARD
        }
        val shouldRenderOverlay =
            overlayRenderer?.shouldRenderOverlay(detections, profile, overlayMode) == true
        val overlayFrame = if (shouldRenderOverlay) {
            // Rendering finishes before the next capture writes into this reusable bitmap.
            // OverlayFrame contains only geometry and independent patches, never this input.
            val overlayBitmap = bitmap
            val currentMetrics = overlayMetrics
            val outputScaleX = if (currentMetrics != null && overlayBitmap.width > 0) {
                currentMetrics.widthPixels.toFloat() / overlayBitmap.width.toFloat()
            } else {
                1f
            }
            val outputScaleY = if (currentMetrics != null && overlayBitmap.height > 0) {
                currentMetrics.heightPixels.toFloat() / overlayBitmap.height.toFloat()
            } else {
                1f
            }
            overlayRenderer?.render(
                overlayBitmap,
                detections,
                profile,
                overlayMode,
                outputScaleX,
                outputScaleY
            )
        } else {
            null
        }
        return RenderResult(
            detections.size,
            overlayFrame,
            renderStart - detectionStart,
            android.os.SystemClock.elapsedRealtime() - renderStart
        )
    }

    // 把处理耗时计入周期：实际周期 = max(间隔, 处理耗时),而非「间隔 + 处理耗时」,降低端到端延迟。
    private suspend fun delayRemainingInterval(cycleStart: Long) {
        val elapsed = android.os.SystemClock.elapsedRealtime() - cycleStart
        // A slow user interval must not postpone a miss recheck beyond the hide deadline.
        val interval = if (hideOverlayJob != null) {
            minOf(detectionIntervalMs, hideTimeoutMs() / 2)
        } else {
            detectionIntervalMs
        }
        val remaining = interval - elapsed
        if (remaining > 0) delay(remaining)
    }

    private fun hideTimeoutMs(): Long = when (overlayMode) {
        ScreenOverlayMode.ACCESSIBILITY -> HIDE_TIMEOUT_ACCESSIBILITY_MS
        ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> HIDE_TIMEOUT_SYSTEM_ALERT_WINDOW_MS
    }

    private fun recordPerformance(cycleStart: Long, captureEnd: Long, applyStart: Long, result: RenderResult) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (performanceSamples == 0) performanceWindowStartMs = cycleStart
        performanceSamples++
        captureTimeTotalMs += captureEnd - cycleStart
        detectionTimeTotalMs += result.detectionMs
        renderTimeTotalMs += result.renderMs
        submitTimeTotalMs += now - applyStart
        cycleTimeTotalMs += now - cycleStart
        cycleTimeMaxMs = maxOf(cycleTimeMaxMs, now - cycleStart)
        if (now - performanceWindowStartMs < PERFORMANCE_LOG_INTERVAL_MS) return
        DebugLogManager.addLog(
            "屏幕性能",
            "${performanceSamples} 次检测均值: 采集/差分=${captureTimeTotalMs / performanceSamples}ms, " +
                "检测=${detectionTimeTotalMs / performanceSamples}ms, 渲染=${renderTimeTotalMs / performanceSamples}ms, " +
                "提交=${submitTimeTotalMs / performanceSamples}ms, 处理=${cycleTimeTotalMs / performanceSamples}ms, " +
                "最长=${cycleTimeMaxMs}ms, 设置间隔=${detectionIntervalMs}ms（不含系统捕获/屏幕呈现等待）"
        )
        resetPerformanceCounters()
    }

    private fun resetPerformanceCounters() {
        performanceSamples = 0
        captureTimeTotalMs = 0L
        detectionTimeTotalMs = 0L
        renderTimeTotalMs = 0L
        submitTimeTotalMs = 0L
        cycleTimeTotalMs = 0L
        cycleTimeMaxMs = 0L
    }

    private fun applyOverlayFrame(frame: ScreenPrivacyMaskRenderer.OverlayFrame?) {
        if (shouldSuppressOverlayForHiddenCapture()) {
            ScreenOverlayController.clearMaskOverlays(overlayMode)
            overlayVisible = false
            frame?.release()
            return
        }

        if (!isOverlayReadyForRendering()) {
            ScreenOverlayController.clearMaskOverlays(overlayMode)
            overlayVisible = false
            frame?.release()
            return
        }

        val hasDetection = frame != null
        val hideTimeoutMs = hideTimeoutMs()

        if (hasDetection) {
            hideOverlayJob?.cancel()
            hideOverlayJob = null
        } else if (overlayVisible && hideOverlayJob == null) {
            // A negative result starts a bounded hold, even if capture produces no further frames.
            hideOverlayJob = serviceScope.launch {
                delay(hideTimeoutMs)
                ScreenOverlayController.clearMaskOverlays(overlayMode)
                overlayVisible = false
                hideOverlayJob = null
            }
        }

        // 阴性清除保护期内保持上一帧；阳性无需再等一轮推理确认。
        if (frame == null) return

        val metrics = ScreenOverlayController.resolveOverlayMetrics(applicationContext, overlayMode)
        overlayMetrics = metrics
        overlayVisible = true

        val preferFullscreenOverlay =
            overlayMode == ScreenOverlayMode.ACCESSIBILITY

        if (frame.requiresFullscreenOverlay || frame.drawTasks.isEmpty() || preferFullscreenOverlay) {
            // 先清理旧窗口，再绑定新帧，避免清理递增 generation 后使位置校正失效。
            ScreenOverlayController.clearRegionOverlays(overlayMode)
            val shown = ScreenOverlayController.showFullscreenOverlay(
                applicationContext,
                frame,
                metrics,
                overlayMode
            )
            if (!shown) {
                frame.release()
            }
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
            frame.release()
        }
    }

    private fun isOverlayReadyForRendering(): Boolean {
        val ready = ScreenOverlayController.isOverlayReady(applicationContext, overlayMode)
        if (ready) {
            if (overlayUnavailableLogged) {
                DebugLogManager.addLog("屏幕检测", "遮挡层已就绪，恢复显示屏幕遮挡")
                overlayUnavailableLogged = false
            }
            return true
        }

        if (!overlayUnavailableLogged) {
            val message = when {
                !ScreenOverlayController.isOverlayPermissionGranted(applicationContext, overlayMode) ->
                    "${overlayUnavailableMessage(overlayMode)}，已跳过本次遮挡渲染"
                overlayMode == ScreenOverlayMode.ACCESSIBILITY ->
                    "无障碍遮挡服务尚未连接，已继续检测；连接后将自动恢复屏幕遮挡"
                else ->
                    "${overlayUnavailableMessage(overlayMode)}，已跳过本次遮挡渲染"
            }
            DebugLogManager.addLog("屏幕检测", message, DebugLogManager.LogLevel.WARN)
            overlayUnavailableLogged = true
        }
        return false
    }

    private fun stopDetection(status: String, cancelJob: Boolean = true) {
        if (cancelJob) {
            detectionJob?.cancel()
        }
        ScreenDetectionStateHolder.setIdle(status)
        updateNotification(status)
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        hideOverlayJob?.cancel()
        hideOverlayJob = null
        ScreenOverlayController.removeOverlayViews()
        overlayVisible = false
        overlayUnavailableLogged = false
        lastPublishedStatus = null
        resetPerformanceCounters()
        captureVisibilityMonitoringEnabled = false
        isCapturedContentVisible = true
        pendingVisibilityResume = false
        visibilityResumeAfterMs = 0L
        runCatching { virtualDisplay?.release() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "释放 VirtualDisplay 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        virtualDisplay = null
        captureSource?.close()
        captureSource = null
        mediaProjection?.unregisterCallback(projectionCallback)
        runCatching { mediaProjection?.stop() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "停止 MediaProjection 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        mediaProjection = null
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
        DebugLogManager.addLog(
            "屏幕检测",
            "无障碍遮挡服务连接超时，先继续启动检测；服务连接后会自动恢复屏幕遮挡",
            DebugLogManager.LogLevel.WARN
        )
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
        return PendingIntent.FLAG_IMMUTABLE
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

    private data class CapturedFrame(
        val bitmap: Bitmap,
        val hasChanged: Boolean
    )

    private fun analyseFrame(bitmap: Bitmap): Boolean {
        val size = STATIC_FRAME_SIGNATURE_SIZE
        val small = signatureBitmap ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            signatureBitmap = it
            signatureCanvas = Canvas(it)
        }
        signatureCanvas?.drawBitmap(bitmap, null, signatureDstRect, signaturePaint)
        small.getPixels(signaturePixels, 0, size, 0, 0, size, size)
        return frameChangeDetector.hasChanged(signaturePixels)
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
        frameChangeDetector.reset()
    }

    private data class RenderResult(
        val detectionCount: Int,
        val overlayFrame: ScreenPrivacyMaskRenderer.OverlayFrame?,
        val detectionMs: Long,
        val renderMs: Long
    )

    private fun shouldSuppressOverlayForHiddenCapture(): Boolean {
        if (!captureVisibilityMonitoringEnabled) return false
        if (pendingVisibilityResume) {
            if (android.os.SystemClock.elapsedRealtime() < visibilityResumeAfterMs) {
                return true
            }
            pendingVisibilityResume = false
            isCapturedContentVisible = true
            return false
        }
        if (isCapturedContentVisible) return false
        return true
    }

    private fun handleCapturedContentVisibilityChanged(isVisible: Boolean) {
        if (isVisible) {
            if (isCapturedContentVisible && !pendingVisibilityResume) return
            captureVisibilityGeneration++
            pendingVisibilityResume = true
            visibilityResumeAfterMs =
                android.os.SystemClock.elapsedRealtime() + VISIBILITY_RESUME_GRACE_MS
            frameChangeDetector.reset()
            DebugLogManager.addLog("屏幕检测", "捕获内容重新可见，延迟恢复遮挡")
        } else {
            captureVisibilityGeneration++
            isCapturedContentVisible = false
            pendingVisibilityResume = false
            visibilityResumeAfterMs = 0L
            hideOverlayJob?.cancel()
            hideOverlayJob = null
            ScreenOverlayController.removeOverlayViews()
            overlayVisible = false
            DebugLogManager.addLog("屏幕检测", "捕获内容不可见，暂停遮挡显示")
        }
    }

}
