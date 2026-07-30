package com.espad32.controller.pinmapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-defined board — for hardware that isn't in the built-in
 * `Boards.ALL` list at all. Same spirit as a custom profile/role: the
 * user names it, adds the pins their specific board actually exposes
 * (with a status per pin — same statuses `PinStatus` already has),
 * and it becomes usable everywhere a built-in board is: New Device,
 * Guided Setup, and switching boards within an existing profile.
 */
data class CustomBoardPin(
    val label: String,
    val gpio: Int?, // null for a non-GPIO pin (3V3, GND, EN, etc), matching BoardPin
    val status: PinStatus
)

data class CustomBoard(
    val key: String,
    val displayName: String,
    val pins: List<CustomBoardPin>,
    val adc1Pins: Set<Int> = emptySet(),
    val supportsCamera: Boolean = false
) {
    /** Converts to the same BoardDef type built-in boards use, so every
     *  existing screen that consumes a BoardDef works unchanged. */
    fun toBoardDef(): BoardDef = BoardDef(
        key = key,
        displayName = "$displayName (custom)",
        leftHeader = pins.map { BoardPin(it.label, it.gpio, it.status) },
        rightHeader = emptyList(), // custom boards render as one column, not two — see the picker UI
        supportsCamera = supportsCamera,
        adc1Pins = adc1Pins
    )
}

class CustomBoardStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_custom_boards", Context.MODE_PRIVATE)
    private val LIST_KEY = "boards"

    fun loadBoards(): MutableList<CustomBoard> {
        val raw = prefs.getString(LIST_KEY, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val result = mutableListOf<CustomBoard>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pinsArr = o.getJSONArray("pins")
            val pins = mutableListOf<CustomBoardPin>()
            for (j in 0 until pinsArr.length()) {
                val p = pinsArr.getJSONObject(j)
                pins.add(
                    CustomBoardPin(
                        label = p.getString("label"),
                        gpio = if (p.has("gpio") && !p.isNull("gpio")) p.getInt("gpio") else null,
                        status = try { PinStatus.valueOf(p.optString("status", "AVAILABLE")) } catch (e: Exception) { PinStatus.AVAILABLE }
                    )
                )
            }
            val adc1Arr = o.optJSONArray("adc1Pins")
            val adc1 = mutableSetOf<Int>()
            if (adc1Arr != null) for (j in 0 until adc1Arr.length()) adc1.add(adc1Arr.getInt(j))

            result.add(
                CustomBoard(
                    key = o.getString("key"),
                    displayName = o.getString("displayName"),
                    pins = pins,
                    adc1Pins = adc1,
                    supportsCamera = o.optBoolean("supportsCamera", false)
                )
            )
        }
        return result
    }

    fun saveBoards(boards: List<CustomBoard>) {
        val arr = JSONArray()
        boards.forEach { b ->
            val o = JSONObject()
            o.put("key", b.key)
            o.put("displayName", b.displayName)
            val pinsArr = JSONArray()
            b.pins.forEach { p ->
                val po = JSONObject()
                po.put("label", p.label)
                if (p.gpio != null) po.put("gpio", p.gpio)
                po.put("status", p.status.name)
                pinsArr.put(po)
            }
            o.put("pins", pinsArr)
            val adc1Arr = JSONArray()
            b.adc1Pins.forEach { adc1Arr.put(it) }
            o.put("adc1Pins", adc1Arr)
            o.put("supportsCamera", b.supportsCamera)
            arr.put(o)
        }
        prefs.edit().putString(LIST_KEY, arr.toString()).apply()
    }

    fun slugify(name: String, existingKeys: Set<String>): String {
        var base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (base.isEmpty()) base = "board"
        var candidate = base
        var i = 1
        while (existingKeys.contains(candidate)) {
            i++
            candidate = "${base}_$i"
        }
        return candidate
    }

    fun addBoard(displayName: String, pins: List<CustomBoardPin>, adc1Pins: Set<Int>, supportsCamera: Boolean): CustomBoard {
        val existing = loadBoards()
        val existingKeys = (Boards.ALL.map { it.key } + existing.map { it.key }).toSet()
        val key = slugify(displayName, existingKeys)
        val board = CustomBoard(key, displayName, pins, adc1Pins, supportsCamera)
        existing.add(board)
        saveBoards(existing)
        return board
    }

    fun deleteBoard(key: String) {
        saveBoards(loadBoards().filterNot { it.key == key })
    }
}

/**
 * Standalone (not tied to one Activity) so both Pin Mapper's board
 * picker and the Guided Setup wizard's board step can show the exact
 * same creation flow without duplicating it — same reasoning as
 * keeping ProfileExportImport/PinValidation as plain objects rather
 * than Activity methods.
 */
