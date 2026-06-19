package com.espad32.controller

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * App-wide logger.
 * - In-memory ring buffer (last 500 lines) for in-app log viewer
 * - Persistent log file in app's internal files dir (no permission needed)
 * - Share via LogViewerActivity which copies to cache dir for FileProvider
 */
object CarLogger {

    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var logFile: File? = null
    private var writer: PrintWriter? = null

    fun init(context: Context) {
        try {
            // Use internal files dir — no permission required on any Android version
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()

            // Keep only last 5 log files to avoid filling storage
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() }

            val timestamp = fileFmt.format(Date())
            logFile = File(dir, "car_log_$timestamp.txt")
            writer = PrintWriter(FileWriter(logFile!!, true), true)
            log("Logger", "Log started — ${logFile!!.name}")
        } catch (e: Exception) {
            android.util.Log.e("CarLogger", "Failed to open log file: ${e.message}")
        }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        android.util.Log.d(tag, message)
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        writer?.println(line)
    }

    @Synchronized
    fun getLines(): List<String> = lines.toList()

    fun getLogFile(): File? = logFile

    fun close() {
        writer?.flush()
        writer?.close()
    }
}
