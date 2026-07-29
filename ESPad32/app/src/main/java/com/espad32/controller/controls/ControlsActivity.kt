package com.espad32.controller.controls

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.espad32.controller.R
import com.espad32.controller.pinmapper.PinConfigStorage
import com.espad32.controller.pinmapper.Profiles
import com.espad32.controller.pinmapper.RoleType
import com.espad32.controller.pinmapper.DeviceProfile
import com.espad32.controller.pinmapper.CustomRoleStorage
import com.espad32.controller.pinmapper.RoleResolver
import com.espad32.controller.pinmapper.ProfileResolver

class ControlsActivity : AppCompatActivity() {

    private lateinit var buttonStorage: ControlButtonStorage
    private lateinit var pinStorage: PinConfigStorage
    private lateinit var customRoleStorage: CustomRoleStorage
    private var currentProfile: DeviceProfile = Profiles.TRAIN
    private var buttons: MutableList<ControlButtonDef> = mutableListOf()
    private val logLines = mutableListOf<String>()

    private lateinit var profileTabContainer: LinearLayout
    private lateinit var buttonListContainer: LinearLayout
    private lateinit var emptyStateText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controls)

        buttonStorage = ControlButtonStorage(this)
        pinStorage = PinConfigStorage(this)
        customRoleStorage = CustomRoleStorage(this)

        profileTabContainer = findViewById(R.id.controlsProfileTabContainer)
        buttonListContainer = findViewById(R.id.buttonListContainer)
        emptyStateText = findViewById(R.id.emptyStateText)
        logText = findViewById(R.id.controlsLogText)

        findViewById<Button>(R.id.addButtonBtn).setOnClickListener { showAddButtonDialog() }

        buildProfileTabs()
        val available = ProfileResolver.allProfiles(this)
        val activeKey = ActiveProfile.get(this, Profiles.TRAIN.key)
        val initial = available.find { it.key == activeKey } ?: available.firstOrNull()
        if (initial != null) {
            loadProfile(initial)
        } else {
            log("No devices exist yet — create one in Pin Mapper first.")
        }
    }

    private fun buildProfileTabs() {
        profileTabContainer.removeAllViews()
        ProfileResolver.allProfiles(this).forEach { profile ->
            val tab = Button(this).apply {
                text = profile.displayName
                textSize = 12.5f
                isAllCaps = false
                setBackgroundColor(
                    if (profile.key == currentProfile.key) Color.parseColor("#262D35")
                    else Color.TRANSPARENT
                )
                setTextColor(
                    if (profile.key == currentProfile.key) Color.parseColor("#E3A458")
                    else Color.parseColor("#8A939C")
                )
                setOnClickListener { loadProfile(profile) }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            profileTabContainer.addView(tab, params)
        }
    }

    private fun loadProfile(profile: DeviceProfile) {
        currentProfile = profile
        ActiveProfile.set(this, profile.key)
        buttons = buttonStorage.loadButtons(profile.key)
        log("Loaded buttons for \"${profile.displayName}\".")
        buildProfileTabs()
        renderButtons()
    }

    /** Roles eligible to back a control — digital outputs (toggle/momentary)
     *  and PWM outputs (slider), built-in AND custom. SERVO isn't offered —
     *  firmware has no angle-control command yet. */
    private fun eligibleRoles() = RoleResolver.effectiveRoles(currentProfile, customRoleStorage)
        .filter { it.type == RoleType.DIGITAL_OUTPUT || it.type == RoleType.PWM_OUTPUT }

    private fun renderButtons() {
        buttonListContainer.removeAllViews()
        emptyStateText.visibility = if (buttons.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        buttons.forEach { btn ->
            buttonListContainer.addView(buildButtonRow(btn))
        }
    }

    // Simple ▲▼ reorder — not full drag-and-drop, since the button list
    // is a plain rendered LinearLayout, not a RecyclerView. At the scale
    // this app operates at (a handful of buttons per profile), this
    // gets the actual value (reordering) without the bigger lift of an
    // ItemTouchHelper/RecyclerView migration.
    private fun addReorderColumn(row: LinearLayout, btn: ControlButtonDef) {
        val index = buttons.indexOfFirst { it.id == btn.id }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 0, 0, 0)
        }
        val canMoveUp = index > 0
        val canMoveDown = index in 0 until (buttons.size - 1)
        col.addView(TextView(this).apply {
            text = "▲"; textSize = 11f
            setTextColor(Color.parseColor(if (canMoveUp) "#8A939C" else "#333940"))
            setPadding(12, 2, 12, 2)
            if (canMoveUp) setOnClickListener { moveButton(index, index - 1) }
        })
        col.addView(TextView(this).apply {
            text = "▼"; textSize = 11f
            setTextColor(Color.parseColor(if (canMoveDown) "#8A939C" else "#333940"))
            setPadding(12, 2, 12, 2)
            if (canMoveDown) setOnClickListener { moveButton(index, index + 1) }
        })
        row.addView(col)
    }

    private fun moveButton(from: Int, to: Int) {
        val item = buttons.removeAt(from)
        buttons.add(to, item)
        buttonStorage.saveButtons(currentProfile.key, buttons)
        renderButtons()
    }

    private fun buildButtonRow(btn: ControlButtonDef): android.view.View {
        val role = RoleResolver.effectiveRoles(currentProfile, customRoleStorage).find { it.key == btn.roleKey }
        val boardKey = pinStorage.loadSelectedBoard(currentProfile.key, currentProfile.boardKey)
        val gpio = pinStorage.load(currentProfile.key, boardKey, currentProfile.defaults)[btn.roleKey]

        if (btn.controlType == ControlType.SLIDER) {
            return buildSliderRow(btn, role, gpio)
        }

        val isOn = buttonStorage.getState(currentProfile.key, btn.id)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.parseColor(if (isOn) "#231C14" else "#181D23"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 6
            layoutParams = params
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = btn.label
            setTextColor(Color.parseColor("#E7EBEE"))
            textSize = 14f
        })
        infoCol.addView(TextView(this).apply {
            text = "${role?.label ?: btn.roleKey} · GPIO ${gpio ?: "?"} · ${btn.controlType.name.lowercase()}"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 10.5f
        })

        val toggleBtn = Button(this).apply {
            text = if (btn.controlType == ControlType.MOMENTARY) "HOLD" else (if (isOn) "ON" else "OFF")
            textSize = 12f
            setBackgroundColor(Color.parseColor(if (isOn) "#E3A458" else "#262D35"))
            setTextColor(Color.parseColor(if (isOn) "#1A1408" else "#8A939C"))
            if (btn.controlType == ControlType.MOMENTARY) {
                // Real press/release, not a tap — a horn/buzzer should
                // stop the moment you lift your finger, not stay on
                // until tapped again.
                setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            handleMomentaryPress(btn, true)
                            v.performClick()
                            true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            handleMomentaryPress(btn, false)
                            true
                        }
                        else -> false
                    }
                }
            } else {
                setOnClickListener { handleTap(btn) }
            }
        }

        row.addView(infoCol)
        row.addView(toggleBtn)
        addReorderColumn(row, btn)
        row.setOnLongClickListener { showEditButtonDialog(btn); true }
        return row
    }

    private fun buildSliderRow(btn: ControlButtonDef, role: com.espad32.controller.pinmapper.PinRoleDef?, gpio: Int?): android.view.View {
        val currentValue = buttonStorage.getValue(currentProfile.key, btn.id)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 12)
            setBackgroundColor(Color.parseColor("#181D23"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 6
            layoutParams = params
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = btn.label
            setTextColor(Color.parseColor("#E7EBEE"))
            textSize = 14f
        })
        infoCol.addView(TextView(this).apply {
            text = "${role?.label ?: btn.roleKey} · GPIO ${gpio ?: "?"} · pwm"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 10.5f
        })
        val valueLabel = TextView(this).apply {
            text = currentValue.toString()
            setTextColor(Color.parseColor("#E3A458"))
            textSize = 16f
            setPadding(16, 0, 0, 0)
        }
        headerRow.addView(infoCol)
        headerRow.addView(valueLabel)
        addReorderColumn(headerRow, btn)
        container.addView(headerRow)

        val seekBar = android.widget.SeekBar(this).apply {
            max = 255
            progress = currentValue
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                valueLabel.text = value.toString()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                // Only send once the user lifts their finger — sending on
                // every intermediate onProgressChanged tick would flood
                // the single-client TCP connection with dozens of
                // commands per second for a single drag gesture.
                val value = sb?.progress ?: 0
                buttonStorage.setValue(currentProfile.key, btn.id, value)
                log("\"${btn.label}\" -> $value — sending...")
                DeviceCommand.sendSetValue(btn.roleKey, value) { response ->
                    log(response ?: "\"${btn.label}\": no response (check connection)")
                }
            }
        })
        container.addView(seekBar)
        container.setOnLongClickListener { showEditButtonDialog(btn); true }
        return container
    }

    private fun handleMomentaryPress(btn: ControlButtonDef, pressed: Boolean) {
        log("\"${btn.label}\" ${if (pressed) "pressed" else "released"} — sending...")
        DeviceCommand.sendSet(btn.roleKey, pressed) { response ->
            log(response ?: "\"${btn.label}\": no response (check connection)")
        }
    }

    private fun handleTap(btn: ControlButtonDef) {
        when (btn.controlType) {
            ControlType.TOGGLE -> {
                val newState = !buttonStorage.getState(currentProfile.key, btn.id)
                buttonStorage.setState(currentProfile.key, btn.id, newState)
                log("\"${btn.label}\" → ${if (newState) "ON" else "OFF"} — sending...")
                DeviceCommand.sendSet(btn.roleKey, newState) { response ->
                    log(response ?: "\"${btn.label}\": no response (check connection)")
                    renderButtons()
                }
            }
            ControlType.MOMENTARY -> {
                // Never actually reached — MOMENTARY buttons use their
                // own OnTouchListener (real press/release) instead of
                // handleTap now. Kept for exhaustiveness.
            }
            ControlType.SLIDER -> {
                // Never actually reached — slider rows use their own
                // SeekBar listener (buildSliderRow), not handleTap.
            }
        }
        renderButtons()
    }

    private fun showAddButtonDialog() {
        val eligible = eligibleRoles().filter { role ->
            // Only offer roles that don't already have a button.
            buttons.none { it.roleKey == role.key }
        }

        if (eligible.isEmpty()) {
            log("No available roles left to add a control for.")
            return
        }

        val outerScroll = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        outerScroll.addView(container)

        val labelInput = EditText(this).apply {
            hint = "Button label (e.g. Headlight)"
        }
        container.addView(labelInput)

        container.addView(TextView(this).apply {
            text = "Role"
            setPadding(0, 24, 0, 4)
        })

        // Behavior section rebuilds depending on the selected role's type —
        // DIGITAL_OUTPUT offers Toggle/Momentary, PWM_OUTPUT is always a
        // slider (no choice to make), so there's nothing to tap.
        val behaviorLabel = TextView(this).apply {
            text = "Behavior"
            setPadding(0, 24, 0, 4)
        }
        val behaviorContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        var selectedRole = eligible.first()
        var selectedType = if (selectedRole.type == RoleType.PWM_OUTPUT) ControlType.SLIDER else ControlType.TOGGLE

        fun rebuildBehaviorSection() {
            behaviorContainer.removeAllViews()
            if (selectedRole.type == RoleType.PWM_OUTPUT) {
                selectedType = ControlType.SLIDER
                behaviorContainer.addView(TextView(this).apply {
                    text = "Slider (0-255) — the only option for a PWM function"
                    textSize = 11f
                    setTextColor(Color.parseColor("#5F6A73"))
                })
            } else {
                selectedType = ControlType.TOGGLE
                val toggleBtn = Button(this).apply { text = "Toggle"; isAllCaps = false }
                val momentaryBtn = Button(this).apply { text = "Momentary"; isAllCaps = false }
                toggleBtn.setOnClickListener { selectedType = ControlType.TOGGLE }
                momentaryBtn.setOnClickListener { selectedType = ControlType.MOMENTARY }
                behaviorContainer.addView(toggleBtn)
                behaviorContainer.addView(momentaryBtn)
            }
        }

        // Vertical, scrollable, single-select list — a horizontal row of
        // buttons silently ran out of screen width once there were more
        // than 3-4 roles, making some genuinely unreachable rather than
        // actually missing. This has no such limit.
        val roleListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val roleRowViews = mutableMapOf<String, LinearLayout>()

        fun refreshRoleSelectionHighlight() {
            roleRowViews.forEach { (key, rowView) ->
                val isSelected = key == selectedRole.key
                rowView.setBackgroundColor(
                    Color.parseColor(if (isSelected) "#262D35" else "#181D23")
                )
            }
        }

        eligible.forEach { role ->
            val typeTag = when (role.type) {
                RoleType.PWM_OUTPUT -> "PWM"
                else -> "On/Off"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 16, 20, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 4
                layoutParams = params
            }
            row.addView(TextView(this).apply {
                text = role.label
                setTextColor(Color.parseColor("#E7EBEE"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = typeTag
                setTextColor(Color.parseColor("#5F6A73"))
                textSize = 11f
            })
            row.setOnClickListener {
                selectedRole = role
                if (labelInput.text.isBlank() || eligible.any { it.label == labelInput.text.toString() }) {
                    labelInput.setText(role.label)
                }
                rebuildBehaviorSection()
                refreshRoleSelectionHighlight()
            }
            roleRowViews[role.key] = row
            roleListContainer.addView(row)
        }
        refreshRoleSelectionHighlight()

        container.addView(roleListContainer)
        container.addView(behaviorLabel)
        container.addView(behaviorContainer)
        rebuildBehaviorSection() // reflect the default-selected role immediately

        AlertDialog.Builder(this)
            .setTitle("Add Button")
            .setView(outerScroll)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString().ifBlank { selectedRole.label }
                val newButton = ControlButtonDef(
                    id = "btn_${System.currentTimeMillis()}",
                    label = label,
                    roleKey = selectedRole.key,
                    controlType = selectedType
                )
                buttons.add(newButton)
                buttonStorage.saveButtons(currentProfile.key, buttons)
                log("Added button \"$label\" for role \"${selectedRole.label}\".")
                renderButtons()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditButtonDialog(btn: ControlButtonDef) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val labelInput = EditText(this).apply { setText(btn.label) }
        container.addView(labelInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Button")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString().ifBlank { btn.label }
                val index = buttons.indexOfFirst { it.id == btn.id }
                if (index >= 0) {
                    buttons[index] = btn.copy(label = newLabel)
                    buttonStorage.saveButtons(currentProfile.key, buttons)
                    log("Renamed button to \"$newLabel\".")
                    renderButtons()
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                buttons.removeAll { it.id == btn.id }
                buttonStorage.saveButtons(currentProfile.key, buttons)
                log("Removed button \"${btn.label}\".")
                renderButtons()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun log(message: String) {
        logLines.add(0, message)
        if (logLines.size > 8) logLines.removeAt(logLines.size - 1)
        logText.text = logLines.joinToString("\n")
    }
}