fun showDefineCustomBoardDialog(context: android.content.Context, onCreated: (BoardDef) -> Unit) {
    val storage = CustomBoardStorage(context)
    val addedPins = mutableListOf<CustomBoardPin>()
    var adc1Pins = mutableSetOf<Int>()
    var supportsCamera = false

    val container = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(40, 24, 40, 0)
    }

    val nameInput = android.widget.EditText(context).apply {
        hint = "Board name (e.g. My Breakout Board)"
    }
    container.addView(nameInput)

    val pinListLabel = android.widget.TextView(context).apply {
        text = "Pins added so far: none"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#5F6A73"))
        setPadding(0, 16, 0, 8)
    }
    container.addView(pinListLabel)

    fun refreshPinList() {
        pinListLabel.text = if (addedPins.isEmpty()) {
            "Pins added so far: none"
        } else {
            "Pins added so far:\n" + addedPins.joinToString("\n") { p ->
                val gpioText = p.gpio?.let { "GPIO $it" } ?: "non-GPIO"
                "  • ${p.label} ($gpioText, ${p.status.name.lowercase()})"
            }
        }
    }

    val pinLabelInput = android.widget.EditText(context).apply {
        hint = "Pin label (e.g. 26, or TX0)"
    }
    val pinGpioInput = android.widget.EditText(context).apply {
        hint = "GPIO number (blank for GND/3V3/EN/etc)"
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    container.addView(pinLabelInput)
    container.addView(pinGpioInput)

    container.addView(android.widget.TextView(context).apply {
        text = "Status"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#5F6A73"))
        setPadding(0, 12, 0, 4)
    })
    val statusOptions = listOf(
        PinStatus.AVAILABLE to "Available",
        PinStatus.RESERVED to "Reserved (not assignable)",
        PinStatus.STRAPPING to "Strapping (usable, boot risk)",
        PinStatus.INPUT_ONLY to "Input-only",
        PinStatus.UART to "UART (loses Serial debug)"
    )
    var selectedStatus = PinStatus.AVAILABLE
    val statusSpinner = android.widget.Spinner(context).apply {
        adapter = android.widget.ArrayAdapter(
            context, android.R.layout.simple_spinner_item, statusOptions.map { it.second }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedStatus = statusOptions[position].first
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
    container.addView(statusSpinner)

    val adc1Check = android.widget.CheckBox(context).apply {
        text = "This pin is ADC1-capable (for Analog Input)"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#8A939C"))
    }
    container.addView(adc1Check)

    val addPinBtn = android.widget.Button(context).apply {
        text = "+ Add This Pin"
        textSize = 12f
        isAllCaps = false
        setBackgroundResource(com.espad32.controller.R.drawable.btn_car_bg)
        setTextColor(android.graphics.Color.WHITE)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (44 * context.resources.displayMetrics.density).toInt()
        ).apply { topMargin = 12 }
    }
    addPinBtn.setOnClickListener {
        val label = pinLabelInput.text.toString().trim()
        if (label.isBlank()) {
            android.widget.Toast.makeText(context, "Give this pin a label first.", android.widget.Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val gpioText = pinGpioInput.text.toString().trim()
        val gpio = if (gpioText.isBlank()) null else gpioText.toIntOrNull()
        if (gpioText.isNotBlank() && gpio == null) {
            android.widget.Toast.makeText(context, "GPIO must be a number, or leave it blank.", android.widget.Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        addedPins.add(CustomBoardPin(label, gpio, selectedStatus))
        if (adc1Check.isChecked && gpio != null) adc1Pins.add(gpio)
        pinLabelInput.setText("")
        pinGpioInput.setText("")
        adc1Check.isChecked = false
        refreshPinList()
    }
    container.addView(addPinBtn)

    val cameraCheck = android.widget.CheckBox(context).apply {
        text = "This board has a camera module"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#8A939C"))
        setPadding(0, 16, 0, 0)
        setOnCheckedChangeListener { _, checked -> supportsCamera = checked }
    }
    container.addView(cameraCheck)

    val scrollView = android.widget.ScrollView(context).apply { addView(container) }

    android.app.AlertDialog.Builder(context)
        .setTitle("Define Custom Board")
        .setView(scrollView)
        .setPositiveButton("Save Board") { _, _ ->
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                android.widget.Toast.makeText(context, "Name the board first.", android.widget.Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (addedPins.isEmpty()) {
                android.widget.Toast.makeText(context, "Add at least one pin first.", android.widget.Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val created = storage.addBoard(name, addedPins, adc1Pins, supportsCamera)
            onCreated(created.toBoardDef())
        }
        .setNegativeButton("Cancel", null)
        .show()
}
