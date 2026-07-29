package com.espad32.controller.pinmapper

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.espad32.controller.MainActivity
import com.espad32.controller.R
import com.espad32.controller.controls.ActiveProfile
import com.espad32.controller.controls.ControlButtonDef
import com.espad32.controller.controls.ControlButtonStorage
import com.espad32.controller.controls.ControlType

/**
 * Guided setup: board → functions → pins → buttons as one continuous
 * flow, instead of requiring someone to already know to visit Pin
 * Mapper then Controls separately. Deliberately doesn't reimplement
 * anything — every step calls the exact same storage classes
 * (CustomProfileStorage, CustomRoleStorage, PinConfigStorage,
 * ControlButtonStorage) and validation (PinValidation) the standalone
 * screens already use, just walked through in one sequence.
 *
 * A function's pin is assigned in the SAME step it's created in
 * (unlike Pin Mapper, where you add a function then separately assign
 * its pin afterward) — since this flow's whole point is fewer trips
 * back and forth, not just relocating the same two-step process here.
 */
class DeviceWizardActivity : AppCompatActivity() {

    private lateinit var contentArea: LinearLayout
    private lateinit var tvStep: TextView
    private lateinit var profileStorage: CustomProfileStorage
    private lateinit var roleStorage: CustomRoleStorage
    private lateinit var pinStorage: PinConfigStorage
    private lateinit var buttonStorage: ControlButtonStorage

