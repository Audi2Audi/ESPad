package com.espad32.controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.*
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class MediaSaver(private val context: Context) {

    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // ── Photo ─────────────────────────────────────────────────────────
    fun savePhoto(bitmap: Bitmap): String? {
        return try {
            val filename = "CAR_${fmt.format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/ESPad32Controller")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            CarLogger.log("MediaSaver", "Photo saved: $filename")
            filename
        } catch (e: Exception) {
            CarLogger.log("MediaSaver", "Photo save failed: ${e.message}")
            null
        }
    }

    // ── Video — write JPEG frames to temp file, mux to MP4 on stop ───
    private var isRecording = false
    private var videoFile: File? = null
    private var frameFile: File? = null
    private var frameOut: FileOutputStream? = null
    private var frameCount = 0
    private var frameWidth = 320
    private var frameHeight = 240
    private val VIDEO_FPS = 10

    fun startRecording(width: Int, height: Int): Boolean {
        if (isRecording) return false
        return try {
            frameWidth = width
            frameHeight = height
            val timestamp = fmt.format(Date())
            val dir = File(context.cacheDir, "video_frames")
            dir.mkdirs()
            frameFile = File(dir, "frames_$timestamp.mjpeg")
            frameOut = FileOutputStream(frameFile!!)
            frameCount = 0

            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ESPad32Controller"
            )
            moviesDir.mkdirs()
            videoFile = File(moviesDir, "CAR_$timestamp.mp4")

            isRecording = true
            CarLogger.log("MediaSaver", "Recording started: ${videoFile!!.name}")
            true
        } catch (e: Exception) {
            CarLogger.log("MediaSaver", "Start recording failed: ${e.message}")
            false
        }
    }

    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording) return
        try {
            // Flip bitmap to match screen orientation
            val matrix = Matrix().apply { setScale(1f, -1f); postTranslate(0f, bitmap.height.toFloat()) }
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            // Write frame length then JPEG bytes
            val out = frameOut ?: return
            val jpegBytes = android.graphics.Bitmap.CompressFormat.JPEG.let {
                val baos = java.io.ByteArrayOutputStream()
                flipped.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                baos.toByteArray()
            }
            flipped.recycle()

            val len = jpegBytes.size
            out.write(byteArrayOf(
                (len shr 24).toByte(), (len shr 16).toByte(),
                (len shr 8).toByte(),  len.toByte()
            ))
            out.write(jpegBytes)
            frameCount++
        } catch (e: Exception) {
            CarLogger.log("MediaSaver", "Encode frame error: ${e.message}")
        }
    }

    fun stopRecording(): String? {
        if (!isRecording) return null
        isRecording = false
        return try {
            frameOut?.flush()
            frameOut?.close()
            frameOut = null

            // Mux JPEG frames into MP4 using MediaMuxer
            val result = muxFramesToMp4()
            frameFile?.delete()
            frameFile = null
            result
        } catch (e: Exception) {
            CarLogger.log("MediaSaver", "Stop recording failed: ${e.message}")
            null
        }
    }

    private fun muxFramesToMp4(): String? {
        val srcFile = frameFile ?: return null
        val dstFile = videoFile ?: return null
        if (!srcFile.exists() || frameCount == 0) return null

        return try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, frameWidth, frameHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val tempMp4 = File(context.cacheDir, "temp_video.mp4")
            val muxer = MediaMuxer(tempMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val fis = java.io.FileInputStream(srcFile)
            val lenBuf = ByteArray(4)
            var presentationUs = 0L
            val frameIntervalUs = 1_000_000L / VIDEO_FPS

            // Feed frames to encoder
            for (i in 0 until frameCount) {
                if (fis.read(lenBuf) != 4) break
                val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                          ((lenBuf[1].toInt() and 0xFF) shl 16) or
                          ((lenBuf[2].toInt() and 0xFF) shl 8) or
                           (lenBuf[3].toInt() and 0xFF)
                val jpegBytes = ByteArray(len)
                fis.read(jpegBytes)

                val bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, len) ?: continue
                val yuv = bitmapToNV21(bmp)
                bmp.recycle()

                val inputIdx = encoder.dequeueInputBuffer(10_000)
                if (inputIdx >= 0) {
                    val buf = encoder.getInputBuffer(inputIdx)!!
                    buf.clear(); buf.put(yuv)
                    encoder.queueInputBuffer(inputIdx, 0, yuv.size, presentationUs, 0)
                    presentationUs += frameIntervalUs
                }
                drainEncoder(encoder, muxer, { trackIndex }, { idx -> trackIndex = idx; muxerStarted = true })
            }

            // Signal end
            val inputIdx = encoder.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) encoder.queueInputBuffer(inputIdx, 0, 0, presentationUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(encoder, muxer, { trackIndex }, { idx -> trackIndex = idx; muxerStarted = true }, true)

            fis.close()
            encoder.stop(); encoder.release()
            if (muxerStarted) muxer.stop()
            muxer.release()

            // Copy temp file to MediaStore (Android 10+ compatible)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, dstFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/ESPad32Controller")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    tempMp4.inputStream().copyTo(out)
                }
            }
            tempMp4.delete()
            CarLogger.log("MediaSaver", "Video saved: ${dstFile.name} ($frameCount frames)")
            dstFile.name
        } catch (e: Exception) {
            CarLogger.log("MediaSaver", "Mux failed: ${e.message}")
            null
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        getTrack: () -> Int,
        setTrack: (Int) -> Unit,
        endOfStream: Boolean = false
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val track = muxer.addTrack(encoder.outputFormat)
                    setTrack(track)
                    muxer.start()
                }
                outIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && getTrack() >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(getTrack(), buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (endOfStream) return else break
            }
        }
    }

    private fun bitmapToNV21(bitmap: Bitmap): ByteArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val nv21 = ByteArray(w * h * 3 / 2)
        var yi = 0; var uvi = w * h
        for (j in 0 until h) for (i in 0 until w) {
            val p = pixels[j * w + i]
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            nv21[yi++] = (((66*r+129*g+25*b+128) shr 8)+16).coerceIn(0,255).toByte()
            if (j%2==0 && i%2==0) {
                nv21[uvi++] = ((( 112*r-94*g-18*b+128) shr 8)+128).coerceIn(0,255).toByte()
                nv21[uvi++] = (((-38*r-74*g+112*b+128) shr 8)+128).coerceIn(0,255).toByte()
            }
        }
        return nv21
    }

    fun isRecording() = isRecording
}
