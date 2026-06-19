package com.espad32.controller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvLog      = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)

        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { tvLog.text = "" }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        refreshLog()
    }

    private fun refreshLog() {
        if (!autoRefresh) return
        val lines = CarLogger.getLines().joinToString("\n")
        tvLog.text = lines
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        handler.postDelayed({ refreshLog() }, 1000)
    }

    private fun shareLog() {
        try {
            // Write current log lines to a temp file in cache dir — no FileProvider needed
            val logText = CarLogger.getLines().joinToString("\n")
            if (logText.isEmpty()) {
                Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
                return
            }

            // Write to cache dir which is always accessible for sharing
            val cacheFile = File(cacheDir, "car_log_share.txt")
            cacheFile.writeText(logText)

            // Use FileProvider with cache dir path
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                cacheFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ESPad32 Car Log")
                putExtra(Intent.EXTRA_TEXT, "ESPad32 Controller log file attached.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Log"))
        } catch (e: Exception) {
            CarLogger.log("LogViewer", "Share failed: ${e.message}")
            // Fallback — share as plain text directly
            shareAsText()
        }
    }

    private fun shareAsText() {
        try {
            val logText = CarLogger.getLines().takeLast(200).joinToString("\n")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ESPad32 Car Log")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(intent, "Share Log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoRefresh = false
    }
}