    private var deviceName = ""
    private var selectedBoardKey = Boards.ALL.first().key
    private var createdProfileKey: String? = null
    private val addedRoles = mutableListOf<CustomRole>()
    private val pinAssignments = mutableMapOf<String, Int>()
    private var currentStep = 0
    private var buttonStepIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_wizard)

        contentArea = findViewById(R.id.wizardContent)
        tvStep = findViewById(R.id.tvWizardStep)
        profileStorage = CustomProfileStorage(this)
        roleStorage = CustomRoleStorage(this)
        pinStorage = PinConfigStorage(this)
        buttonStorage = ControlButtonStorage(this)

        findViewById<Button>(R.id.btnWizardClose).setOnClickListener { finish() }

        renderStep()
    }

    private fun renderStep() {
        contentArea.removeAllViews()
        when (currentStep) {
            0 -> renderNameAndBoardStep()
            1 -> renderAddFunctionsStep()
            2 -> renderAddButtonsStep()
            3 -> renderSummaryStep()
        }
    }

    // ── Step 1: Name + Board ───────────────────────────────────────────
    private fun renderNameAndBoardStep() {
        tvStep.text = "Step 1 of 4 — Name your device and pick its board"

        val nameInput = EditText(this).apply {
            hint = "Device name (e.g. Lamp, Robot Arm)"
            setText(deviceName)
        }
        contentArea.addView(nameInput)

        contentArea.addView(TextView(this).apply {
            text = "Board"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 11f
            setPadding(0, 24, 0, 4)
        })

        val boardRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Boards.ALL.forEach { board ->
            val btn = Button(this).apply {
                text = board.displayName
                textSize = 12f
                isAllCaps = false
                setBackgroundColor(if (board.key == selectedBoardKey) Color.parseColor("#262D35") else Color.TRANSPARENT)
                setTextColor(if (board.key == selectedBoardKey) Color.parseColor("#E3A458") else Color.parseColor("#8A939C"))
                setOnClickListener {
                    selectedBoardKey = board.key
                    renderStep() // refresh highlighting
                }
            }
            boardRow.addView(btn)
        }
        contentArea.addView(boardRow)

        val nextBtn = Button(this).apply {
            text = "Next: Add Functions →"
            textSize = 13f
            isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp).apply {
                topMargin = 24.dp
            }
        }
        nextBtn.setOnClickListener {
            deviceName = nameInput.text.toString().ifBlank { "New Device" }
            if (createdProfileKey == null) {
                val created = profileStorage.addProfile(deviceName, selectedBoardKey)
                createdProfileKey = created.key
            }
            currentStep = 1
            renderStep()
        }
        contentArea.addView(nextBtn)
    }

    // ── Step 2: Add functions, one at a time, pin assigned immediately ──
    private fun renderAddFunctionsStep() {
        tvStep.text = "Step 2 of 4 — Add functions (assign a pin to each as you go)"

        if (addedRoles.isNotEmpty()) {
            contentArea.addView(TextView(this).apply {
                text = "Added so far:"
                setTextColor(Color.parseColor("#5F6A73"))
                textSize = 11f
                setPadding(0, 0, 0, 8)
            })
            addedRoles.forEach { role ->
                contentArea.addView(TextView(this).apply {
                    text = "  • ${role.label} (${role.type.name.lowercase()}) → GPIO ${pinAssignments[role.key]}"
                    setTextColor(Color.parseColor("#8A939C"))
                    textSize = 12f
                })
            }
        }

        val labelInput = EditText(this).apply {
            hint = "Function name (e.g. Light, Motor speed)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16.dp
            }
        }
        contentArea.addView(labelInput)

        var selectedType = RoleType.DIGITAL_OUTPUT
        contentArea.addView(TextView(this).apply {
            text = "Type"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 11f
            setPadding(0, 16, 0, 4)
        })
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        contentArea.addView(typeRow)

        contentArea.addView(TextView(this).apply {
            text = "Pin"
            setTextColor(Color.parseColor("#5F6A73"))
            textSize = 11f
            setPadding(0, 16, 0, 4)
        })

        // Pin options rebuild whenever the type selection changes, same
        // ADC1-only filtering PinValidation already enforces elsewhere.
        var pinOptions: List<Pair<String, Int>> = emptyList()
        val pinSpinner = Spinner(this)
        contentArea.addView(pinSpinner)

        fun rebuildPinOptions(): List<Pair<String, Int>> {
            val board = Boards.byKey(selectedBoardKey)
            val takenPins = pinAssignments.values.toSet()
            return board.allPins().mapNotNull { pin ->
                val gpio = pin.gpio ?: return@mapNotNull null
                if (gpio in takenPins) return@mapNotNull null // already used by an earlier function this session
                if (!PinValidation.canAssign(pin, selectedType).ok) return@mapNotNull null
                val label = if (PinValidation.isRisky(pin)) "GPIO $gpio ⚠" else "GPIO $gpio"
                label to gpio
            }
        }
        fun refreshSpinner() {
            pinOptions = rebuildPinOptions()
            pinSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, pinOptions.map { it.first }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        val typeButtons = mutableMapOf<RoleType, Button>()
        fun highlightType() {
            typeButtons.forEach { (t, btn) ->
                btn.setBackgroundColor(if (t == selectedType) Color.parseColor("#262D35") else Color.TRANSPARENT)
                btn.setTextColor(if (t == selectedType) Color.parseColor("#E3A458") else Color.parseColor("#8A939C"))
            }
        }
        listOf(
            RoleType.DIGITAL_OUTPUT to "On/Off",
            RoleType.PWM_OUTPUT to "PWM",
            RoleType.ANALOG_INPUT to "Analog In"
        ).forEach { (type, label) ->
            val btn = Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false
                setOnClickListener {
                    selectedType = type
                    highlightType()
                    refreshSpinner()
                }
            }
            typeButtons[type] = btn
            typeRow.addView(btn)
        }
        highlightType()
        refreshSpinner()

        val addBtn = Button(this).apply {
            text = "+ Add This Function"
            textSize = 13f
            isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp).apply {
                topMargin = 16.dp
            }
        }
        addBtn.setOnClickListener {
            val label = labelInput.text.toString().trim()
            if (label.isBlank()) {
                android.widget.Toast.makeText(this, "Name this function first.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pinOptions.isEmpty()) {
                android.widget.Toast.makeText(this, "No valid pins left for this type on this board.", android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val existingKeys = addedRoles.map { it.key }.toSet()
            val key = roleStorage.slugify(label, existingKeys)
            val gpio = pinOptions[pinSpinner.selectedItemPosition].second
            addedRoles.add(CustomRole(key, label, "Custom", selectedType))
            pinAssignments[key] = gpio
            renderStep() // refresh with the new function listed, cleared inputs
        }
        contentArea.addView(addBtn)

        val doneBtn = Button(this).apply {
            text = if (addedRoles.isEmpty()) "Skip — no functions yet" else "Done Adding Functions →"
            textSize = 12f
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#8A939C"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40.dp).apply {
                topMargin = 8.dp
            }
        }
        doneBtn.setOnClickListener {
            // Persist everything added this step before moving on.
            val profileKey = createdProfileKey ?: return@setOnClickListener
            roleStorage.saveCustomRoles(profileKey, addedRoles)
            pinStorage.saveSelectedBoard(profileKey, selectedBoardKey)
            pinStorage.save(profileKey, selectedBoardKey, pinAssignments.mapValues { it.value as Int? })
            buttonStepIndex = 0
            currentStep = 2
            renderStep()
        }
        contentArea.addView(doneBtn)
    }

    // ── Step 3: Offer a Controls button for each function just added ──
    private fun renderAddButtonsStep() {
        if (addedRoles.isEmpty() || buttonStepIndex >= addedRoles.size) {
            currentStep = 3
            renderStep()
            return
        }

        val role = addedRoles[buttonStepIndex]
        tvStep.text = "Step 3 of 4 — Add a button? (${buttonStepIndex + 1} of ${addedRoles.size})"

        contentArea.addView(TextView(this).apply {
            text = "Add a control for \"${role.label}\"?"
            setTextColor(Color.parseColor("#E7EBEE"))
            textSize = 15f
            setPadding(0, 0, 0, 16)
        })

        if (role.type == RoleType.ANALOG_INPUT) {
            contentArea.addView(TextView(this).apply {
                text = "Analog Input is a reading, not something to control — no button makes sense here. It'll show live on the main screen automatically."
                setTextColor(Color.parseColor("#5F6A73"))
                textSize = 12f
                setPadding(0, 0, 0, 16)
            })
            val skipBtn = Button(this).apply {
                text = "Next →"; textSize = 13f; isAllCaps = false
                setBackgroundResource(R.drawable.btn_car_bg); setTextColor(Color.WHITE)
            }
            skipBtn.setOnClickListener { buttonStepIndex++; renderStep() }
            contentArea.addView(skipBtn)
            return
        }

        val controlType = if (role.type == RoleType.PWM_OUTPUT) ControlType.SLIDER else ControlType.TOGGLE
        var chosenType = controlType

        if (role.type == RoleType.DIGITAL_OUTPUT) {
            contentArea.addView(TextView(this).apply {
                text = "Behavior"
                setTextColor(Color.parseColor("#5F6A73")); textSize = 11f; setPadding(0, 0, 0, 4)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val toggleBtn = Button(this).apply { text = "Toggle"; textSize = 11f; isAllCaps = false }
            val momentaryBtn = Button(this).apply { text = "Momentary"; textSize = 11f; isAllCaps = false }
            toggleBtn.setOnClickListener { chosenType = ControlType.TOGGLE }
            momentaryBtn.setOnClickListener { chosenType = ControlType.MOMENTARY }
            row.addView(toggleBtn); row.addView(momentaryBtn)
            contentArea.addView(row)
        } else {
            contentArea.addView(TextView(this).apply {
                text = "This will be a slider (0-255) — the only sensible control for a PWM value."
                setTextColor(Color.parseColor("#5F6A73")); textSize = 12f; setPadding(0, 0, 0, 8)
            })
        }

        val addBtn = Button(this).apply {
            text = "+ Add Button"; textSize = 13f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp).apply { topMargin = 16.dp }
        }
        addBtn.setOnClickListener {
            val profileKey = createdProfileKey ?: return@setOnClickListener
            val existing = buttonStorage.loadButtons(profileKey)
            existing.add(
                ControlButtonDef(
                    id = "btn_${System.currentTimeMillis()}",
                    label = role.label,
                    roleKey = role.key,
                    controlType = chosenType
                )
            )
            buttonStorage.saveButtons(profileKey, existing)
            buttonStepIndex++
            renderStep()
        }
        contentArea.addView(addBtn)

        val skipBtn = Button(this).apply {
            text = "Skip this one →"; textSize = 12f; isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#8A939C"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40.dp).apply { topMargin = 8.dp }
        }
        skipBtn.setOnClickListener { buttonStepIndex++; renderStep() }
        contentArea.addView(skipBtn)
    }

    // ── Step 4: Summary ─────────────────────────────────────────────────
    private fun renderSummaryStep() {
        tvStep.text = "Step 4 of 4 — Done"

        contentArea.addView(TextView(this).apply {
            text = "\"$deviceName\" is ready."
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 17f
            setPadding(0, 0, 0, 16)
        })

        val profileKey = createdProfileKey
        if (profileKey != null) {
            contentArea.addView(TextView(this).apply {
                text = "${addedRoles.size} function(s), " +
                    "${buttonStorage.loadButtons(profileKey).size} button(s) on ${Boards.byKey(selectedBoardKey).displayName}."
                setTextColor(Color.parseColor("#8A939C"))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            })

            val useBtn = Button(this).apply {
                text = "Use \"$deviceName\" Now"
                textSize = 13f; isAllCaps = false
                setBackgroundResource(R.drawable.btn_car_bg); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp)
            }
            useBtn.setOnClickListener {
                ActiveProfile.set(this, profileKey)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            contentArea.addView(useBtn)
        }

        val doneBtn = Button(this).apply {
            text = "Close"
            textSize = 12f; isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#8A939C"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40.dp).apply { topMargin = 12.dp }
        }
        doneBtn.setOnClickListener { finish() }
        contentArea.addView(doneBtn)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
