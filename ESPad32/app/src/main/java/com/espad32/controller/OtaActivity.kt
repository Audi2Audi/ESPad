package com.espad32.controller

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OtaActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnPickFile: Button
    private lateinit var btnFlash: Button
    private lateinit var btnBack: Button

    private var selectedUri: Uri? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val PICK_BIN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ota)

        tvStatus     = findViewById(R.id.tvOtaStatus)
        tvInfo       = findViewById(R.id.tvOtaInfo)
        progressBar  = findViewById(R.id.otaProgressBar)
        tvProgress   = findViewById(R.id.tvOtaProgress)
        btnPickFile  = findViewById(R.id.btnPickBin)
        btnFlash     = findViewById(R.id.btnFlash)
        btnBack      = findViewById(R.id.btnOtaBack)

        progressBar.visibility = View.GONE
        tvProgress.visibility  = View.GONE
        btnFlash.isEnabled     = false

        btnBack.setOnClickListener { finish() }

        // Flash default bundled firmware
        val btnDefault = findViewById<android.widget.Button>(R.id.btnFlashDefault)
        btnDefault.setOnClickListener { flashFromAssets() }

        // Auto-flash default if launched from Settings OTA tab
        if (intent.getBooleanExtra("flashDefault", false)) {
            flashFromAssets()
        }

        btnPickFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                // Force local copy for cloud URIs (Google Drive etc.)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Select .bin file"), PICK_BIN)
        }

        btnFlash.setOnClickListener { startFlash() }

        // Query ESP32 OTA status
        checkEsp32Status()
    }

    private fun checkEsp32Status() {
        val ip = getSharedPreferences("ESPad32Prefs", MODE_PRIVATE)
            .getString("ip", "192.168.4.1") ?: "192.168.4.1"
        tvStatus.text = "Checking ESP32 at $ip…"
        scope.launch {
            try {
                val url = URL("http://$ip:8080/ota/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout    = 3000
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // Query firmware version via TCP command port
                val version = queryFirmwareVersion(ip)
                mainHandler.post {
                    tvStatus.text = if (version != null)
                        "✓ ESP32 ready  •  Firmware v$version"
                    else
                        "✓ ESP32 ready for OTA  •  Firmware unknown (pre-version sketch)"
                    tvStatus.setTextColor(0xFF69FF47.toInt())
                    tvInfo.text   = "Response: $response"
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = "✗ ESP32 not reachable — check IP and connection"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                    tvInfo.text   = e.message ?: "Unknown error"
                }
            }
        }
    }

    // Query version through the EXISTING TCP connection (MainTcpHolder),
    // since the ESP32 command server only accepts one client at a time.
    // Opening a second socket would hang waiting for accept().
    private suspend fun queryFirmwareVersion(ip: String): String? {
        if (MainTcpHolder.enqueue == null) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val prevHandler = MainTcpHolder.onNextData
            var resumed = false
            MainTcpHolder.onNextData = { data ->
                if (data.startsWith("CMD_VERSION") && !resumed) {
                    resumed = true
                    val ver = data.split("#").getOrNull(1)?.trim()
                    MainTcpHolder.onNextData = prevHandler
                    if (cont.isActive) cont.resume(ver) {}
                }
            }
            MainTcpHolder.enqueue?.invoke("CMD_VERSION#\n")
            // Timeout after 3 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!resumed) {
                    resumed = true
                    MainTcpHolder.onNextData = prevHandler
                    if (cont.isActive) cont.resume(null) {}
                }
            }, 3000)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_BIN && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            tvStatus.text = "Copying file…"
            tvStatus.setTextColor(0xFFFFCC00.toInt())
            scope.launch {
                try {
                    // Copy to local cache — handles Google Drive and other cloud URIs
                    val inStream = contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open file")
                    val cacheFile = java.io.File(cacheDir, "firmware_ota.bin")
                    cacheFile.outputStream().use { out -> inStream.copyTo(out) }
                    inStream.close()
                    selectedUri = android.net.Uri.fromFile(cacheFile)
                    val sizekb = cacheFile.length() / 1024
                    val name = uri.lastPathSegment?.substringAfterLast("/") ?: "firmware.bin"
                    mainHandler.post {
                        tvInfo.text   = "Selected: $name\nSize: ${sizekb}KB (cached locally)"
                        btnFlash.isEnabled = true
                        tvStatus.text = "Ready to flash"
                        tvStatus.setTextColor(0xFFFFCC00.toInt())
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvStatus.text = "✗ Could not read file: ${e.message}"
                        tvStatus.setTextColor(0xFFFF4444.toInt())
                    }
                }
            }
        }
    }

    private fun startFlash() {
        val uri = selectedUri ?: return
        val ip  = getSharedPreferences("ESPad32Prefs", MODE_PRIVATE)
            .getString("ip", "192.168.4.1") ?: "192.168.4.1"

        btnFlash.isEnabled    = false
        btnPickFile.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility  = View.VISIBLE
        progressBar.progress   = 0
        tvStatus.text = "Flashing…"
        tvStatus.setTextColor(0xFFFFCC00.toInt())

        scope.launch {
            try {
                // selectedUri is now a local cache file — read directly
                val cacheFile = java.io.File(cacheDir, "firmware_ota.bin")
                if (!cacheFile.exists()) throw Exception("Cached file not found — re-select")
                val bytes = cacheFile.readBytes()

                val totalSize = bytes.size
                mainHandler.post { tvProgress.text = "0 / ${totalSize/1024}KB" }

                val url = URL("http://$ip:8080/ota/upload")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod      = "POST"
                conn.doOutput           = true
                conn.connectTimeout     = 10000
                conn.readTimeout        = 60000
                conn.setChunkedStreamingMode(4096)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Firmware-Size", totalSize.toString())

                val out = DataOutputStream(conn.outputStream)
                val chunkSize = 4096
                var offset = 0
                while (offset < totalSize) {
                    val end  = minOf(offset + chunkSize, totalSize)
                    out.write(bytes, offset, end - offset)
                    offset = end
                    val pct = (offset * 100 / totalSize)
                    mainHandler.post {
                        progressBar.progress = pct
                        tvProgress.text = "${offset/1024}KB / ${totalSize/1024}KB  ($pct%)"
                    }
                }
                out.flush(); out.close()

                val responseCode = conn.responseCode
                val responseText = if (responseCode == 200)
                    conn.inputStream.bufferedReader().readText()
                else
                    conn.errorStream?.bufferedReader()?.readText() ?: "No response"
                conn.disconnect()

                mainHandler.post {
                    if (responseCode == 200) {
                        progressBar.progress = 100
                        tvStatus.text = "✓ Flash Complete!"
                        tvStatus.setTextColor(0xFF69FF47.toInt())
                        tvProgress.text = "Reconnect the app to resume control"
                    } else {
                        tvStatus.text = "✗ Flash failed (HTTP $responseCode)"
                        tvStatus.setTextColor(0xFFFF4444.toInt())
                        tvInfo.text = responseText
                        btnFlash.isEnabled    = true
                        btnPickFile.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    // Connection reset after full upload = ESP32 rebooted = success
                    if (progressBar.progress >= 95 &&
                        (e.message?.contains("reset") == true ||
                         e.message?.contains("closed") == true ||
                         e.message?.contains("EOF") == true)) {
                        progressBar.progress = 100
                        tvStatus.text = "✓ Flash Complete!"
                        tvStatus.setTextColor(0xFF69FF47.toInt())
                        tvProgress.text = "Reconnect the app to resume control"
                    } else {
                        tvStatus.text = "✗ Error: ${e.message}"
                        tvStatus.setTextColor(0xFFFF4444.toInt())
                        btnFlash.isEnabled    = true
                        btnPickFile.isEnabled = true
                    }
                }
            }
        }
    }

    private fun flashFromAssets() {
        val assetName = "06_3_Multi_Functional_Car.ino.bin"
        try {
            assets.open(assetName)  // test it exists
        } catch (e: Exception) {
            tvStatus.text = "✗ No bundled firmware found — place $assetName in app/src/main/assets/"
            tvStatus.setTextColor(0xFFFF4444.toInt())
            return
        }

        btnFlash.isEnabled = false
        findViewById<android.widget.Button>(R.id.btnFlashDefault).isEnabled = false
        btnPickFile.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        tvProgress.visibility  = android.view.View.VISIBLE
        progressBar.progress   = 0
        tvStatus.text = "Flashing default firmware…"
        tvStatus.setTextColor(0xFFFFCC00.toInt())
        tvInfo.text   = "Source: bundled $assetName"

        scope.launch {
            try {
                val bytes = assets.open(assetName).readBytes()
                val cacheFile = java.io.File(cacheDir, "firmware_ota.bin")
                cacheFile.writeBytes(bytes)
                selectedUri = android.net.Uri.fromFile(cacheFile)
                mainHandler.post { startFlash() }
            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = "✗ Error reading bundled firmware: ${e.message}"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                    btnFlash.isEnabled    = true
                    btnPickFile.isEnabled = true
                    findViewById<android.widget.Button>(R.id.btnFlashDefault).isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
