package com.espad32.controller

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout

class ControlPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface ButtonListener {
        fun onCameraFlip()
        fun onTakePhoto()
        fun onToggleRecording()
        fun onViewLog()
        fun onSettings()
    }

    private var listener: ButtonListener? = null

    init {
        inflate(context, R.layout.view_control_panel, this)
        orientation = VERTICAL

        findViewById<Button>(R.id.btnCameraFlip).setOnClickListener    { listener?.onCameraFlip() }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener         { listener?.onTakePhoto() }
        findViewById<Button>(R.id.btnRecord).setOnClickListener        { listener?.onToggleRecording() }
        findViewById<Button>(R.id.btnViewLog).setOnClickListener       { listener?.onViewLog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener      { listener?.onSettings() }
    }

    fun setButtonListener(l: ButtonListener) { listener = l }

    /** Container for user-configured Control buttons, populated by
     *  MainActivity — kept dumb here on purpose, same as the rest of
     *  this view. */
    fun getDynamicButtonsContainer(): LinearLayout = findViewById(R.id.dynamicButtonsContainer)
}
