package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量图片处理管理器
 * 负责管理批量图片处理的队列、进度和状态
 */
class BatchProcessingManager private constructor(private val context: Context) {
    
    private val _processingState = MutableStateFlow<BatchProcessingState>(BatchProcessingState.Idle)
    val processingState: StateFlow<BatchProcessingState> = _processingState.asStateFlow()
    
    private val _progress = MutableStateFlow<BatchProgress>(BatchProgress(0, 0, 0))
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()
    
    private val _results = MutableStateFlow<List<BatchProcessingResult>>(emptyList())
    val results: StateFlow<List<BatchProcessingResult>> = _results.asStateFlow()
    
    private var processingQueue = Channel<BatchProcessingTask>(256)
    private val isProcessing = AtomicBoolean(false)
    private val processedCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)
    private val inferenceMutex = Mutex()
    private val resultsMutex = Mutex()
    
    private var yoloRunner: YoloOnnxRunner? = null
    private var isModelLoaded = false
    private var loadedModelVariant: DetectionModelVariant? = null
    private var processingJob: Job? = null
    @Volatile
    private var preferredDetectedFolder: String = FolderModels.SAFE_NET_DIR
    
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tempInputDir = File(context.cacheDir, "batch_inputs")
    
    companion object {
        @Volatile
        private var INSTANCE: BatchProcessingManager? = null
        
        fun getInstance(context: Context): BatchProcessingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatchProcessingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 添加批量处理任务
     */
    suspend fun addBatchTasks(uris: List<Uri>) {
        try {
            if (uris.isEmpty()) {
                DebugLogManager.addLog("批量处理", "没有选择图片")
                return
            }

            DebugLogManager.addLog("批量处理", "开始添加 ${uris.size} 张图片到处理队列")

            // 取消进行中的任务并等待其完全停止，再重建有界队列，防止旧协程访问新 Channel
            processingJob?.cancelAndJoin()
            processingQueue.close()
            processingQueue = Channel(256)

            // 重置计数器
            processedCount.set(0)
            totalCount.set(uris.size)
            _results.value = emptyList()
            clearTempInputDir()

            // 先启动消费者协程，再生产任务，避免有界 Channel 在任务数 > 容量时死锁
            isProcessing.set(true)
            DebugLogManager.addLog("批量处理", "队列已就绪，启动消费者协程")
            startProcessing()

            // 向已运行的消费者逐一发送任务；Channel 满时自动挂起，由消费者腾出空间后继续
            uris.forEachIndexed { index, uri ->
                try {
                    val task = createTaskFromUri(uri, index)
                    processingQueue.send(task)
                    DebugLogManager.addLog("批量处理", "已添加任务: ${task.fileName}")
                } catch (e: Exception) {
                    DebugLogManager.addLog("批量处理", "添加任务失败: $uri, 错误: ${e.message}")
                }
            }

            // 关闭 Channel，通知消费者所有任务已全部发送完毕
            processingQueue.close()
            DebugLogManager.addLog("批量处理", "所有任务已添加到队列")

        } catch (e: Exception) {
            DebugLogManager.addLog("批量处理", "添加批量任务失败: ${e.message}")
            DebugLogManager.addLog("批量处理", "异常堆栈: ${e.stackTraceToString()}")
            _processingState.value = BatchProcessingState.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 取消批量处理
     */
    fun cancelProcessing() {
        processingJob?.cancel()
        processingQueue.close()
        processingQueue = Channel(256)
        clearQueue()
        isProcessing.set(false)
        _processingState.value = BatchProcessingState.Cancelled
        DebugLogManager.addLog("批量处理", "批量处理已取消")
    }
    
    /**
     * 清空结果
     */
    fun clearResults() {
        _results.value = emptyList()
        processedCount.set(0)
        totalCount.set(0)
        _progress.value = BatchProgress(0, 0, 0)
        DebugLogManager.addLog("批量处理", "处理结果已清空")
    }

    fun setPreferredDetectedFolder(folderName: String) {
        preferredDetectedFolder = folderName.ifBlank { FolderModels.SAFE_NET_DIR }
    }
    
    private suspend fun startProcessing() {
        try {
            DebugLogManager.addLog("批量处理", "startProcessing() 已进入")
            _processingState.value = BatchProcessingState.Loading
            
            // 确保模型已加载
            val targetVariant = AppSettingsManager.getInstance(context).getDetectionModelVariant()
            if (!isModelLoaded || loadedModelVariant != targetVariant || yoloRunner == null) {
                _processingState.value = BatchProcessingState.LoadingModel
                DebugLogManager.addLog("批量处理", "开始加载YOLO模型: ${targetVariant.displayName}")
                try {
                    yoloRunner = withContext(Dispatchers.IO) {
                        YoloModelProvider.getRunner(context, targetVariant)
                    }
                    isModelLoaded = true
                    loadedModelVariant = targetVariant
                    DebugLogManager.addLog("批量处理", "YOLO模型加载成功: ${targetVariant.displayName}")
                } catch (e: Exception) {
                    DebugLogManager.addLog("批量处理", "模型加载失败: ${e.message}")
                    DebugLogManager.addLog("批量处理", "异常堆栈: ${e.stackTraceToString()}")
                    _processingState.value = BatchProcessingState.Error(e.message ?: "模型加载失败")
                    isProcessing.set(false)
                    return
                }
            }
            
            _processingState.value = BatchProcessingState.Processing
            DebugLogManager.addLog("批量处理", "开始批量处理 ${totalCount.get()} 张图片")
            
            processingJob = processingScope.launch {
                try {
                    DebugLogManager.addLog("批量处理", "消费协程已启动，准备拉取队列任务")
                    if (totalCount.get() >= 2) {
                        val maxConcurrency = 2
                        DebugLogManager.addLog("批量处理", "检测到多张图片，使用双线程处理")
                        processWithMultiThreading(maxConcurrency)
                    } else {
                        DebugLogManager.addLog("批量处理", "单张图片，使用顺序处理")
                        processSequentially()
                    }
                    
                    _processingState.value = BatchProcessingState.Completed
                    DebugLogManager.addLog("批量处理", "批量处理完成，共处理 ${processedCount.get()} 张图片")
                } catch (e: Exception) {
                    DebugLogManager.addLog("批量处理", "批量处理失败: ${e.message}")
                    DebugLogManager.addLog("批量处理", "异常堆栈: ${e.stackTraceToString()}")
                    _processingState.value = BatchProcessingState.Error(e.message ?: "处理失败")
                } finally {
                    isProcessing.set(false)
                }
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("批量处理", "启动处理失败: ${e.message}")
            DebugLogManager.addLog("批量处理", "异常堆栈: ${e.stackTraceToString()}")
            _processingState.value = BatchProcessingState.Error(e.message ?: "启动失败")
            isProcessing.set(false)
        }
    }
    
    private suspend fun processSequentially() {
        for (task in processingQueue) {
            processTask(task)
        }
    }
    
    private suspend fun processWithMultiThreading(maxConcurrency: Int) {
        DebugLogManager.addLog("批量处理", "使用多线程处理，最大并发数: $maxConcurrency")

        coroutineScope {
            repeat(maxConcurrency) {
                launch {
                    for (task in processingQueue) {
                        processTask(task)
                    }
                }
            }
        }
    }
    
    private suspend fun processTask(task: BatchProcessingTask) {
        try {
            DebugLogManager.addLog("批量处理", "开始处理图片: ${task.fileName}")
            val startTime = System.currentTimeMillis()
            
            // 更新进度
            updateProgress()
            
            // 处理图片
            val result = processImage(task)
            
            val processingTime = System.currentTimeMillis() - startTime
            DebugLogManager.addLog("批量处理", "图片处理完成: ${task.fileName}, 耗时: ${processingTime}ms")
            
            // 添加结果
            addResult(result)
            
        } catch (e: Exception) {
            DebugLogManager.addLog("批量处理", "图片处理失败: ${task.fileName}, 错误: ${e.message}")
            val errorResult = BatchProcessingResult(
                task = task,
                success = false,
                errorMessage = e.message ?: "未知错误"
            )
            addResult(errorResult)
        } finally {
            safeDeleteTempFile(task.tempFile)
            // 释放URI权限
            releaseUriPermission(task.uri)
        }
        
        processedCount.incrementAndGet()
        updateProgress()
    }
    
    private suspend fun processImage(task: BatchProcessingTask): BatchProcessingResult {
        val runner = yoloRunner ?: throw IllegalStateException("YOLO模型未初始化")
        val bytes = withContext(Dispatchers.IO) {
            task.tempFile.readBytes()
        }
        val bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(task.tempFile.absolutePath)
        } ?: throw IOException("图片格式不受支持")
        
        val detections = try {
            // 运行检测
            inferenceMutex.withLock {
                withContext(Dispatchers.Default) {
                    runner.run(bitmap)
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        
        // 保存结果
        val saveResult = MediaSaveHelper.saveImage(
            context = context,
            bytes = bytes,
            originalName = task.fileName,
            detections = detections,
            preferredDetectedFolder = preferredDetectedFolder
        )
        
        return BatchProcessingResult(
            task = task,
            success = true,
            detections = detections,
            imageFile = saveResult.imageFile,
            metadataFile = saveResult.metadataFile,
            hasDetections = saveResult.hasDetections
        )
    }
    
    private suspend fun createTaskFromUri(uri: Uri, index: Int): BatchProcessingTask {
        val fileName = queryDisplayName(uri) ?: "image_$index"
        val tempFile = copyToTempInput(uri, index, fileName)
        
        return BatchProcessingTask(
            id = index.toLong(),
            uri = uri,
            tempFile = tempFile,
            fileName = fileName
        )
    }

    private suspend fun copyToTempInput(uri: Uri, index: Int, fileName: String): File {
        return withContext(Dispatchers.IO) {
            if (!tempInputDir.exists() && !tempInputDir.mkdirs()) {
                throw IOException("无法创建临时目录")
            }

            val ext = fileName.substringAfterLast('.', "")
                .lowercase(Locale.US)
                .takeIf { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() } }
                ?: "img"
            val tempFile = File(
                tempInputDir,
                "batch_${System.currentTimeMillis()}_${index}.${ext}"
            )
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("无法读取图片内容")

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IOException("写入临时文件失败")
            }
            tempFile
        }
    }
    
    private suspend fun queryDisplayName(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }
    }
    
    private fun updateProgress() {
        val processed = processedCount.get()
        val total = totalCount.get()
        _progress.value = BatchProgress(processed, total, if (total > 0) (processed * 100 / total) else 0)
    }
    
    private suspend fun addResult(result: BatchProcessingResult) {
        resultsMutex.withLock {
            val currentResults = _results.value.toMutableList()
            currentResults.add(result)
            _results.value = currentResults
        }
    }

    private fun clearQueue() {
        while (true) {
            val task = processingQueue.tryReceive().getOrNull() ?: break
            safeDeleteTempFile(task.tempFile)
        }
    }

    private fun clearTempInputDir() {
        if (!tempInputDir.exists()) return
        tempInputDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                runCatching { file.delete() }
            }
        }
    }

    private fun safeDeleteTempFile(file: File) {
        if (!file.exists()) return
        runCatching { file.delete() }
    }

    private fun releaseUriPermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            DebugLogManager.addLog("权限管理", "已释放URI权限: $uri")
        } catch (e: Exception) {
            // 忽略释放失败(可能是系统图库选择的URI,本身就不是持久化权限)
            DebugLogManager.addLog("权限管理", "释放URI权限失败(可忽略): ${e.message}")
        }
    }

    /**
     * 批量处理状态
     */
    sealed class BatchProcessingState {
        object Idle : BatchProcessingState()
        object Loading : BatchProcessingState()
        object LoadingModel : BatchProcessingState()
        object Processing : BatchProcessingState()
        object Completed : BatchProcessingState()
        object Cancelled : BatchProcessingState()
        data class Error(val message: String) : BatchProcessingState()
    }
    
    /**
     * 批量处理进度
     */
    data class BatchProgress(
        val processedCount: Int,
        val totalCount: Int,
        val percentage: Int
    )
    
    /**
     * 批量处理任务
     */
    data class BatchProcessingTask(
        val id: Long,
        val uri: Uri,
        val tempFile: File,
        val fileName: String
    )
    
    /**
     * 批量处理结果
     */
    data class BatchProcessingResult(
        val task: BatchProcessingTask,
        val success: Boolean,
        val detections: List<YoloOnnxRunner.Detection> = emptyList(),
        val imageFile: File? = null,
        val metadataFile: File? = null,
        val hasDetections: Boolean = false,
        val errorMessage: String? = null
    )
}
