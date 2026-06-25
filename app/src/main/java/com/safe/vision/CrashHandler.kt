package com.safe.vision

import android.content.Context
import com.safe.vision.BuildConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * 崩溃处理器
 * 确保应用崩溃时能够保存详细的崩溃信息
 */
object CrashHandler {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    /**
     * 初始化崩溃处理器
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        DebugLogManager.initialize(appContext)

        // 设置默认异常处理器
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                // 记录崩溃信息
                val crashInfo = generateCrashInfo(thread, exception)
                
                DebugLogManager.addLog("应用崩溃", crashInfo, DebugLogManager.LogLevel.FATAL)
                DebugLogManager.flushNow(300)
                ErrorReportManager.captureCrashAndMarkPending(appContext, crashInfo)
                
                // 调用原始处理器
                defaultHandler?.uncaughtException(thread, exception)
                
            } catch (e: Exception) {
                // 如果连崩溃处理都失败了，输出到System.err
                System.err.println("=== Crash Handler Failure ===")
                System.err.println("Original exception: ${exception.message}")
                System.err.println("Exception stack trace: ${exception.stackTraceToString()}")
                System.err.println("Error during handling: ${e.message}")
                e.printStackTrace()
            } finally {
                exitProcess(1)
            }
        }
    }
    
    /**
     * 生成崩溃信息
     */
    private fun generateCrashInfo(thread: Thread, exception: Throwable): String {
        val timestamp = LocalDateTime.now().format(dateFormat)
        val deviceInfo = getDeviceInfo()
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        
        return buildString {
            appendLine("${"=".repeat(60)}")
            appendLine("Safe Vision Crash Report")
            appendLine("${"=".repeat(60)}")
            appendLine("Crash time: $timestamp")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Android version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("Device model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Device info: $deviceInfo")
            appendLine()
            appendLine("Thread info:")
            appendLine("  Name: ${thread.name}")
            appendLine("  ID: ${thread.threadId()}")
            appendLine("  Priority: ${thread.priority}")
            appendLine()
            appendLine("Exception info:")
            appendLine("  Type: ${exception::class.java.simpleName}")
            appendLine("  Message: ${exception.message}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(exception.stackTraceToString())
            
            var cause = exception.cause
            var level = 1
            while (cause != null && level < 5) {
                appendLine()
                appendLine("Caused by $level:")
                appendLine("  Type: ${cause::class.java.simpleName}")
                appendLine("  Message: ${cause.message}")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                level++
            }
            
            appendLine("${"=".repeat(60)}")
        }
    }
    
    private fun getDeviceInfo(): String {
        return buildString {
            append("Manufacturer: ${android.os.Build.MANUFACTURER}")
            append(", Model: ${android.os.Build.MODEL}")
            append(", Product: ${android.os.Build.PRODUCT}")
            append(", Hardware: ${android.os.Build.HARDWARE}")
            append(", Serial: ${getDeviceSerial()}")
        }
    }

    private fun getDeviceSerial(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { android.os.Build.getSerial() }.getOrDefault(android.os.Build.UNKNOWN)
        } else {
            @Suppress("DEPRECATION")
            android.os.Build.SERIAL
        }
    }
}
