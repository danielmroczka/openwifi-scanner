package com.dm.labs.wifi.log

import android.content.Context
import android.util.Log
import com.dm.labs.wifi.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * File-based developer logger with 7-day retention.
 * Only writes when developer logging is enabled in settings.
 * Each day gets its own file: dev_log_2026-04-13.txt
 */
object DevLog {
    private const val TAG = "WiFiDevLog"
    private const val LOG_DIR = "dev_logs"
    private const val RETENTION_DAYS = 7

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var settings: AppSettings? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        settings = AppSettings.getInstance(context)
        cleanOldLogs()
    }

    /** Log a verbose developer message (only if developer logging is enabled). */
    fun d(message: String) {
        Log.d(TAG, message)
        if (settings?.developerLogging != true) return
        writeToFile("DEBUG", message)
    }

    /** Log an important event (always written when dev logging is on). */
    fun i(message: String) {
        Log.i(TAG, message)
        if (settings?.developerLogging != true) return
        writeToFile("INFO", message)
    }

    /** Log a warning (always written when dev logging is on). */
    fun w(message: String) {
        Log.w(TAG, message)
        if (settings?.developerLogging != true) return
        writeToFile("WARN", message)
    }

    /** Log an error (always written when dev logging is on). */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        if (settings?.developerLogging != true) return
        val full = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        writeToFile("ERROR", full)
    }

    /** Get all log files for export/viewing. */
    fun getLogFiles(): List<File> {
        val ctx = appContext ?: return emptyList()
        val dir = File(ctx.filesDir, LOG_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.toList()?.sortedByDescending { it.name } ?: emptyList()
    }

    /** Read full content of all log files. */
    fun readAllLogs(): String {
        val exportTs = timestampFormat.format(Date())
        val files = getLogFiles()
        if (files.isEmpty()) {
            return "=== Exported at $exportTs ===\n\nNo developer logs available."
        }
        val separator = "\n\n--- ${"-".repeat(40)} ---\n\n"
        return "=== Exported at $exportTs ===\n\n" + files.joinToString(separator) { file ->
            "=== ${file.name} ===\n${file.readText()}"
        }
    }

    private fun writeToFile(level: String, message: String) {
        val ctx = appContext ?: return
        executor.execute {
            try {
                val dir = File(ctx.filesDir, LOG_DIR)
                if (!dir.exists()) dir.mkdirs()
                val fileName = "dev_log_${dateFormat.format(Date())}.txt"
                val file = File(dir, fileName)
                val timestamp = timestampFormat.format(Date())
                file.appendText("[$timestamp] [$level] $message\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write dev log", e)
            }
        }
    }

    private fun cleanOldLogs() {
        val ctx = appContext ?: return
        executor.execute {
            try {
                val dir = File(ctx.filesDir, LOG_DIR)
                if (!dir.exists()) return@execute
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000L
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean old dev logs", e)
            }
        }
    }
}

