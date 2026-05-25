package com.safe.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
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
        private const val ACTION_START = "com.safe.vision.action.START_SCREEN_DETECTION"
        private const val ACTION_STOP = "com.safe.vision.action.STOP_SCREEN_DETECTION"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_CHANNEL_ID = "screen_detection_channel"
        private const val NOTIFICATION_ID = 3002
        private const val FLICKER_WINDOW_SIZE = 4
        private const val SHOW_THRESHOLD = 2
        private const val HIDE_TIMEOUT_ACCESSIBILITY_MS = 500L
        private const val HIDE_TIMEOUT_SYSTEM_ALERT_WINDOW_MS = 300L

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

    // 抗闪烁：滑动窗口 + 延迟清除
    private val detectionWindow = ArrayDeque<Boolean>(FLICKER_WINDOW_SIZE)
    private var overlayVisible = false
    private var lastPositiveTimeMs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            DebugLogManager.addLog("屏幕检测", "MediaProjection 已停止")
            stopDetection(getString(R.string.screen_detection_status_stopped))
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
            runCatching {
                ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_starting))
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection 初始化失败")
                mediaProjection = projection
                projection.registerCallback(projectionCallback, null)

                val appSettings = AppSettingsManager.getInstance(applicationContext)
                overlayMode = appSettings.getScreenDetectionOverlayMode()
                if (!ScreenOverlayController.isOverlayReady(applicationContext, overlayMode)) {
                    error(overlayUnavailableMessage(overlayMode))
                }

                val metrics = ScreenOverlayController.resolveOverlayMetrics(applicationContext)
                overlayMetrics = metrics
                imageReader = ImageReader.newInstance(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    PixelFormat.RGBA_8888,
                    2
                )
                virtualDisplay = projection.createVirtualDisplay(
                    "safe-vision-screen-detection",
                    metrics.widthPixels,
                    metrics.heightPixels,
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

                DebugLogManager.addLog(
                    "屏幕检测",
                    "屏幕检测已启动: ${metrics.widthPixels}x${metrics.heightPixels}, 偏移=${metrics.contentOffsetX},${metrics.contentOffsetY}, 模型=${variant.runtimeLabel}"
                )
                ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_running))
                updateNotification(getString(R.string.screen_detection_notification_running))

                detectionLoop(variant)
            }.onFailure { error ->
                DebugLogManager.addLog("屏幕检测", "启动失败: ${error.message}", DebugLogManager.LogLevel.ERROR)
                DebugLogManager.addLog("屏幕检测", error.stackTraceToString(), DebugLogManager.LogLevel.ERROR)
                stopDetection(error.message ?: getString(R.string.screen_detection_status_start_failed))
            }
        }
    }

    private suspend fun detectionLoop(variant: DetectionModelVariant) {
        while (serviceScope.isActive) {
            val bitmap = imageReader?.acquireLatestImage()?.use { it.toBitmap() }

            if (bitmap == null) {
                delay(200)
                continue
            }

            val renderResult = withContext(Dispatchers.Default) {
                val detections = yoloRunner?.run(
                    bitmap,
                    enrichFaceLandmarks = variant == DetectionModelVariant.STANDARD
                ).orEmpty()
                val profile = if (variant == DetectionModelVariant.ANIME) {
                    DetectionConfig.LabelProfile.ANIME
                } else {
                    DetectionConfig.LabelProfile.STANDARD
                }
                val overlayFrame = overlayRenderer?.render(bitmap, detections, profile)
                RenderResult(detections.size, overlayFrame)
            }

            bitmap.recycle()
            applyOverlayFrame(renderResult.overlayFrame)

            val status = if (renderResult.detectionCount == 0) {
                getString(R.string.screen_detection_status_clear)
            } else {
                getString(R.string.screen_detection_status_detected, renderResult.detectionCount)
            }
            ScreenDetectionStateHolder.setRunning(status, renderResult.detectionCount)
            updateNotification(status)
            DebugLogManager.addLog("屏幕检测", "最新一帧检测结果: ${renderResult.detectionCount} 个目标")

            delay(detectionIntervalMs)
        }
    }

    private fun applyOverlayFrame(frame: ScreenPrivacyMaskRenderer.OverlayFrame?) {
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

    private fun stopDetection(status: String) {
        detectionJob?.cancel()
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
        runCatching { virtualDisplay?.release() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "释放 VirtualDisplay 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        virtualDisplay = null
        runCatching { imageReader?.close() }
            .onFailure { e -> DebugLogManager.addLog("屏幕检测", "关闭 ImageReader 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        imageReader = null
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

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        return cropped
    }

    private data class RenderResult(
        val detectionCount: Int,
        val overlayFrame: ScreenPrivacyMaskRenderer.OverlayFrame?
    )

}
