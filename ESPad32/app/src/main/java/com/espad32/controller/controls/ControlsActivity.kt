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
        loadProfile(Profiles.TRAIN)
    }

    private fun buildProfileTabs() {
        profileTabContainer.removeAllViews()
        Profiles.ALL.forEach { profile ->
            val tab = Button(this).apply {
                text = if (profile.key == "train") "Train" else "RC Car"
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

    /** Roles eligible to back a button — digital outputs only for now,
     *  built-in AND custom (a custom "LED" function shows up here too). */
    private fun eligibleRoles() = RoleResolver.effectiveRoles(currentProfile, customRoleStorage)
        .filter { it.type == RoleType.DIGITAL_OUTPUT }

    private fun renderButtons() {
        buttonListContainer.removeAllViews()
        emptyStateText.visibility = if (buttons.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        buttons.forEach { btn ->
            buttonListContainer.addView(buildButtonRow(btn))
        }
    }

    private fun buildButtonRow(btn: ControlButtonDef): android.view.View {
        val role = RoleResolver.effectiveRoles(currentProfile, customRoleStorage).find { it.key == btn.roleKey }
        val boardKey = pinStorage.loadSelectedBoard(currentProfile.key, currentProfile.boardKey)
        val gpio = pinStorage.load(currentProfile.key, boardKey, currentProfile.defaults)[btn.roleKey]
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
            text = if (isOn) "ON" else "OFF"
            textSize = 12f
            setBackgroundColor(Color.parseColor(if (isOn) "#E3A458" else "#262D35"))
            setTextColor(Color.parseColor(if (isOn) "#1A1408" else "#8A939C"))
            setOnClickListener { handleTap(btn) }
        }

        row.addView(infoCol)
        row.addView(toggleBtn)
        row.setOnLongClickListener { showEditButtonDialog(btn); true }
        return row
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
                // True press/release isn't wired yet (needs touch-down/up
                // handling, not just a tap) — see PIN_MAPPER_ROADMAP.md.
                // For now this just sends ON; nothing turns it back off.
                log("\"${btn.label}\" pressed — sending...")
                DeviceCommand.sendSet(btn.roleKey, true) { response ->
                    log(response ?: "\"${btn.label}\": no response (check connection)")
                }
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
            log("No available digital-output roles left to add a button for.")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }

        val labelInput = EditText(this).apply {
            hint = "Button label (e.g. Headlight)"
        }
        container.addView(labelInput)

        container.addView(TextView(this).apply {
            text = "Role"
            setPadding(0, 24, 0, 4)
        })
        var selectedRole = eligible.first()
        val roleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eligible.forEach { role ->
            val roleBtn = Button(this).apply {
                text = role.label
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    selectedRole = role
                    if (labelInput.text.isBlank()) labelInput.setText(role.label)
                }
            }
            roleRow.addView(roleBtn)
        }
        container.addView(roleRow)

        container.addView(TextView(this).apply {
            text = "Behavior"
            setPadding(0, 24, 0, 4)
        })
        var selectedType = ControlType.TOGGLE
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toggleBtn = Button(this).apply { text = "Toggle"; isAllCaps = false }
        val momentaryBtn = Button(this).apply { text = "Momentary"; isAllCaps = false }
        toggleBtn.setOnClickListener { selectedType = ControlType.TOGGLE }
        momentaryBtn.setOnClickListener { selectedType = ControlType.MOMENTARY }
        typeRow.addView(toggleBtn)
        typeRow.addView(momentaryBtn)
        container.addView(typeRow)

        AlertDialog.Builder(this)
            .setTitle("Add Button")
            .setView(container)
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
