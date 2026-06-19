package com.espad32.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Virtual joystick view.
 * Reports normalized X/Y values in range -1.0 to 1.0 via onMoved callback.
 * onReleased fires when finger lifts.
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMoved: ((x: Float, y: Float) -> Unit)? = null
    var onReleased: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var thumbX  = 0f
    private var thumbY  = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var active = false

    private val paintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 229, 255)
        style = Paint.Style.FILL
    }
    private val paintBaseBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 229, 255)
        style = Paint.Style.FILL
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX     = w / 2f
        centerY     = h / 2f
        baseRadius  = min(w, h) / 2f * 0.85f
        thumbRadius = baseRadius * 0.35f
        thumbX = centerX
        thumbY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        // Base circle
        canvas.drawCircle(centerX, centerY, baseRadius, paintBase)
        canvas.drawCircle(centerX, centerY, baseRadius, paintBaseBorder)

        // Cross hairs
        canvas.drawLine(centerX - baseRadius * 0.6f, centerY, centerX + baseRadius * 0.6f, centerY, paintCross)
        canvas.drawLine(centerX, centerY - baseRadius * 0.6f, centerX, centerY + baseRadius * 0.6f, paintCross)

        // Thumb
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paintThumb)

        // Inner ring on thumb
        val ringPaint = Paint(paintBaseBorder).apply { alpha = 160 }
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.6f, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                active = true
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = sqrt(dx * dx + dy * dy)
                val clampedDist = min(dist, baseRadius)
                val angle = atan2(dy, dx)
                thumbX = centerX + clampedDist * Math.cos(angle.toDouble()).toFloat()
                thumbY = centerY + clampedDist * Math.sin(angle.toDouble()).toFloat()
                val nx = (thumbX - centerX) / baseRadius
                val ny = (thumbY - centerY) / baseRadius
                onMoved?.invoke(nx, ny)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                thumbX = centerX
                thumbY = centerY
                onReleased?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
