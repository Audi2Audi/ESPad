package com.espad32.controller.pinmapper

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.espad32.controller.R // adjust if your R class lives elsewhere

class PinMapperActivity : AppCompatActivity() {

    private lateinit var storage: PinConfigStorage
    private var currentProfile: DeviceProfile = Profiles.TRAIN
    private var assignments: MutableMap<String, Int?> = mutableMapOf()
    private var pendingRoleKey: String? = null
    private val logLines = mutableListOf<String>()

    private lateinit var profileTabContainer: LinearLayout
    private lateinit var boardContainer: LinearLayout
    private lateinit var roleContainer: LinearLayout
    private lateinit var roleSectionLabel: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_mapper)

        storage = PinConfigStorage(this)

        profileTabContainer = findViewById(R.id.profileTabContainer)
        boardContainer = findViewById(R.id.boardContainer)
        roleContainer = findViewById(R.id.roleContainer)
        roleSectionLabel = findViewById(R.id.roleSectionLabel)
        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.resetButton).setOnClickListener { resetDefaults() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { validateAndSave() }

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
        assignments = storage.load(profile.key, profile.defaults)
        pendingRoleKey = null
        log("Loaded profile \"${profile.displayName}\".")
        buildProfileTabs()
        renderBoard()
        renderRoles()
    }

    private fun resetDefaults() {
        assignments = currentProfile.defaults.mapValues { it.value as Int? }.toMutableMap()
        pendingRoleKey = null
        log("Reset to firmware defaults.")
        renderBoard()
        renderRoles()
    }

    private fun renderBoard() {
        boardContainer.removeAllViews()
        boardContainer.addView(buildHeaderColumn(BoardLayout.LEFT_HEADER))
        boardContainer.addView(buildHeaderColumn(BoardLayout.RIGHT_HEADER))
    }

    private fun buildHeaderColumn(pins: List<BoardPin>): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pins.forEach { pin -> col.addView(buildPinRow(pin)) }
        return col
    }

    private fun buildPinRow(pin: BoardPin): View {
        val assignedRole = pin.gpio?.let { gpio -> assignments.entries.find { it.value == gpio }?.key }
        val isClickable = pendingRoleKey != null && pin.status != PinStatus.INPUT_ONLY && pin.gpio != null

        val dotColor = when {
            pin.gpio == null -> "#333A41"
            assignedRole != null -> "#E3A458"
            pin.status == PinStatus.AVAILABLE -> "#2A3D31"
            pin.status == PinStatus.STRAPPING -> "#3A2F22"
            pin.status == PinStatus.INPUT_ONLY -> "#3A2528"
            pin.status == PinStatus.UART -> "#262B30"
            else -> "#333A41"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 4, 6, 4)
            setBackgroundColor(
                if (assignedRole != null) Color.parseColor("#231C14") else Color.TRANSPARENT
            )
            isClickable = isClickable
            if (isClickable) {
                setOnClickListener { onPinTapped(pin) }
            }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(20, 20).apply { marginEnd = 10 }
            setBackgroundColor(Color.parseColor(dotColor))
        }

        val labelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val silk = TextView(this).apply {
            text = pin.silkLabel
            textSize = 11f
            setTextColor(if (pin.gpio == null) Color.parseColor("#5A6169") else Color.parseColor("#E7EBEE"))
        }
        labelCol.addView(silk)
        if (assignedRole != null) {
            val roleLabel = TextView(this).apply {
                text = currentProfile.roles.find { it.key == assignedRole }?.label ?: assignedRole
                textSize = 9f
                setTextColor(Color.parseColor("#E3A458"))
            }
            labelCol.addView(roleLabel)
        }

        row.addView(dot)
        row.addView(labelCol)
        return row
    }

    private fun onPinTapped(pin: BoardPin) {
        val roleKey = pendingRoleKey ?: return
        val gpio = pin.gpio ?: return
        val result = PinValidation.canAssign(pin, roleKey)

        if (!result.ok) {
            log("NACK — GPIO $gpio rejected: ${result.reason}")
            return
        }

        // Clear any other role currently holding this GPIO.
        assignments.entries.find { it.value == gpio && it.key != roleKey }?.let {
            assignments[it.key] = null
        }
        assignments[roleKey] = gpio

        val roleLabel = currentProfile.roles.find { it.key == roleKey }?.label ?: roleKey
        if (PinValidation.isRisky(pin)) {
            log("GPIO $gpio assigned to \"$roleLabel\" — ${pin.status?.displayLabel?.lowercase()}.")
        } else {
            log("GPIO $gpio assigned to \"$roleLabel\".")
        }

        pendingRoleKey = null
        renderBoard()
        renderRoles()
    }

    private fun renderRoles() {
        roleSectionLabel.text = pendingRoleKey?.let {
            "TAP A PIN FOR \"${currentProfile.roles.find { r -> r.key == it }?.label?.uppercase()}\""
        } ?: "FUNCTIONS"

        roleContainer.removeAllViews()
        currentProfile.roles.groupBy { it.group }.forEach { (group, roles) ->
            val groupLabel = TextView(this).apply {
                text = group.uppercase()
                textSize = 10.5f
                setTextColor(Color.parseColor("#E3A458"))
                setPadding(0, 12, 0, 6)
            }
            roleContainer.addView(groupLabel)

            roles.forEach { role ->
                roleContainer.addView(buildRoleRow(role))
            }
        }
    }

    private fun buildRoleRow(role: PinRoleDef): View {
        val gpio = assignments[role.key]
        val isPending = pendingRoleKey == role.key

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(
                Color.parseColor(if (isPending) "#231C14" else "#181D23")
            )
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 6
            layoutParams = params
            setOnClickListener {
                pendingRoleKey = if (pendingRoleKey == role.key) null else role.key
                renderBoard()
                renderRoles()
            }
        }

        val nameView = TextView(this).apply {
            text = role.label
            setTextColor(Color.parseColor("#E7EBEE"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(this).apply {
            text = if (gpio != null) "GPIO $gpio" else "unassigned"
            setTextColor(Color.parseColor(if (gpio != null) "#C7CCD1" else "#E0645C"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        row.addView(nameView)
        row.addView(valueView)
        return row
    }

    private fun validateAndSave() {
        val unassigned = currentProfile.roles.filter { assignments[it.key] == null }
        if (unassigned.isNotEmpty()) {
            log("NACK — ${unassigned.size} role(s) unassigned: ${unassigned.joinToString { it.label }}")
            return
        }

        val dupes = PinValidation.findDuplicates(assignments)
        if (dupes.isNotEmpty()) {
            log("NACK — GPIO(s) assigned to more than one role: ${dupes.joinToString()}")
            return
        }

        storage.save(currentProfile.key, assignments)
        val payload = storage.buildPayload(currentProfile, assignments)
        log("VALIDATION OK — saved locally.")
        log("Payload ready to send to device:")
        log(payload.toString())

        // TODO: actually transmit `payload` to the ESP32 here once the
        // transport (TCP/BLE) for this profile is decided, then wait
        // for the firmware's own ACK/NACK before declaring success.
    }

    private fun log(message: String) {
        logLines.add(0, message)
        if (logLines.size > 8) logLines.removeAt(logLines.size - 1)
        logText.text = logLines.joinToString("\n")
    }
}
