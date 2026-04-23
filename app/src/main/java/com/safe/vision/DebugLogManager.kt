package com.safe.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.safe.vision.BuildConfig
import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

object DebugLogManager {
    private const val MAX_IN_MEMORY_LOGS = 1000
    private const val MAX_LOG_FILE_SIZE_BYTES = 2 * 1024 * 1024 // 2MB
    private const val MAX_LOG_FILE_COUNT = 5
    private const val FLUSH_INTERVAL_MS = 800L
    private const val FLUSH_BATCH_SIZE = 50

    private val logs = ArrayDeque<String>()
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val headerDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val listeners = CopyOnWriteArraySet<WeakReference<(String) -> Unit>>()
    private val logsLock = Any()
    private val fileLock = Any()
    private val pendingWrites = ConcurrentLinkedQueue<String>()
    private var flushScheduler: ScheduledExecutorService? = null
    private var fileWriter: BufferedWriter? = null
    private var logDir: File? = null

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR, FATAL
    }

    @Volatile
    private var isInitialized = false
    private var logFile: File? = null

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(fileLock) {
            if (isInitialized) return
            val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
            logDir = File(rootDir, "logs").apply { if (!exists()) mkdirs() }
            val timestamp = LocalDateTime.now().format(fileDateFormat)
            logFile = File(logDir, "safe_vision_log_$timestamp.txt")
            openWriterLocked()
            writeSessionHeaderLocked()
            cleanupOldFilesLocked()
            startFlushSchedulerLocked()
            isInitialized = true
        }
    }

    fun addLog(tag: String, message: String) {
        addLog(tag, message, inferLevel(tag, message))
    }

    fun addLog(tag: String, message: String, level: LogLevel) {
        val timestamp = LocalDateTime.now().format(dateFormat)
        val logLine = "[$timestamp] $tag: $message"

        synchronized(logsLock) {
            logs.offerLast(logLine)
            while (logs.size > MAX_IN_MEMORY_LOGS) {
                logs.pollFirst()
            }
        }

        val activeListeners = mutableListOf<(String) -> Unit>()
        listeners.forEach { ref ->
            val callback = ref.get()
            if (callback != null) {
                activeListeners.add(callback)
            } else {
                listeners.remove(ref)
            }
        }
        activeListeners.forEach { it(logLine) }

        pendingWrites.add(logLine)
        if (level == LogLevel.ERROR || level == LogLevel.FATAL) {
            requestFlush(immediate = true)
        }
    }

    fun getLogs(): String = synchronized(logsLock) { logs.joinToString("\n") }

    fun addListener(listener: (String) -> Unit) {
        cleanupListeners()
        if (listeners.any { it.get() === listener }) return
        listeners.add(WeakReference(listener))
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.removeIf { ref ->
            val callback = ref.get()
            callback == null || callback == listener
        }
    }

    fun copyToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Info", getLogs())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.settings_debug_copied, Toast.LENGTH_SHORT).show()
    }

    fun getCurrentLogFile(context: Context? = null): File? {
        if (!isInitialized && context != null) {
            initialize(context.applicationContext)
        }
        return logFile
    }

    private fun appendLineToFile(line: String) {
        synchronized(fileLock) {
            appendLineToFileLocked(line)
        }
    }

    fun onAppBackgrounded() {
        requestFlush(immediate = true)
    }

    fun shutdown() {
        synchronized(fileLock) {
            flushPendingLocked(force = true)
            closeWriterLocked()
            flushScheduler?.shutdown()
            flushScheduler = null
        }
    }

    fun flushNow(timeoutMs: Long = 1500L): Boolean {
        val scheduler = synchronized(fileLock) { flushScheduler }
        if (scheduler == null || scheduler.isShutdown) {
            synchronized(fileLock) {
                flushPendingLocked(force = true)
            }
            return true
        }
        return try {
            val task = scheduler.submit<Boolean> {
                synchronized(fileLock) {
                    flushPendingLocked(force = true)
                }
                true
            }
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupOldFilesLocked() {
        val dir = logDir ?: return
        val files = dir.listFiles { _, name ->
            name.startsWith("safe_vision_log_") && name.endsWith(".txt")
        }?.sortedBy { it.lastModified() } ?: return

        if (files.size <= MAX_LOG_FILE_COUNT) return
        val toDelete = files.take(files.size - MAX_LOG_FILE_COUNT)
        toDelete.forEach { it.delete() }
    }

    private fun cleanupListeners() {
        listeners.removeIf { it.get() == null }
    }

    private fun writeSessionHeader() {
        synchronized(fileLock) {
            writeSessionHeaderLocked()
        }
    }

    private fun writeSessionHeaderLocked() {
        val info = listOf(
            "Safe Vision 日志会话",
            "开始时间: ${LocalDateTime.now().format(headerDateFormat)}",
            "应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "系统版本: Android ${android.os.Build.VERSION.RELEASE}",
            "========================================",
            ""
        )
        info.forEach { line -> appendLineToFileLocked(line) }
    }

    private fun appendLineToFileLocked(line: String) {
        val file = logFile ?: return
        try {
            val writer = fileWriter ?: run {
                openWriterLocked()
                fileWriter
            } ?: return
            writer.append(line)
            writer.newLine()
        } catch (e: Exception) {
            System.err.println("写入日志文件失败: ${e.message}")
            runCatching { closeWriterLocked() }
        }
    }

    private fun openWriterLocked() {
        val file = logFile ?: return
        if (fileWriter != null) return
        fileWriter = BufferedWriter(FileWriter(file, true))
    }

    private fun closeWriterLocked() {
        runCatching { fileWriter?.flush() }
        runCatching { fileWriter?.close() }
        fileWriter = null
    }

    private fun rotateLogFileIfNeededLocked(nextLineLength: Int) {
        val dir = logDir ?: return
        val file = logFile ?: return
        val projected = file.length() + nextLineLength
        if (projected <= MAX_LOG_FILE_SIZE_BYTES) return
        closeWriterLocked()
        val timestamp = LocalDateTime.now().format(fileDateFormat)
        logFile = File(dir, "safe_vision_log_$timestamp.txt")
        openWriterLocked()
        writeSessionHeaderLocked()
        cleanupOldFilesLocked()
    }

    private fun flushPendingLocked(force: Boolean) {
        var wrote = 0
        while (true) {
            val line = pendingWrites.poll() ?: break
            rotateLogFileIfNeededLocked(line.length + 1)
            appendLineToFileLocked(line)
            wrote++
            if (!force && wrote >= FLUSH_BATCH_SIZE) break
        }
        if (wrote > 0 || force) {
            runCatching { fileWriter?.flush() }
        }
    }

    private fun requestFlush(immediate: Boolean) {
        val scheduler = synchronized(fileLock) { flushScheduler } ?: return
        if (immediate) {
            scheduler.execute {
                synchronized(fileLock) { flushPendingLocked(force = true) }
            }
        }
    }

    private fun startFlushSchedulerLocked() {
        if (flushScheduler != null) return
        flushScheduler = Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "safe-log-writer").apply { isDaemon = true }
            }
        ).also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { synchronized(fileLock) { flushPendingLocked(force = false) } },
                FLUSH_INTERVAL_MS,
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun inferLevel(tag: String, message: String): LogLevel {
        val text = "$tag $message"
        return when {
            text.contains("FATAL", ignoreCase = true) -> LogLevel.FATAL
            text.contains("ERROR", ignoreCase = true) -> LogLevel.ERROR
            text.contains("崩溃") || text.contains("异常") || text.contains("错误") || text.contains("失败") -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
    }
}
