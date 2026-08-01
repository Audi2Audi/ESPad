package com.espad32.controller

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

// Was also home to a ButtonListener interface (Photo/Record/View Log/
// Camera Flip/Settings callbacks) — removed along with row 1's buttons
// in view_control_panel.xml, not just left unused. Those were genuine
// duplication (Photo/Record/Settings already exist as compact icons in
// cameraControls) or have been relocated (Camera Flip -> cameraControls,
// View Log -> Settings' Theme tab). This view's only remaining job is
// hosting the live Controls buttons MainActivity populates at runtime.
class ControlPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        inflate(context, R.layout.view_control_panel, this)
        orientation = VERTICAL
    }

    /** Container for user-configured Control buttons, populated by
     *  MainActivity — kept dumb here on purpose, same as the rest of
     *  this view. */
    fun getDynamicButtonsContainer(): LinearLayout = findViewById(R.id.dynamicButtonsContainer)
}
