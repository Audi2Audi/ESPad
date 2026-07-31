package com.espad32.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Virtual joystick view.
 * Reports normalized X/Y values in range -1.0 to 1.0 via onMoved callback.
 * onReleased fires when finger lifts.
 *
 * Also supports relocating the WHOLE widget: press and hold WITHOUT
 * moving for the standard long-press duration, and it switches into
 * "drag me somewhere else" mode instead of steering. Deliberately
 * layered on top of the existing knob-drag behavior rather than
 * replacing it — knob-dragging starts immediately on touch-down
 * exactly as before (driving can't tolerate any added lag waiting to
 * see if this is a long-press), and a SEPARATE delayed check watches
 * for "stayed roughly still for 500ms," which real steering almost
 * never does (you touch down expecting to immediately move), so this
 * doesn't meaningfully risk misfiring during normal use.
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMoved: ((x: Float, y: Float) -> Unit)? = null
    var onReleased: (() -> Unit)? = null
    // Fires once when a long-press-without-movement switches this
    // widget into relocate mode — the Activity uses this to give some
    // feedback (already gets haptic feedback for free below) and to
    // know a drag session has begun.
    var onRelocateModeEntered: (() -> Unit)? = null
    // Fires on every move while in relocate mode, with the raw screen-
    // pixel delta since the initial touch-down (not normalized
    // joystick values) — the Activity applies this as a translation
    // offset on the whole view.
    var onRelocateDragged: ((dxPx: Float, dyPx: Float) -> Unit)? = null
    var onRelocateFinished: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var thumbX  = 0f
    private var thumbY  = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var active = false

    private var relocateMode = false
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val stationaryThresholdPx = 12f * resources.displayMetrics.density
    private val longPressRunnable = Runnable {
        if (!relocateMode) {
            relocateMode = true
            active = false
            thumbX = centerX
            thumbY = centerY
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRelocateModeEntered?.invoke()
        }
    }

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

    private fun updateThumb(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        val clampedDist = min(dist, baseRadius)
        val angle = atan2(dy, dx)
        thumbX = centerX + clampedDist * Math.cos(angle.toDouble()).toFloat()
        thumbY = centerY + clampedDist * Math.sin(angle.toDouble()).toFloat()
        val nx = (thumbX - centerX) / baseRadius
        val ny = (thumbY - centerY) / baseRadius
        onMoved?.invoke(nx, ny)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                relocateMode = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                // Knob-dragging starts immediately, same as before —
                // no lag added for the common case.
                active = true
                updateThumb(event.x, event.y)
                downRawX = event.rawX
                downRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (relocateMode) {
                    // Raw (screen-absolute) coordinates, not event.x/y —
                    // local coordinates are relative to this view's OWN
                    // position, which is exactly what's changing during
                    // a relocate-drag. Using local coordinates here
                    // would create a feedback loop: moving the view
                    // shifts what "local" means for the next event,
                    // compounding instead of tracking the actual finger
                    // movement correctly. Raw coordinates are a stable
                    // reference frame regardless of how the view itself
                    // moves underneath the finger.
                    onRelocateDragged?.invoke(event.rawX - downRawX, event.rawY - downRawY)
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                if (sqrt(dx * dx + dy * dy) > stationaryThresholdPx) {
                    // Real movement — this is steering, not a hold.
                    // Cancel the long-press-to-relocate check entirely.
                    removeCallbacks(longPressRunnable)
                }
                updateThumb(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (relocateMode) {
                    relocateMode = false
                    onRelocateFinished?.invoke()
                } else {
                    active = false
                    thumbX = centerX
                    thumbY = centerY
                    onReleased?.invoke()
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
