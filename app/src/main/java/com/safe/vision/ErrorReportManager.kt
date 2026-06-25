package com.safe.vision

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ErrorReportManager {
    private const val PREFS_NAME = "error_report_prefs"
    private const val KEY_PENDING_CRASH_PATH = "pending_crash_path"
    private const val KEY_PENDING_CRASH_TIME = "pending_crash_time"

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val humanTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun captureHandledError(context: Context, source: String, message: String, throwable: Throwable? = null): File? {
        return createReportFile(
            context = context.applicationContext,
            title = "Safe Vision 可恢复错误报告",
            source = source,
            message = message,
            throwable = throwable,
            crashInfo = null
        )
    }

    fun captureCrashAndMarkPending(context: Context, crashInfo: String): File? {
        val appContext = context.applicationContext
        val file = createReportFile(
            context = appContext,
            title = "Safe Vision Crash Report",
            source = "CrashHandler",
            message = "App crashed with uncaught exception",
            throwable = null,
            crashInfo = crashInfo
        ) ?: return null

        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_CRASH_PATH, file.absolutePath)
            .putString(KEY_PENDING_CRASH_TIME, LocalDateTime.now().format(humanTimeFormatter))
            .apply()
        return file
    }

    fun maybeShowPendingCrashDialog(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING_CRASH_PATH, null) ?: return
        val file = File(path)
        if (!file.exists()) {
            clearPendingCrash(context)
            return
        }
        val crashTime = prefs.getString(KEY_PENDING_CRASH_TIME, "Unknown time") ?: "Unknown time"
        DialogUtils.builder(context)
            .setTitle(R.string.error_report_pending_title)
            .setMessage(context.getString(R.string.error_report_pending_message, crashTime))
            .setPositiveButton(R.string.error_report_share_logs) { _, _ ->
                shareReportFile(context, file)
                clearPendingCrash(context)
            }
            .setNegativeButton(R.string.error_report_remind_later, null)
            .setNeutralButton(R.string.error_report_ignore) { _, _ ->
                clearPendingCrash(context)
            }
            .show()
    }

    fun promptShareHandledError(context: Context, file: File, source: String) {
        DialogUtils.builder(context)
            .setTitle(R.string.error_report_error_title)
            .setMessage(context.getString(R.string.error_report_error_message, source))
            .setPositiveButton(R.string.error_report_share_logs) { _, _ ->
                shareReportFile(context, file)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearPendingCrash(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_CRASH_PATH)
            .remove(KEY_PENDING_CRASH_TIME)
            .apply()
    }

    private fun createReportFile(
        context: Context,
        title: String,
        source: String,
        message: String,
        throwable: Throwable?,
        crashInfo: String?
    ): File? {
        return runCatching {
            DebugLogManager.flushNow()
            val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(rootDir, "logs/error_reports").apply { if (!exists()) mkdirs() }
            val now = LocalDateTime.now()
            val file = File(dir, "error_${now.format(timestampFormatter)}.txt")
            FileWriter(file, false).use { writer ->
                writer.appendLine(title)
                writer.appendLine("Time: ${now.format(humanTimeFormatter)}")
                writer.appendLine("Source: $source")
                writer.appendLine("Message: $message")
                writer.appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                writer.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                writer.appendLine("System: Android ${android.os.Build.VERSION.RELEASE}")
                writer.appendLine("========================================")
                if (!crashInfo.isNullOrBlank()) {
                    writer.appendLine(crashInfo)
                    writer.appendLine("========================================")
                }
                if (throwable != null) {
                    writer.appendLine("Stack trace:")
                    writer.appendLine(throwable.stackTraceToString())
                    writer.appendLine("========================================")
                }
                writer.appendLine("Recent logs:")
                writer.appendLine(DebugLogManager.getLogs())
            }
            file
        }.onFailure {
            DebugLogManager.addLog("ErrorReport", "Failed to generate error report: ${it.message}", DebugLogManager.LogLevel.ERROR)
        }.getOrNull()
    }

    private fun shareReportFile(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Safe Vision Error Log")
                putExtra(Intent.EXTRA_TEXT, "Error log file: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Error Log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            DebugLogManager.addLog("ErrorReport", "Failed to share error log: ${it.message}", DebugLogManager.LogLevel.ERROR)
        }
    }
}
