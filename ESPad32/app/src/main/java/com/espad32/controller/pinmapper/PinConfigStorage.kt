package com.espad32.controller.pinmapper

import android.content.Context
import org.json.JSONObject

/**
 * Local (phone-side) storage of the current in-progress assignment,
 * separate from what's actually saved on the device's NVS. This is
 * just so the screen remembers your work if you background the app
 * before hitting "Send to device".
 *
 * The actual device sync (sending this as JSON to the ESP32 and
 * getting ACK/NACK back) isn't wired up yet — see sendToDevice()
 * below for where that goes once the transport is decided.
 */
class PinConfigStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_pin_config", Context.MODE_PRIVATE)

    private fun assignmentsKey(profileKey: String, boardKey: String) = "assign__${profileKey}__${boardKey}"
    private fun boardChoiceKey(profileKey: String) = "board_choice__$profileKey"

    fun save(profileKey: String, boardKey: String, assignments: Map<String, Int?>) {
        val json = JSONObject()
        assignments.forEach { (role, gpio) ->
            if (gpio != null) json.put(role, gpio)
        }
        prefs.edit().putString(assignmentsKey(profileKey, boardKey), json.toString()).apply()
    }

    fun load(profileKey: String, boardKey: String, defaults: Map<String, Int>): MutableMap<String, Int?> {
        val raw = prefs.getString(assignmentsKey(profileKey, boardKey), null)
        val result = mutableMapOf<String, Int?>()
        defaults.keys.forEach { result[it] = defaults[it] }

        if (raw != null) {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                result[key] = json.getInt(key)
            }
        }
        return result
    }

    /** Remembers which board was last selected for a given profile. */
    fun saveSelectedBoard(profileKey: String, boardKey: String) {
        prefs.edit().putString(boardChoiceKey(profileKey), boardKey).apply()
    }

    fun loadSelectedBoard(profileKey: String, fallback: String): String {
        return prefs.getString(boardChoiceKey(profileKey), fallback) ?: fallback
    }

    /**
     * Builds the JSON payload in the same shape the firmware's
     * ESPad_PinConfig_Test.ino expects, e.g.:
     * {"profile":"train","version":1,"pins":{"motor_dir_a":{"gpio":26,"role":"motor_dir_a"}, ...}}
     */
    fun buildPayload(profile: DeviceProfile, assignments: Map<String, Int?>): JSONObject {
        val pins = JSONObject()
        assignments.forEach { (role, gpio) ->
            if (gpio != null) {
                val entry = JSONObject()
                entry.put("gpio", gpio)
                entry.put("role", role)
                pins.put(role, entry)
            }
        }
        val payload = JSONObject()
        payload.put("profile", profile.key)
        payload.put("version", 1)
        payload.put("pins", pins)
        return payload
    }

    /**
     * TODO: wire this to whatever transport the RC car profile already
     * uses (TCP port 4000 per earlier ESPad32 notes, or BLE if the
     * train ends up on a different transport). For now this just
     * returns the payload so the UI can log/display it.
     */
    fun sendToDevice(payload: JSONObject): String {
        return payload.toString(2)
    }
}
