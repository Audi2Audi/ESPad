package com.espad32.controller.pinmapper

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.espad32.controller.R // adjust if your R class lives elsewhere

class PinMapperActivity : AppCompatActivity() {

    private lateinit var storage: PinConfigStorage
    private lateinit var customRoleStorage: CustomRoleStorage
    private lateinit var customProfileStorage: CustomProfileStorage
    private var currentProfile: DeviceProfile = Profiles.TRAIN
    private var currentBoardKey: String = Profiles.TRAIN.boardKey
    private var assignments: MutableMap<String, Int?> = mutableMapOf()
    private var customRoles: MutableList<CustomRole> = mutableListOf()
    private var labelOverrides: MutableMap<String, String> = mutableMapOf()
    private var pendingRoleKey: String? = null
    private val logLines = mutableListOf<String>()

    private lateinit var profileTabContainer: LinearLayout
    private lateinit var boardTabContainer: LinearLayout
    private lateinit var boardContainer: LinearLayout
    private lateinit var roleContainer: LinearLayout
    private lateinit var roleSectionLabel: TextView
    private lateinit var boardSectionLabel: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_mapper)

        storage = PinConfigStorage(this)
        customRoleStorage = CustomRoleStorage(this)
        customProfileStorage = CustomProfileStorage(this)

        profileTabContainer = findViewById(R.id.profileTabContainer)
        boardTabContainer = findViewById(R.id.boardTabContainer)
        boardContainer = findViewById(R.id.boardContainer)
        roleContainer = findViewById(R.id.roleContainer)
        roleSectionLabel = findViewById(R.id.roleSectionLabel)
        boardSectionLabel = findViewById(R.id.boardSectionLabel)
        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.resetButton).setOnClickListener { resetDefaults() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { validateAndSave() }
        findViewById<Button>(R.id.addRoleButton).setOnClickListener { showAddRoleDialog() }

        buildProfileTabs()
        val available = ProfileResolver.allProfiles(this)
        val activeKey = com.espad32.controller.controls.ActiveProfile.get(this, Profiles.TRAIN.key)
        val initial = available.find { it.key == activeKey } ?: available.firstOrNull()
        if (initial != null) {
            loadProfile(initial)
        } else {
            log("No devices exist yet — tap \"+\" to create one.")
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
                setOnLongClickListener {
                    showManageDeviceDialog(profile)
                    true
                }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            profileTabContainer.addView(tab, params)
        }

        val addTab = Button(this).apply {
            text = "+"
            textSize = 14f
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#E3A458"))
            setOnClickListener { showAddDeviceDialog() }
        }
        profileTabContainer.addView(
            addTab,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    // Creates a brand-new device profile with NO built-in roles at all —
    // unlike Train/RC Car, someone building a simple lamp isn't forced
    // into a skeleton meant for a completely different kind of device.
    private fun showAddDeviceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val nameInput = EditText(this).apply { hint = "Device name (e.g. Lamp)" }
        container.addView(nameInput)

        container.addView(TextView(this).apply {
            text = "Board"
            setPadding(0, 24, 0, 4)
        })
        var selectedBoardKey = Boards.ALL.first().key
        val boardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Boards.ALL.forEach { board ->
            val boardBtn = Button(this).apply {
                text = board.displayName
                textSize = 11f
                isAllCaps = false
                setOnClickListener { selectedBoardKey = board.key }
            }
            boardRow.addView(boardBtn)
        }
        container.addView(boardRow)

        // Alternative to filling in the form above: paste a profile
        // someone else exported (or one you exported from another
        // install) instead of building it from scratch.
        container.addView(TextView(this).apply {
            text = "— or —"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 20, 0, 4)
        })
        val importInput = EditText(this).apply {
            hint = "Paste an exported device's JSON here"
            minLines = 3
            gravity = Gravity.TOP
        }
        container.addView(importInput)
        val importBtn = Button(this).apply {
            text = "Import from pasted text"
            textSize = 11f
            isAllCaps = false
        }
        container.addView(importBtn)

        val dialog = AlertDialog.Builder(this)
            .setTitle("New Device")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().ifBlank { "New Device" }
                val created = customProfileStorage.addProfile(name, selectedBoardKey)
                log("Created device \"$name\".")
                loadProfile(
                    DeviceProfile(created.key, created.displayName, created.boardKey, emptyList(), emptyMap())
                )
            }
            .setNegativeButton("Cancel", null)
            .create()

        importBtn.setOnClickListener {
            val text = importInput.text.toString().trim()
            if (text.isEmpty()) {
                log("Paste an exported profile's text first.")
                return@setOnClickListener
            }
            val result = ProfileExportImport.importFromJson(this, text)
            if (result.profile != null) {
                log("Imported \"${result.profile.displayName}\".")
                dialog.dismiss()
                loadProfile(result.profile)
            } else {
                log("Import failed: ${result.error}")
            }
        }

        dialog.show()
    }

    private fun showManageDeviceDialog(profile: DeviceProfile) {
        AlertDialog.Builder(this)
            .setTitle(profile.displayName)
            .setItems(arrayOf("Connection IP", "Export", "Delete")) { _, which ->
                when (which) {
                    0 -> showConnectionIpDialog(profile)
                    1 -> exportDevice(profile)
                    2 -> showDeleteDeviceDialog(profile)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Lets a device profile remember which IP to connect to — e.g.
    // Train might always be its own AP at 192.168.4.1, while a
    // different device lives on the home network at some other
    // address. Just an IP, not WiFi credentials — joining the right
    // network is an OS-level phone setting, not something this app
    // manages; once the phone's already on the right network, the IP
    // is the only thing worth remembering per device.
    private fun showConnectionIpDialog(profile: DeviceProfile) {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.4.1"
            setText(profile.connectionIp ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
            addView(TextView(this@PinMapperActivity).apply {
                text = "Which IP should \"${profile.displayName}\" connect to? " +
                    "Leave blank to not remember one for this device."
                textSize = 12f
                setTextColor(Color.parseColor("#8A939C"))
                setPadding(0, 0, 0, 16)
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Connection IP")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                customProfileStorage.setConnectionIp(profile.key, input.text.toString().trim())
                log("Connection IP for \"${profile.displayName}\" ${if (input.text.isBlank()) "cleared" else "set to ${input.text}"}.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Shares the profile as JSON via the system share sheet (any app —
    // Drive, email, Bluetooth, "Copy to clipboard" via Files, etc) so
    // it doesn't need Android's storage/file-picker permissions at all
    // for something this small.
    private fun exportDevice(profile: DeviceProfile) {
        val roles = effectiveRolesFor(profile)
        val json = ProfileExportImport.buildExportJson(this, profile, roles)
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "ESPad device: ${profile.displayName}")
            putExtra(android.content.Intent.EXTRA_TEXT, json)
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "Export \"${profile.displayName}\""))
        log("Exported \"${profile.displayName}\" (${roles.size} function(s)).")
    }

    // Same merge effectiveRoles() does for the CURRENTLY loaded profile,
    // but for an arbitrary one — export can be triggered for a profile
    // that isn't the one currently open in this screen.
    private fun effectiveRolesFor(profile: DeviceProfile): List<PinRoleDef> {
        return RoleResolver.effectiveRoles(profile, customRoleStorage)
    }

    private fun showDeleteDeviceDialog(profile: DeviceProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${profile.displayName}\"?")
            .setMessage("This removes the device profile itself. Its functions, pin assignments, and buttons stay stored under this profile's key and would reappear if a profile with the same name were created again — they aren't separately cleaned up yet.")
            .setPositiveButton("Delete") { _, _ ->
                customProfileStorage.deleteProfile(profile.key)
                log("Deleted device \"${profile.displayName}\".")
                val remaining = ProfileResolver.allProfiles(this)
                val fallback = remaining.firstOrNull { it.key != profile.key } ?: remaining.firstOrNull()
                if (fallback != null) {
                    loadProfile(fallback)
                } else {
                    buildProfileTabs()
                    log("No devices left — tap \"+\" to create one.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadProfile(profile: DeviceProfile) {
        currentProfile = profile
        com.espad32.controller.controls.ActiveProfile.set(this, profile.key)
        currentBoardKey = storage.loadSelectedBoard(profile.key, profile.boardKey)
        assignments = storage.load(profile.key, currentBoardKey, profile.defaults)
        customRoles = customRoleStorage.loadCustomRoles(profile.key)
        labelOverrides = customRoleStorage.loadLabelOverrides(profile.key)
        pendingRoleKey = null
        log("Loaded profile \"${profile.displayName}\".")
        buildProfileTabs()
        buildBoardTabs()
        renderBoard()
        renderRoles()
    }

    /**
     * Built-in roles (with any label override applied) plus user-added
     * custom roles, merged into one list — everything else in this
     * Activity should read roles through this instead of
     * currentProfile.roles directly, so custom/renamed functions show
     * up everywhere the built-in ones do.
     */
    private fun effectiveRoles(): List<PinRoleDef> {
        val builtIn = currentProfile.roles.map { r ->
            PinRoleDef(r.key, labelOverrides[r.key] ?: r.label, r.group, r.type)
        }
        val custom = customRoles.map { c -> PinRoleDef(c.key, c.label, c.group, c.type) }
        return builtIn + custom
    }

    private fun buildBoardTabs() {
        boardTabContainer.removeAllViews()
        Boards.ALL.forEach { board ->
            val tab = Button(this).apply {
                text = board.displayName
                textSize = 11f
                isAllCaps = false
                setBackgroundColor(
                    if (board.key == currentBoardKey) Color.parseColor("#262D35")
                    else Color.TRANSPARENT
                )
                setTextColor(
                    if (board.key == currentBoardKey) Color.parseColor("#E3A458")
                    else Color.parseColor("#8A939C")
                )
                setOnClickListener { switchBoard(board) }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            boardTabContainer.addView(tab, params)
        }
    }

    private fun switchBoard(board: BoardDef) {
        if (board.key == currentBoardKey) return

        currentBoardKey = board.key
        storage.saveSelectedBoard(currentProfile.key, board.key)

        // Pull in whatever was previously saved for this profile+board combo
        // (if any), then drop any assignment that no longer resolves to a
        // real, usable pin on the new board rather than silently keeping a
        // stale GPIO number that may mean something completely different.
        val loaded = storage.load(currentProfile.key, board.key, emptyMap())
        val dropped = mutableListOf<String>()

        effectiveRoles().forEach { role ->
            val existingGpio = assignments[role.key]
            val carriedGpio = loaded[role.key]
            when {
                carriedGpio != null -> assignments[role.key] = carriedGpio
                existingGpio != null && board.findByGpio(existingGpio)?.status.let {
                    it == PinStatus.AVAILABLE || it == PinStatus.STRAPPING || it == PinStatus.UART
                } -> {
                    // Same GPIO number happens to exist and be usable on the
                    // new board too — keep it as a convenience default.
                }
                else -> {
                    if (existingGpio != null) dropped.add(role.label)
                    assignments[role.key] = null
                }
            }
        }

        pendingRoleKey = null
        log("Switched to board \"${board.displayName}\".")
        if (dropped.isNotEmpty()) {
            log("Cleared ${dropped.size} assignment(s) not valid on this board: ${dropped.joinToString()}")
        }
        buildBoardTabs()
        renderBoard()
        renderRoles()
    }

    private fun resetDefaults() {
        currentBoardKey = currentProfile.boardKey
        storage.saveSelectedBoard(currentProfile.key, currentBoardKey)
        // "Reset defaults" only applies to built-in roles (that's what
        // Profiles.TRAIN/RC_CAR.defaults actually describes) — custom
        // roles have no firmware default, so their assignments are left
        // exactly as they were rather than getting wiped.
        val customAssignments = customRoles.associate { it.key to assignments[it.key] }
        assignments = currentProfile.defaults.mapValues { it.value as Int? }.toMutableMap()
        assignments.putAll(customAssignments)
        pendingRoleKey = null
        log("Reset built-in roles to firmware defaults (board: ${Boards.byKey(currentBoardKey).displayName}).")
        buildBoardTabs()
        renderBoard()
        renderRoles()
    }

    private fun renderBoard() {
        boardContainer.removeAllViews()
        val board = Boards.byKey(currentBoardKey)
        boardSectionLabel.text = "DEVICE — ${board.displayName.uppercase()}"
        boardContainer.addView(buildHeaderColumn(board.leftHeader))
        boardContainer.addView(buildHeaderColumn(board.rightHeader))
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
        val pinClickable = pendingRoleKey != null && pin.status != PinStatus.INPUT_ONLY && pin.gpio != null

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
            isClickable = pinClickable
            if (pinClickable) {
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
                text = effectiveRoles().find { it.key == assignedRole }?.label ?: assignedRole
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
        assignGpioToRole(roleKey, gpio)
    }

    /**
     * Single source of truth for "assign this GPIO to this role" —
     * used by both tapping a pin on the board diagram (pendingRoleKey
     * flow) and the per-row GPIO dropdown. Keeping this in one place
     * means both paths handle validation, bumping another role that
     * already holds the pin, and logging identically rather than two
     * slightly-different implementations drifting apart over time.
     *
     * newGpio == null means "unassign" (used by the dropdown's
     * "Unassigned" option — tapping the board diagram has no
     * equivalent for this, since there's no "empty" pin to tap).
     */
    private fun assignGpioToRole(roleKey: String, newGpio: Int?) {
        val roleLabel = effectiveRoles().find { it.key == roleKey }?.label ?: roleKey
        val roleType = effectiveRoles().find { it.key == roleKey }?.type ?: RoleType.DIGITAL_OUTPUT

        if (newGpio == null) {
            assignments[roleKey] = null
            log("\"$roleLabel\" unassigned.")
            pendingRoleKey = if (pendingRoleKey == roleKey) null else pendingRoleKey
            renderBoard()
            renderRoles()
            return
        }

        val board = Boards.byKey(currentBoardKey)
        val pin = board.findByGpio(newGpio)
        if (pin == null) {
            log("NACK — GPIO $newGpio isn't on this board.")
            return
        }

        val result = PinValidation.canAssign(pin, roleType)
        if (!result.ok) {
            log("NACK — GPIO $newGpio rejected: ${result.reason}")
            return
        }

        // Clear any other role currently holding this GPIO — and say so
        // clearly, since bumping a required built-in role will block
        // Validate & Save until it's reassigned somewhere else.
        assignments.entries.find { it.value == newGpio && it.key != roleKey }?.let { bumped ->
            assignments[bumped.key] = null
            val bumpedLabel = effectiveRoles().find { it.key == bumped.key }?.label ?: bumped.key
            val isCustom = customRoles.any { it.key == bumped.key }
            if (isCustom) {
                log("\"$bumpedLabel\" is now unassigned.")
            } else {
                log("\"$bumpedLabel\" is now unassigned — it's required, so you'll need to give it a new pin before you can Validate & Save.")
            }
        }
        assignments[roleKey] = newGpio

        if (PinValidation.isRisky(pin)) {
            log("GPIO $newGpio assigned to \"$roleLabel\" — ${pin.status?.displayLabel?.lowercase()}.")
        } else {
            log("GPIO $newGpio assigned to \"$roleLabel\".")
        }

        pendingRoleKey = null
        renderBoard()
        renderRoles()
    }

    private fun renderRoles() {
        roleSectionLabel.text = pendingRoleKey?.let {
            "TAP A PIN FOR \"${effectiveRoles().find { r -> r.key == it }?.label?.uppercase()}\""
        } ?: "FUNCTIONS"

        roleContainer.removeAllViews()
        effectiveRoles().groupBy { it.group }.forEach { (group, roles) ->
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

        // Custom roles get a visible delete icon — relying on a hidden
        // long-press alone made deletion undiscoverable. Built-in roles
        // stay long-press-to-rename only (they can't be deleted). Placed
        // on the left, ahead of the function name, so it doesn't get
        // confused with the pin-clearing button on the right.
        val isCustom = customRoles.any { it.key == role.key }
        if (isCustom) {
            val deleteBtn = TextView(this).apply {
                text = "🗑"
                textSize = 14f
                setPadding(0, 0, 24, 0)
                setOnClickListener {
                    // Don't let this bubble up to the row's own click
                    // listener (which toggles pin-assignment mode).
                    showEditRoleDialog(role)
                }
            }
            row.addView(deleteBtn)
        }

        val nameView = TextView(this).apply {
            text = role.label
            setTextColor(Color.parseColor("#E7EBEE"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameView)

        // Options: every GPIO on the current board that's actually valid
        // for this role's type — same filter (PinValidation.canAssign)
        // the board-diagram tap flow uses, so the dropdown can never
        // offer a pin tapping would have rejected. Risky-but-allowed
        // pins (strapping/UART) are marked the same way the board
        // diagram marks them. No "Unassigned" entry here — clearing a
        // pin is the dedicated ✕ button below, not a dropdown choice.
        val board = Boards.byKey(currentBoardKey)
        val options = mutableListOf<Pair<String, Int?>>().apply {
            if (gpio == null) {
                // Placeholder only shown while nothing is assigned yet —
                // not a selectable "clear" action, just what the
                // dropdown displays before a real pin is chosen.
                add("— Select GPIO —" to null)
            }
            board.allPins().forEach { pin ->
                val pinGpio = pin.gpio ?: return@forEach
                if (PinValidation.canAssign(pin, role.type).ok) {
                    val label = if (PinValidation.isRisky(pin)) "GPIO $pinGpio ⚠" else "GPIO $pinGpio"
                    add(label to pinGpio)
                }
            }
            // Guarantee the currently-assigned pin is always shown, even
            // in the unlikely case it wouldn't pass today's validation
            // (e.g. board was switched since it was assigned) — the
            // dropdown should always be able to reflect real state.
            if (gpio != null && none { it.second == gpio }) {
                add("GPIO $gpio" to gpio)
            }
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PinMapperActivity, android.R.layout.simple_spinner_item, options.map { it.first }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val initialIndex = options.indexOfFirst { it.second == gpio }.let { if (it >= 0) it else 0 }
        spinner.setSelection(initialIndex, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedGpio = options[position].second
                if (selectedGpio == gpio) return // initial fire / re-selecting current value — no-op
                assignGpioToRole(role.key, selectedGpio)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        row.addView(spinner)

        // Dedicated "clear this pin" button — replaces the delete icon's
        // old spot on the right. Only shown once something's actually
        // assigned (nothing to clear otherwise).
        if (gpio != null) {
            val clearBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#8A939C"))
                setPadding(24, 0, 0, 0)
                setOnClickListener {
                    assignGpioToRole(role.key, null)
                }
            }
            row.addView(clearBtn)
        }

        row.setOnLongClickListener {
            showEditRoleDialog(role)
            true
        }
        return row
    }

    private fun showAddRoleDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val labelInput = EditText(this).apply { hint = "Function label (e.g. LED)" }
        container.addView(labelInput)

        val groupLabel = TextView(this).apply {
            text = "Group (optional)"
            setPadding(0, 24, 0, 4)
        }
        container.addView(groupLabel)
        val groupInput = EditText(this).apply {
            hint = "e.g. Lights"
            setText("Custom")
        }
        container.addView(groupInput)

        val typeLabel = TextView(this).apply {
            text = "Type"
            setPadding(0, 24, 0, 4)
        }
        container.addView(typeLabel)

        var selectedType = RoleType.DIGITAL_OUTPUT
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val typeButtons = mutableMapOf<RoleType, Button>()
        fun highlightSelected() {
            typeButtons.forEach { (t, btn) ->
                if (t == selectedType) {
                    btn.setBackgroundColor(Color.parseColor("#262D35"))
                    btn.setTextColor(Color.parseColor("#E3A458"))
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(Color.parseColor("#8A939C"))
                }
            }
        }

        val digitalBtn = Button(this).apply {
            text = "On/Off"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { selectedType = RoleType.DIGITAL_OUTPUT; highlightSelected() }
        }
        val pwmBtn = Button(this).apply {
            text = "PWM (0-255)"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { selectedType = RoleType.PWM_OUTPUT; highlightSelected() }
        }
        val analogBtn = Button(this).apply {
            text = "Analog In"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { selectedType = RoleType.ANALOG_INPUT; highlightSelected() }
        }
        typeButtons[RoleType.DIGITAL_OUTPUT] = digitalBtn
        typeButtons[RoleType.PWM_OUTPUT] = pwmBtn
        typeButtons[RoleType.ANALOG_INPUT] = analogBtn
        typeRow.addView(digitalBtn)
        typeRow.addView(pwmBtn)
        typeRow.addView(analogBtn)
        container.addView(typeRow)
        highlightSelected()

        // SERVO isn't offered — firmware has no angle-control command
        // yet (SETV is PWM duty only), so creating a SERVO role here
        // would produce something Controls can't actually drive
        // correctly. See PIN_MAPPER_ROADMAP.md.
        val noteText = TextView(this).apply {
            text = "On/Off backs a toggle button in Controls. PWM backs a slider " +
                "(0-255). Analog In reads a live voltage — e.g. battery level via " +
                "your own voltage divider — and can only go on an ADC1 pin " +
                "(32/33/34/35/36/39, marked available in the diagram above); servo " +
                "angle control isn't supported by the firmware yet."
            textSize = 11f
            setTextColor(Color.parseColor("#5F6A73"))
            setPadding(0, 20, 0, 0)
        }
        container.addView(noteText)

        AlertDialog.Builder(this)
            .setTitle("Add Function")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString().ifBlank { "New Function" }
                val group = groupInput.text.toString().ifBlank { "Custom" }
                val existingKeys = effectiveRoles().map { it.key }.toSet()
                val key = customRoleStorage.slugify(label, existingKeys)

                customRoles.add(CustomRole(key, label, group, selectedType))
                customRoleStorage.saveCustomRoles(currentProfile.key, customRoles)
                log("Added function \"$label\".")
                renderRoles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditRoleDialog(role: PinRoleDef) {
        val isCustom = customRoles.any { it.key == role.key }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val labelInput = EditText(this).apply { setText(role.label) }
        container.addView(labelInput)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isCustom) "Edit Function" else "Rename Function")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString().ifBlank { role.label }
                if (isCustom) {
                    val idx = customRoles.indexOfFirst { it.key == role.key }
                    if (idx >= 0) {
                        customRoles[idx] = customRoles[idx].copy(label = newLabel)
                        customRoleStorage.saveCustomRoles(currentProfile.key, customRoles)
                    }
                } else {
                    labelOverrides[role.key] = newLabel
                    customRoleStorage.saveLabelOverrides(currentProfile.key, labelOverrides)
                }
                log("Renamed function to \"$newLabel\".")
                renderRoles()
                renderBoard()
            }
            .setNegativeButton("Cancel", null)

        // Built-in roles (motor_dir_a, etc) can be renamed but not
        // deleted — their key is what firmware/storage actually keys
        // on, and other profile logic assumes they exist.
        if (isCustom) {
            builder.setNeutralButton("Delete") { _, _ ->
                customRoles.removeAll { it.key == role.key }
                customRoleStorage.saveCustomRoles(currentProfile.key, customRoles)
                assignments.remove(role.key)
                pendingRoleKey = if (pendingRoleKey == role.key) null else pendingRoleKey
                log("Deleted function \"${role.label}\".")
                renderRoles()
                renderBoard()
            }
        }
        builder.show()
    }

    private fun validateAndSave() {
        val unassigned = effectiveRoles().filter { assignments[it.key] == null }
        if (unassigned.isNotEmpty()) {
            log("NACK — ${unassigned.size} role(s) unassigned: ${unassigned.joinToString { it.label }}")
            return
        }

        val dupes = PinValidation.findDuplicates(assignments)
        if (dupes.isNotEmpty()) {
            log("NACK — GPIO(s) assigned to more than one role: ${dupes.joinToString()}")
            return
        }

        storage.save(currentProfile.key, currentBoardKey, assignments)
        val payload = storage.buildPayload(currentProfile, assignments)
        log("Saved locally for board \"${Boards.byKey(currentBoardKey).displayName}\".")
        log("Sending to device...")

        com.espad32.controller.controls.DeviceCommand.sendRaw(payload.toString()) { response ->
            when {
                response == null -> log("No response from device (check connection).")
                response.startsWith("VALIDATION OK") -> log("Device confirmed: $response")
                response.startsWith("NACK") -> log("Device rejected config: $response")
                else -> log("Device response: $response")
            }
        }
    }

    private fun log(message: String) {
        logLines.add(0, message)
        if (logLines.size > 8) logLines.removeAt(logLines.size - 1)
        logText.text = logLines.joinToString("\n")
    }
}
