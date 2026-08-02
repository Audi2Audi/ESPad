package com.espad32.controller

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ControllerMappingActivity : AppCompatActivity() {

    private lateinit var tabButtons: TextView
    private lateinit var tabAxes: TextView
    private lateinit var tabAdvanced: TextView
    private lateinit var contentArea: LinearLayout

    // For detect-button dialog
    private var detectDialog: AlertDialog? = null
    private var detectCallback: ((Int, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller_mapping)

        tabButtons  = findViewById(R.id.tabButtons)
        tabAxes     = findViewById(R.id.tabAxes)
        tabAdvanced = findViewById(R.id.tabAdvanced)
        contentArea = findViewById(R.id.mappingContent)

        tabButtons.setOnClickListener  { showTab(0) }
        tabAxes.setOnClickListener     { showTab(1) }
        tabAdvanced.setOnClickListener { showTab(2) }

        findViewById<Button>(R.id.btnMappingClose).setOnClickListener { finish() }

        showTab(0)
    }

    private fun showTab(tab: Int) {
        val cyan = getColor(android.R.color.holo_blue_light)
        val grey = 0xFF888888.toInt()
        listOf(tabButtons, tabAxes, tabAdvanced)
            .forEachIndexed { i, tv -> tv.setTextColor(if (i == tab) cyan else grey) }
        contentArea.removeAllViews()
        when (tab) {
            0 -> buildButtonsTab()
            1 -> buildAxesTab()
            2 -> buildAdvancedTab()
        }
    }

    // ── Buttons tab ───────────────────────────────────────────────────
    private fun buildButtonsTab() {
        contentArea.removeAllViews()
        val hint = TextView(this).apply {
            text = "Tap a row to remap it. Use 'Detect' to press a physical button."
            textSize = 11f; setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
        }
        contentArea.addView(hint)

        ControllerMapping.buttons.forEachIndexed { index, mapping ->
            val row = layoutInflater.inflate(R.layout.item_button_row, contentArea, false)
            row.findViewById<TextView>(R.id.tvButtonName).text = mapping.label
            row.findViewById<TextView>(R.id.tvButtonFunction).text = displayLabelFor(mapping)
            row.setOnClickListener { showButtonEditDialog(index, mapping) }
            contentArea.addView(row)
        }
    }

    private fun displayLabelFor(mapping: ButtonMapping): String {
        if (mapping.function != ButtonFunction.CUSTOM_CONTROL) return mapping.function.label
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val buttons = com.espad32.controller.controls.ControlButtonStorage(this).loadButtons(profileKey)
        val target = buttons.find { it.id == mapping.customButtonId }
        return if (target != null) "→ ${target.label}" else "Custom Control Button (none set)"
    }

    private fun showButtonEditDialog(index: Int, mapping: ButtonMapping) {
        val view = layoutInflater.inflate(R.layout.dialog_button_mapping, null)
        val tvTarget  = view.findViewById<TextView>(R.id.tvMappingTarget)
        val spinner   = view.findViewById<Spinner>(R.id.spinnerFunction)
        val btnDetect = view.findViewById<Button>(R.id.btnDetect)

        tvTarget.text = "Remapping: ${mapping.label}"

        val functions = ButtonFunction.values()
        val adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            functions.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(functions.indexOf(mapping.function))

        btnDetect.setOnClickListener {
            showDetectDialog { detectedKeyCode, detectedLabel ->
                // Find if this keycode exists in our mapping list
                val existingIdx = ControllerMapping.buttons.indexOfFirst { it.keyCode == detectedKeyCode }
                if (existingIdx >= 0) {
                    tvTarget.text = "Remapping: ${ControllerMapping.buttons[existingIdx].label}"
                    // Show edit for detected button
                    showButtonEditDialog(existingIdx, ControllerMapping.buttons[existingIdx])
                } else {
                    tvTarget.text = "Detected: $detectedLabel (keyCode $detectedKeyCode)"
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Button Mapping")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val selected = functions[spinner.selectedItemPosition]
                if (selected == ButtonFunction.CUSTOM_CONTROL) {
                    showCustomControlPicker(index, mapping)
                } else {
                    ControllerMapping.updateButton(index, selected, this)
                    showTab(0)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Second step when "Custom Control Button" is chosen — pick WHICH
    // of the currently-defined Controls buttons (e.g. "LED") this
    // gamepad button should trigger.
    private fun showCustomControlPicker(index: Int, mapping: ButtonMapping) {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val buttons = com.espad32.controller.controls.ControlButtonStorage(this).loadButtons(profileKey)

        if (buttons.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "No Controls buttons exist yet for the active profile — add one in the Controls screen first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = buttons.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which button?")
            .setItems(labels) { _, itemIndex ->
                ControllerMapping.updateButton(index, ButtonFunction.CUSTOM_CONTROL, this, buttons[itemIndex].id)
                showTab(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetectDialog(callback: (Int, String) -> Unit) {
        detectCallback = callback
        val tv = TextView(this).apply {
            text = "Press any button on your controller now…"
            textSize = 14f; gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        detectDialog = AlertDialog.Builder(this)
            .setTitle("Detecting Button")
            .setView(tv)
            .setNegativeButton("Cancel") { _, _ -> detectCallback = null }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val cb = detectCallback
        if (cb != null && event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            detectCallback = null
            detectDialog?.dismiss()
            val name = KeyEvent.keyCodeToString(keyCode)
                .replace("KEYCODE_BUTTON_", "")
                .replace("KEYCODE_DPAD_", "D-Pad ")
                .replace("_", " ")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
            cb(keyCode, name)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Axes tab ──────────────────────────────────────────────────────
    private fun buildAxesTab() {
        contentArea.removeAllViews()
        val hint = TextView(this).apply {
            text = "Assign which stick controls drive or camera pan/tilt."
            textSize = 11f; setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
        }
        contentArea.addView(hint)

        ControllerMapping.axes.forEachIndexed { index, mapping ->
            val row = layoutInflater.inflate(R.layout.item_button_row, contentArea, false)
            row.findViewById<TextView>(R.id.tvButtonName).text = mapping.label
            row.findViewById<TextView>(R.id.tvButtonFunction).text = displayLabelForAxis(mapping)
            row.setOnClickListener { showAxisEditDialog(index, mapping) }
            contentArea.addView(row)
        }
    }

    private fun displayLabelForAxis(mapping: AxisMapping): String {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val buttons = com.espad32.controller.controls.ControlButtonStorage(this).loadButtons(profileKey)
        if (mapping.function == AxisFunction.CUSTOM_PWM) {
            val target = buttons.find { it.id == mapping.customButtonId }
            return if (target != null) "→ ${target.label}" else "Custom PWM Function (none set)"
        }
        if (mapping.function == AxisFunction.CUSTOM_BIDIRECTIONAL_DRIVE) {
            val fwd = buttons.find { it.id == mapping.customForwardButtonId }
            val rev = buttons.find { it.id == mapping.customReverseButtonId }
            val spd = buttons.find { it.id == mapping.customButtonId }
            return if (fwd != null && rev != null && spd != null)
                "→ ${fwd.label}/${rev.label} + ${spd.label}"
            else "Bidirectional Drive (not fully set)"
        }
        if (mapping.function == AxisFunction.CUSTOM_PAN_TILT) {
            val pan = buttons.find { it.id == mapping.customButtonId }
            val tilt = buttons.find { it.id == mapping.customButtonId2 }
            return if (pan != null && tilt != null)
                "→ ${pan.label} / ${tilt.label}"
            else "Pan/Tilt (not fully set)"
        }
        return mapping.function.label
    }

    private fun showAxisEditDialog(index: Int, mapping: AxisMapping) {
        val functions = AxisFunction.values()
        val adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            functions.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val spinner = Spinner(this)
        spinner.adapter = adapter
        spinner.setSelection(functions.indexOf(mapping.function))

        AlertDialog.Builder(this)
            .setTitle("Axis: ${mapping.label}")
            .setView(spinner)
            .setPositiveButton("Save") { _, _ ->
                val selected = functions[spinner.selectedItemPosition]
                if (selected == AxisFunction.CUSTOM_PWM) {
                    showCustomPwmPicker(index, mapping)
                } else if (selected == AxisFunction.CUSTOM_BIDIRECTIONAL_DRIVE) {
                    showBidirectionalDrivePicker(index, mapping)
                } else if (selected == AxisFunction.CUSTOM_PAN_TILT) {
                    showPanTiltPicker(index, mapping)
                } else {
                    ControllerMapping.updateAxis(index, selected, this)
                    showTab(1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Second step when "Custom PWM Function" is chosen — pick WHICH
    // slider (a SLIDER-type Controls button, e.g. "Motor speed") this
    // axis should drive.
    private fun showCustomPwmPicker(index: Int, mapping: AxisMapping) {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val buttons = com.espad32.controller.controls.ControlButtonStorage(this)
            .loadButtons(profileKey)
            .filter { it.controlType == com.espad32.controller.controls.ControlType.SLIDER }

        if (buttons.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "No PWM sliders exist yet for the active profile — add a PWM function's button in Controls first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = buttons.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which slider?")
            .setItems(labels) { _, itemIndex ->
                ControllerMapping.updateAxis(index, AxisFunction.CUSTOM_PWM, this, buttons[itemIndex].id)
                showTab(1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Three-step picker for Bidirectional Drive — needs a forward
    // direction toggle, a reverse direction toggle, and a speed slider,
    // chained one after another rather than one combined dialog, so
    // each step can filter to the right ControlType (TOGGLE for the
    // two direction roles, SLIDER for speed) with a clear label for
    // which one is currently being picked.
    private fun showBidirectionalDrivePicker(index: Int, mapping: AxisMapping) {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val allButtons = com.espad32.controller.controls.ControlButtonStorage(this).loadButtons(profileKey)
        val toggleButtons = allButtons.filter { it.controlType == com.espad32.controller.controls.ControlType.TOGGLE }
        val sliderButtons = allButtons.filter { it.controlType == com.espad32.controller.controls.ControlType.SLIDER }

        if (toggleButtons.size < 2) {
            android.widget.Toast.makeText(
                this,
                "Needs at least 2 Toggle-type Controls buttons (one per direction) — add them in Controls first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (sliderButtons.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "No PWM sliders exist yet for the active profile — add a PWM function's button in Controls first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val toggleLabels = toggleButtons.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Step 1 of 3 — Forward direction toggle")
            .setItems(toggleLabels) { _, fwdIndex ->
                val forwardBtn = toggleButtons[fwdIndex]
                AlertDialog.Builder(this)
                    .setTitle("Step 2 of 3 — Reverse direction toggle")
                    .setItems(toggleLabels) { _, revIndex ->
                        val reverseBtn = toggleButtons[revIndex]
                        if (reverseBtn.id == forwardBtn.id) {
                            android.widget.Toast.makeText(this, "Forward and reverse need to be different buttons.", android.widget.Toast.LENGTH_LONG).show()
                            return@setItems
                        }
                        val sliderLabels = sliderButtons.map { it.label }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Step 3 of 3 — Speed slider")
                            .setItems(sliderLabels) { _, spdIndex ->
                                val speedBtn = sliderButtons[spdIndex]
                                ControllerMapping.updateAxis(
                                    index, AxisFunction.CUSTOM_BIDIRECTIONAL_DRIVE, this,
                                    customButtonId = speedBtn.id,
                                    customForwardButtonId = forwardBtn.id,
                                    customReverseButtonId = reverseBtn.id
                                )
                                showTab(1)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Two-step picker for Pan/Tilt — needs a "pan" servo slider and a
    // "tilt" servo slider, chained the same way the other multi-step
    // pickers here already work. Both filtered to SLIDER-type Controls
    // buttons only (same as the other pickers' speed-slider step) —
    // this doesn't distinguish a SERVO-type slider from a PWM-type one
    // any more strictly than CUSTOM_PWM/Bidirectional Drive's own
    // pickers already do, so picking a mismatched button here carries
    // the same pre-existing risk as those, not a new one introduced
    // by this function specifically.
    private fun showPanTiltPicker(index: Int, mapping: AxisMapping) {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val allButtons = com.espad32.controller.controls.ControlButtonStorage(this).loadButtons(profileKey)
        val sliderButtons = allButtons.filter { it.controlType == com.espad32.controller.controls.ControlType.SLIDER }

        if (sliderButtons.size < 2) {
            android.widget.Toast.makeText(
                this,
                "Needs at least 2 slider-type Controls buttons (one per servo) — add them in Controls first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val sliderLabels = sliderButtons.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Step 1 of 2 — Pan servo (left/right)")
            .setItems(sliderLabels) { _, panIndex ->
                val panBtn = sliderButtons[panIndex]
                AlertDialog.Builder(this)
                    .setTitle("Step 2 of 2 — Tilt servo (up/down)")
                    .setItems(sliderLabels) { _, tiltIndex ->
                        val tiltBtn = sliderButtons[tiltIndex]
                        if (tiltBtn.id == panBtn.id) {
                            android.widget.Toast.makeText(this, "Pan and tilt need to be different servos.", android.widget.Toast.LENGTH_LONG).show()
                            return@setItems
                        }
                        ControllerMapping.updateAxis(
                            index, AxisFunction.CUSTOM_PAN_TILT, this,
                            customButtonId = panBtn.id,
                            customButtonId2 = tiltBtn.id
                        )
                        showTab(1)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Advanced tab ──────────────────────────────────────────────────
    private fun buildAdvancedTab() {
        val prefs = getSharedPreferences("ESPad32Prefs", MODE_PRIVATE)

        addSectionHeader("MOTOR SPEED CURVE")
        addHint("Linear: direct stick-to-speed mapping.\nExponential: slow near centre for precision, fast at extremes.")
        val curveGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        val rbLinear = android.widget.RadioButton(this).apply {
            text = "Linear"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            id = android.view.View.generateViewId()
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
        }
        val rbExpo = android.widget.RadioButton(this).apply {
            text = "Exponential"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            id = android.view.View.generateViewId()
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
        }
        curveGroup.addView(rbLinear); curveGroup.addView(rbExpo)
        if (prefs.getString("speedCurve", "linear") == "linear") rbLinear.isChecked = true
        else rbExpo.isChecked = true
        curveGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("speedCurve", if (id == rbLinear.id) "linear" else "exponential").apply()
        }
        contentArea.addView(curveGroup)
        addDivider()

        addSectionHeader("AUTO-STOP TIMEOUT")
        addHint("Stop motors if no drive command is received within this time. 0 = disabled.")
        val timeoutMs = prefs.getInt("autoStopMs", 500)
        val tvTimeoutVal = addLabelValue("Timeout", formatTimeout(timeoutMs))
        val sbTimeout = android.widget.SeekBar(this).apply {
            max = 10; progress = timeoutToSlider(timeoutMs)
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            thumbTintList    = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        sbTimeout.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, u: Boolean) {
                val ms = sliderToTimeout(v); tvTimeoutVal.text = formatTimeout(ms)
                prefs.edit().putInt("autoStopMs", ms).apply()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        contentArea.addView(sbTimeout)
        addDivider()

        addSectionHeader("CAMERA RESOLUTION")
        addHint("Lower = smoother stream. Higher = more detail. Takes effect on next connection.")
        val resolutions = listOf("QQVGA (160×120)", "QVGA (320×240)", "VGA (640×480)", "SVGA (800×600)")
        val resCodes    = listOf("QQVGA", "QVGA", "VGA", "SVGA")
        val currentRes  = prefs.getString("cameraRes", "QVGA") ?: "QVGA"
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, resolutions).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(resCodes.indexOf(currentRes).coerceAtLeast(0))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20.dp }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                    prefs.edit().putString("cameraRes", resCodes[pos]).apply()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
            }
        }
        contentArea.addView(spinner)
        addDivider()

        Button(this).apply {
            text = "↺  Reset Advanced Defaults"; textSize = 12f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp)
            setOnClickListener {
                prefs.edit().putString("speedCurve","linear").putInt("autoStopMs",500)
                    .putString("cameraRes","QVGA").apply()
                buildAdvancedTab()
            }
        }.also { contentArea.addView(it) }
    }

    // ── Shared helpers ────────────────────────────────────────────────
    private fun addSliderPref(prefs: android.content.SharedPreferences, key: String,
                               label: String, default: Int, min: Int, max: Int, scale: Float) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4.dp }
        }
        val currentVal = if (scale == 1.6f) ((prefs.getFloat(key, default * scale) / scale).toInt())
                         else ((prefs.getFloat(key, default * scale) / scale).toInt())
        val tvLabel = TextView(this).apply {
            text = label; textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvVal = TextView(this).apply {
            text = "$currentVal"; textSize = 12f; setTextColor(0xFF00E5FF.toInt())
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(tvLabel); row.addView(tvVal)
        contentArea.addView(row)
        val sb = android.widget.SeekBar(this).apply {
            this.max = max - min; progress = currentVal - min
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            thumbTintList    = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp }
        }
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, v: Int, u: Boolean) {
                val newVal = v + min; tvVal.text = "$newVal"
                prefs.edit().putFloat(key, newVal * scale).apply()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        contentArea.addView(sb)
    }

    private fun addSectionHeader(text: String) {
        contentArea.addView(TextView(this).apply {
            this.text = text; textSize = 10f; setTextColor(0xFF666666.toInt())
            letterSpacing = 0.1f; setPadding(0, 0, 0, 8)
        })
    }
    private fun addHint(text: String) {
        contentArea.addView(TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, 12)
        })
    }
    private fun addDivider() {
        contentArea.addView(android.view.View(this).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 8; bottomMargin = 16
            }
        })
    }
    private fun addLabelValue(label: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvVal = TextView(this).apply {
            text = value; textSize = 13f; setTextColor(0xFF00E5FF.toInt())
            gravity = android.view.Gravity.END
        }
        row.addView(tvVal); contentArea.addView(row); return tvVal
    }
    private val timeoutValues = listOf(0,200,500,1000,2000,3000,5000,10000,20000,30000,60000)
    private fun sliderToTimeout(v: Int) = timeoutValues.getOrElse(v) { 500 }
    private fun timeoutToSlider(ms: Int) = timeoutValues.indexOfFirst { it >= ms }.coerceAtLeast(0)
    private fun formatTimeout(ms: Int) = if (ms == 0) "Off" else if (ms < 1000) "${ms}ms" else "${ms/1000}s"
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
