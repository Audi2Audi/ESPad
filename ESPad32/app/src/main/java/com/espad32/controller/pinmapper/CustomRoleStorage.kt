package com.espad32.controller.pinmapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-defined function, distinct from the fixed roles baked into
 * Profiles.TRAIN/RC_CAR (e.g. "Motor direction A"). Lets someone add a
 * genuine "LED" function instead of repurposing an unrelated built-in
 * role just because its GPIO happens to be free.
 */
data class CustomRole(
    val key: String,
    val label: String,
    val group: String,
    val type: RoleType
)

/**
 * Persists custom roles and label overrides per profile. Built-in
 * roles (from Profiles.kt) can be renamed but not deleted — their
 * label is overridden here rather than mutated in place, since the
 * underlying key is what firmware/storage actually keys on. Custom
 * roles can be renamed AND deleted freely, since nothing else depends
 * on their key existing.
 */
class CustomRoleStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_custom_roles", Context.MODE_PRIVATE)

    private fun rolesKey(profileKey: String) = "custom_roles__$profileKey"
    private fun overridesKey(profileKey: String) = "label_overrides__$profileKey"

    fun loadCustomRoles(profileKey: String): MutableList<CustomRole> {
        val raw = prefs.getString(rolesKey(profileKey), null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val result = mutableListOf<CustomRole>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                CustomRole(
                    key = o.getString("key"),
                    label = o.getString("label"),
                    group = o.getString("group"),
                    type = RoleType.valueOf(o.getString("type"))
                )
            )
        }
        return result
    }

    fun saveCustomRoles(profileKey: String, roles: List<CustomRole>) {
        val arr = JSONArray()
        roles.forEach { r ->
            val o = JSONObject()
            o.put("key", r.key)
            o.put("label", r.label)
            o.put("group", r.group)
            o.put("type", r.type.name)
            arr.put(o)
        }
        prefs.edit().putString(rolesKey(profileKey), arr.toString()).apply()
    }

    fun loadLabelOverrides(profileKey: String): MutableMap<String, String> {
        val raw = prefs.getString(overridesKey(profileKey), null) ?: return mutableMapOf()
        val json = JSONObject(raw)
        val map = mutableMapOf<String, String>()
        json.keys().forEach { k -> map[k] = json.getString(k) }
        return map
    }

    fun saveLabelOverrides(profileKey: String, overrides: Map<String, String>) {
        val json = JSONObject()
        overrides.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(overridesKey(profileKey), json.toString()).apply()
    }

    /** Turns a label into a stable, unique role key, e.g. "LED" -> "led". */
    fun slugify(label: String, existingKeys: Set<String>): String {
        var base = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (base.isEmpty()) base = "custom_role"
        var candidate = base
        var i = 1
        while (existingKeys.contains(candidate)) {
            i++
            candidate = "${base}_$i"
        }
        return candidate
    }
}
