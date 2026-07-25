package com.espad32.controller

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ToggleButton

class ControlPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface ButtonListener {
        fun onBuzzerChanged(on: Boolean)
        fun onLedCycle()
        fun onEmotionCycle()
        fun onEmotionOff()
        fun onLedOff()
        fun onServoReset()
        fun onCameraFlip()
        fun onTakePhoto()
        fun onToggleRecording()
        fun onViewLog()
        fun onSettings()
        fun onMatrixCanvas()
    }

    private var listener: ButtonListener? = null

    init {
        inflate(context, R.layout.view_control_panel, this)
        orientation = VERTICAL

        val btnBuzzer = findViewById<ToggleButton>(R.id.btnBuzzer)
        btnBuzzer.setOnCheckedChangeListener { _, isChecked -> listener?.onBuzzerChanged(isChecked) }

        findViewById<Button>(R.id.btnLedCycle).setOnClickListener     { listener?.onLedCycle() }
        findViewById<Button>(R.id.btnLedOff).setOnClickListener       { listener?.onLedOff() }
        findViewById<Button>(R.id.btnEmotionCycle).setOnClickListener  { listener?.onEmotionCycle() }
        findViewById<Button>(R.id.btnEmotionOff).setOnClickListener    { listener?.onEmotionOff() }
        findViewById<Button>(R.id.btnServoReset).setOnClickListener    { listener?.onServoReset() }
        findViewById<Button>(R.id.btnCameraFlip).setOnClickListener    { listener?.onCameraFlip() }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener         { listener?.onTakePhoto() }
        findViewById<Button>(R.id.btnRecord).setOnClickListener        { listener?.onToggleRecording() }
        findViewById<Button>(R.id.btnViewLog).setOnClickListener       { listener?.onViewLog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener      { listener?.onSettings() }
        findViewById<Button>(R.id.btnMatrixCanvas).setOnClickListener  { listener?.onMatrixCanvas() }
    }

    fun setButtonListener(l: ButtonListener) { listener = l }

    /** Container for user-configured Control buttons, populated by
     *  MainActivity — kept dumb here on purpose, same as the rest of
     *  this view. */
    fun getDynamicButtonsContainer(): LinearLayout = findViewById(R.id.dynamicButtonsContainer)
}
