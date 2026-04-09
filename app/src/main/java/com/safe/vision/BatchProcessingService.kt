package com.safe.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.app.Service.STOP_FOREGROUND_DETACH
import android.app.Service.STOP_FOREGROUND_REMOVE
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 批量处理后台服务
 * 确保批量处理在后台持续运行，即使应用切换到后台或屏幕关闭
 */
class BatchProcessingService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var batchManager: BatchProcessingManager
    private lateinit var notificationManager: NotificationManager
    
    companion object {
        const val CHANNEL_ID = "batch_processing_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_PROCESSING = "com.safe.vision.action.START_PROCESSING"
        const val ACTION_CANCEL_PROCESSING = "com.safe.vision.action.CANCEL_PROCESSING"
        const val EXTRA_URIS = "com.safe.vision.extra.URIS"
        const val EXTRA_PREFERRED_DETECTED_FOLDER = "com.safe.vision.extra.PREFERRED_DETECTED_FOLDER"
        
        fun startProcessing(
            context: Context,
            uris: ArrayList<android.net.Uri>,
            preferredDetectedFolder: String = FolderModels.SAFE_NET_DIR
        ) {
            val intent = Intent(context, BatchProcessingService::class.java).apply {
                action = ACTION_START_PROCESSING
                putParcelableArrayListExtra(EXTRA_URIS, uris)
                putExtra(EXTRA_PREFERRED_DETECTED_FOLDER, preferredDetectedFolder)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startService(intent)
        }
        
        fun cancelProcessing(context: Context) {
            val intent = Intent(context, BatchProcessingService::class.java).apply {
                action = ACTION_CANCEL_PROCESSING
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        batchManager = BatchProcessingManager.getInstance(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        DebugLogManager.addLog("批量处理服务", "服务已创建")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROCESSING -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_URIS, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(EXTRA_URIS)
                }
                if (uris != null) {
                    val preferredDetectedFolder = intent.getStringExtra(EXTRA_PREFERRED_DETECTED_FOLDER)
                        ?: FolderModels.SAFE_NET_DIR
                    startBatchProcessing(uris, preferredDetectedFolder)
                }
            }
            ACTION_CANCEL_PROCESSING -> {
                cancelBatchProcessing()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        DebugLogManager.addLog("批量处理服务", "服务已销毁")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "批量处理通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示批量图片处理进度"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startBatchProcessing(uris: List<android.net.Uri>, preferredDetectedFolder: String) {
        DebugLogManager.addLog("批量处理服务", "开始批量处理，共 ${uris.size} 张图片")
        batchManager.setPreferredDetectedFolder(preferredDetectedFolder)
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createInitialNotification())
        
        // 监听处理状态
        serviceScope.launch {
            batchManager.processingState.collect { state ->
                updateNotification(state)
            }
        }
        
        // 监听进度
        serviceScope.launch {
            batchManager.progress.collect { progress ->
                updateProgressNotification(progress)
            }
        }
        
        // 开始处理
        serviceScope.launch {
            try {
                batchManager.addBatchTasks(uris)
            } catch (e: Exception) {
                DebugLogManager.addLog("批量处理服务", "处理失败: ${e.message}")
                DebugLogManager.addLog("批量处理服务", "异常堆栈: ${e.stackTraceToString()}")
                stopForegroundCompat(removeNotification = true)
                stopSelf()
            }
        }
    }
    
    private fun cancelBatchProcessing() {
        try {
            DebugLogManager.addLog("批量处理服务", "取消批量处理")
            batchManager.cancelProcessing()
            stopForegroundCompat(removeNotification = true)
            stopSelf()
        } catch (e: Exception) {
            DebugLogManager.addLog("批量处理服务", "取消处理失败: ${e.message}")
            DebugLogManager.addLog("批量处理服务", "异常堆栈: ${e.stackTraceToString()}")
            stopForegroundCompat(removeNotification = true)
            stopSelf()
        }
    }
    
    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Vision - 批量处理")
            .setContentText("正在准备批量处理...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    private fun updateNotification(state: BatchProcessingManager.BatchProcessingState) {
        val notification = when (state) {
            is BatchProcessingManager.BatchProcessingState.Loading -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理")
                    .setContentText("正在准备批量处理...")
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            }
            is BatchProcessingManager.BatchProcessingState.LoadingModel -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理")
                    .setContentText("正在加载模型...")
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            }
            is BatchProcessingManager.BatchProcessingState.Processing -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理")
                    .setContentText("正在处理图片...")
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            }
            is BatchProcessingManager.BatchProcessingState.Completed -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理完成")
                    .setContentText("所有图片处理完成")
                    .setSmallIcon(android.R.drawable.ic_menu_save)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            is BatchProcessingManager.BatchProcessingState.Cancelled -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理已取消")
                    .setContentText("批量处理已被取消")
                    .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            is BatchProcessingManager.BatchProcessingState.Error -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Safe Vision - 批量处理失败")
                    .setContentText("处理过程中发生错误: ${state.message}")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            else -> return
        }
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // 如果处理完成或出错，停止前台服务
        if (state is BatchProcessingManager.BatchProcessingState.Completed ||
            state is BatchProcessingManager.BatchProcessingState.Cancelled ||
            state is BatchProcessingManager.BatchProcessingState.Error) {
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
    }
    
    private fun updateProgressNotification(progress: BatchProcessingManager.BatchProgress) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Vision - 批量处理")
            .setContentText("已处理 ${progress.processedCount}/${progress.totalCount} 张图片")
            .setProgress(progress.totalCount, progress.processedCount, false)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
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
