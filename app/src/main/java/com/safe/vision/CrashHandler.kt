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
                System.err.println("=== 崩溃处理器失败 ===")
                System.err.println("原始异常: ${exception.message}")
                System.err.println("异常堆栈: ${exception.stackTraceToString()}")
                System.err.println("处理异常时出错: ${e.message}")
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
            appendLine("Safe Vision 应用崩溃报告")
            appendLine("${"=".repeat(60)}")
            appendLine("崩溃时间: $timestamp")
            appendLine("应用版本: $versionName ($versionCode)")
            appendLine("Android版本: ${android.os.Build.VERSION.RELEASE}")
            appendLine("设备型号: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("设备信息: $deviceInfo")
            appendLine()
            appendLine("线程信息:")
            appendLine("  名称: ${thread.name}")
            appendLine("  ID: ${thread.threadId()}")
            appendLine("  优先级: ${thread.priority}")
            appendLine()
            appendLine("异常信息:")
            appendLine("  类型: ${exception::class.java.simpleName}")
            appendLine("  消息: ${exception.message}")
            appendLine()
            appendLine("堆栈跟踪:")
            appendLine(exception.stackTraceToString())
            
            // 添加原因异常
            var cause = exception.cause
            var level = 1
            while (cause != null && level < 5) {
                appendLine()
                appendLine("原因异常 $level:")
                appendLine("  类型: ${cause::class.java.simpleName}")
                appendLine("  消息: ${cause.message}")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                level++
            }
            
            appendLine("${"=".repeat(60)}")
        }
    }
    
    private fun getDeviceInfo(): String {
        return buildString {
            append("制造商: ${android.os.Build.MANUFACTURER}")
            append(", 型号: ${android.os.Build.MODEL}")
            append(", 产品: ${android.os.Build.PRODUCT}")
            append(", 硬件: ${android.os.Build.HARDWARE}")
            append(", 序列号: ${getDeviceSerial()}")
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
