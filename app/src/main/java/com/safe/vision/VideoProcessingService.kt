package com.safe.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VideoProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var videoProcessingManager: VideoProcessingManager
    private lateinit var notificationManager: NotificationManager
    private var queueJob: Job? = null
    private var totalCount: Int = 0
    private var completedCount: Int = 0
    private var queueMode: Boolean = false

    companion object {
        const val CHANNEL_ID = "video_processing_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START_PROCESSING = "com.safe.vision.action.START_VIDEO_PROCESSING"
        const val ACTION_CANCEL_PROCESSING = "com.safe.vision.action.CANCEL_VIDEO_PROCESSING"
        const val EXTRA_URIS = "com.safe.vision.extra.VIDEO_URIS"
        const val EXTRA_BLOCKED_LABELS = "com.safe.vision.extra.VIDEO_BLOCKED_LABELS"
        const val EXTRA_REVERSE_LABELS = "com.safe.vision.extra.VIDEO_REVERSE_LABELS"
        const val EXTRA_BLUR_MODE = "com.safe.vision.extra.VIDEO_BLUR_MODE"
        const val EXTRA_SKIP_STRIDE = "com.safe.vision.extra.VIDEO_SKIP_STRIDE"
        const val EXTRA_HIGH_LOAD_MODE = "com.safe.vision.extra.VIDEO_HIGH_LOAD_MODE"
        const val EXTRA_LABEL_OVERRIDES = "com.safe.vision.extra.VIDEO_LABEL_OVERRIDES"

        fun startProcessing(
            context: Context,
            uris: ArrayList<android.net.Uri>,
            options: VideoProcessingManager.VideoProcessingOptions
        ) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_START_PROCESSING
                putParcelableArrayListExtra(EXTRA_URIS, uris)
                putStringArrayListExtra(EXTRA_BLOCKED_LABELS, ArrayList(options.blockedLabels))
                putStringArrayListExtra(EXTRA_REVERSE_LABELS, ArrayList(options.reverseLabels))
                putExtra(EXTRA_BLUR_MODE, options.blurMode)
                putExtra(EXTRA_SKIP_STRIDE, options.skipStride)
                putExtra(EXTRA_HIGH_LOAD_MODE, options.highLoadMode)
                putExtra(EXTRA_LABEL_OVERRIDES, HashMap(options.labelEffectOverrides))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelProcessing(context: Context) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_CANCEL_PROCESSING
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        videoProcessingManager = VideoProcessingManager.getInstance(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        DebugLogManager.addLog("视频处理服务", "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROCESSING -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_URIS, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(EXTRA_URIS)
                } ?: arrayListOf()
                if (uris.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val options = extractOptions(intent)
                startQueueProcessing(uris, options)
            }
            ACTION_CANCEL_PROCESSING -> cancelQueueProcessing()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        queueJob?.cancel()
        serviceScope.cancel()
        DebugLogManager.addLog("视频处理服务", "服务已销毁")
        super.onDestroy()
    }

    private fun startQueueProcessing(
        uris: List<android.net.Uri>,
        options: VideoProcessingManager.VideoProcessingOptions
    ) {
        queueJob?.cancel()
        videoProcessingManager.cancel()

        totalCount = uris.size
        completedCount = 0
        queueMode = uris.size > 1
        startForeground(NOTIFICATION_ID, createProcessingNotification())

        DebugLogManager.addLog("视频处理服务", "开始处理任务，共 $totalCount 个视频")
        queueJob = serviceScope.launch {
            var lastTerminalState: VideoProcessingManager.VideoProcessingState? = null
            for (uri in uris) {
                val previousState = videoProcessingManager.state.value
                videoProcessingManager.startProcessing(uri, options)
                videoProcessingManager.state.first { state -> state != previousState }
                val terminalState = videoProcessingManager.state.first { state ->
                    state is VideoProcessingManager.VideoProcessingState.Completed ||
                        state is VideoProcessingManager.VideoProcessingState.Error ||
                        state is VideoProcessingManager.VideoProcessingState.Cancelled
                }
                lastTerminalState = terminalState
                if (terminalState is VideoProcessingManager.VideoProcessingState.Completed) {
                    completedCount++
                } else if (terminalState is VideoProcessingManager.VideoProcessingState.Cancelled) {
                    break
                }
                notificationManager.notify(NOTIFICATION_ID, createProcessingNotification())
            }
            when (lastTerminalState) {
                is VideoProcessingManager.VideoProcessingState.Cancelled -> {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createFinalNotification("Safe Vision - 媒体处理已取消")
                    )
                }
                else -> {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createFinalNotification("Safe Vision - 媒体处理完成")
                    )
                }
            }
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
    }

    private fun cancelQueueProcessing() {
        DebugLogManager.addLog("视频处理服务", "取消视频处理任务")
        queueJob?.cancel()
        videoProcessingManager.cancel()
        notificationManager.notify(
            NOTIFICATION_ID,
            createFinalNotification("Safe Vision - 媒体处理已取消")
        )
        stopForegroundCompat(removeNotification = false)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "视频处理通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示视频处理状态"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createProcessingNotification(): Notification {
        val text = if (queueMode) {
            val unfinished = (totalCount - completedCount).coerceAtLeast(0)
            "已完成 $completedCount / 未完成 $unfinished"
        } else {
            "正在处理媒体"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Vision - 媒体处理中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createFinalNotification(title: String): Notification {
        val text = if (queueMode) {
            val unfinished = (totalCount - completedCount).coerceAtLeast(0)
            "已完成 $completedCount / 未完成 $unfinished"
        } else {
            "媒体处理任务结束"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun extractOptions(intent: Intent): VideoProcessingManager.VideoProcessingOptions {
        val blockedLabels = intent.getStringArrayListExtra(EXTRA_BLOCKED_LABELS) ?: arrayListOf()
        val reverseLabels = intent.getStringArrayListExtra(EXTRA_REVERSE_LABELS) ?: arrayListOf()
        val blurMode = intent.getIntExtra(EXTRA_BLUR_MODE, PrivacySettingsManager.BLUR_MODE_GAUSSIAN)
        val skipStride = intent.getIntExtra(EXTRA_SKIP_STRIDE, 3)
        val highLoadMode = intent.getBooleanExtra(EXTRA_HIGH_LOAD_MODE, false)
        val overridesSerializable: Serializable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_LABEL_OVERRIDES, HashMap::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_LABEL_OVERRIDES)
        }
        val labelOverrides = (overridesSerializable as? HashMap<*, *>)?.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val value = v as? Int ?: return@mapNotNull null
            key to value
        }?.toMap() ?: emptyMap()
        return VideoProcessingManager.VideoProcessingOptions(
            blockedLabels = blockedLabels,
            reverseLabels = reverseLabels,
            blurMode = blurMode,
            labelEffectOverrides = labelOverrides,
            skipStride = skipStride,
            highLoadMode = highLoadMode
        )
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }
}
