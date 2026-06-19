package com.espad32.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.Socket

class CameraStreamClient(
    private val host: String,
    private val port: Int,
    private var holder: SurfaceHolder
) {
    private var socket: Socket? = null
    private var streamJob: Job? = null
    var flipped = true  // default true since camera is mounted upside down

    // Current frame — exposed for photo/video capture
    @Volatile var currentBitmap: Bitmap? = null
    var onFrameAvailable: ((Bitmap) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun updateHolder(newHolder: SurfaceHolder) {
        holder = newHolder
    }

    @Volatile private var running = false

    fun isRunning() = running

    fun start(scope: CoroutineScope) {
        running = true
        streamJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    CarLogger.log("Camera", "Connecting to $host:$port")
                    socket = Socket(host, port).apply {
                        soTimeout = 30000  // 30s timeout — modem sleep can cause ~10s gaps
                        keepAlive = true
                    }
                    val stream = DataInputStream(socket!!.getInputStream())
                    socket!!.getOutputStream().write("CMD_VIDEO#1\n".toByteArray())
                    CarLogger.log("Camera", "Stream started")
                    var frameCount = 0

                    while (isActive) {
                        val b0 = stream.read()
                        val b1 = stream.read()
                        val b2 = stream.read()
                        val b3 = stream.read()
                        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) break

                        val length = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                        if (length == 0) {
                            // Keepalive ping from ESP32 while video paused — ignore
                            continue
                        }
                        if (length < 0 || length > 500_000) {
                            CarLogger.log("Camera", "Bad frame length: $length — resyncing")
                            // Try to resync by reading until we find a valid JPEG header (FF D8)
                            // rather than dropping the whole connection
                            var resynced = false
                            val buf = ByteArray(1)
                            var prev = 0
                            var attempts = 0
                            while (attempts++ < 8192) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                if (prev == 0xFF && buf[0] == 0xD8.toByte()) {
                                    resynced = true; break
                                }
                                prev = buf[0].toInt() and 0xFF
                            }
                            if (!resynced) break
                            continue
                        }

                        val jpegBytes = ByteArray(length)
                        var offset = 0
                        var stallCount = 0
                        while (offset < length) {
                            val read = stream.read(jpegBytes, offset, length - offset)
                            if (read < 0) break
                            if (read == 0) {
                                stallCount++
                                if (stallCount > 1000) {
                                    CarLogger.log("Camera", "Frame read stalled — aborting frame")
                                    break
                                }
                                continue
                            }
                            stallCount = 0
                            offset += read
                        }
                        if (offset < length) continue  // incomplete frame, skip decode

                        // Validate JPEG end-of-image marker (0xFFD9) — a frame that
                        // got cut off mid-DMA (FB-OVF) won't have a valid trailer,
                        // and decoding it anyway produces torn/banded images
                        if (length < 4 ||
                            jpegBytes[length - 2] != 0xFF.toByte() ||
                            jpegBytes[length - 1] != 0xD9.toByte()) {
                            continue  // skip this frame, keep showing the last good one
                        }

                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, length)
                            ?: continue

                        frameCount++
                        if (frameCount == 1 || frameCount % 30 == 0) {
                            CarLogger.log("Camera", "Frame #$frameCount decoded (${length} bytes)")
                        }

                        // Store current frame for capture
                        currentBitmap?.recycle()
                        currentBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                        onFrameAvailable?.invoke(currentBitmap!!)

                        // Draw to surface — stretch to fill entire view
                        val canvas = holder.lockCanvas()
                        if (canvas == null) {
                            CarLogger.log("Camera", "lockCanvas() returned null — surface not ready")
                            continue
                        }
                        try {
                            val sw = canvas.width.toFloat()
                            val sh = canvas.height.toFloat()
                            val bw = bitmap.width.toFloat()
                            val bh = bitmap.height.toFloat()

                            // Scale to fill (may crop slightly) rather than letterbox
                            val scale = maxOf(sw / bw, sh / bh)
                            val dx = (sw - bw * scale) / 2f
                            val dy = (sh - bh * scale) / 2f

                            val flipY = if (flipped) -scale else scale
                            val matrix = android.graphics.Matrix().apply {
                                setScale(scale, flipY)
                                postTranslate(dx, if (flipped) sh - dy else dy)
                            }
                            canvas.drawColor(android.graphics.Color.BLACK)
                            canvas.drawBitmap(bitmap, matrix, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    CarLogger.log("Camera", "Stream error: ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) { }
                    socket = null
                    CarLogger.log("Camera", "Stream disconnected — retrying in 2s")
                    onDisconnected?.invoke()
                }
                if (isActive) delay(2000)
            }
            running = false
        }
    }

    fun stop() {
        running = false
        streamJob?.cancel()
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        currentBitmap?.recycle()
        currentBitmap = null
    }
}
