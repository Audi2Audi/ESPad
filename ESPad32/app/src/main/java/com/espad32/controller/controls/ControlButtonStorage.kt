package com.espad32.controller.controls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the list of control buttons per profile, plus each button's
 * last-known local state (on/off). This is local-only for now — nothing
 * here talks to the ESP32. Toggling a button just flips stored state and
 * logs what command *would* be sent, the same way the Pin Mapper's
 * "Validate & Save" logs a payload without transmitting it.
 *
 * Wiring this to an actual live GPIO toggle on the device is the next
 * milestone, and depends on the same transport work already tracked as
 * a TODO in PinConfigStorage.sendToDevice() — see PIN_MAPPER_ROADMAP.md.
 */
class ControlButtonStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_control_buttons", Context.MODE_PRIVATE)

    private fun buttonsKey(profileKey: String) = "buttons__$profileKey"
    private fun stateKey(profileKey: String, buttonId: String) = "state__${profileKey}__$buttonId"

    fun loadButtons(profileKey: String): MutableList<ControlButtonDef> {
        val raw = prefs.getString(buttonsKey(profileKey), null) ?: return mutableListOf()
        val array = JSONArray(raw)
        val result = mutableListOf<ControlButtonDef>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                ControlButtonDef(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    roleKey = obj.getString("roleKey"),
                    controlType = ControlType.valueOf(obj.getString("controlType"))
                )
            )
        }
        return result
    }

    fun saveButtons(profileKey: String, buttons: List<ControlButtonDef>) {
        val array = JSONArray()
        buttons.forEach { btn ->
            val obj = JSONObject()
            obj.put("id", btn.id)
            obj.put("label", btn.label)
            obj.put("roleKey", btn.roleKey)
            obj.put("controlType", btn.controlType.name)
            array.put(obj)
        }
        prefs.edit().putString(buttonsKey(profileKey), array.toString()).apply()
    }

    fun getState(profileKey: String, buttonId: String): Boolean {
        return prefs.getBoolean(stateKey(profileKey, buttonId), false)
    }

    fun setState(profileKey: String, buttonId: String, isOn: Boolean) {
        prefs.edit().putBoolean(stateKey(profileKey, buttonId), isOn).apply()
    }
}
