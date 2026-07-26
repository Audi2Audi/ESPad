package com.espad32.controller.pinmapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-created device profile — unlike Profiles.TRAIN/RC_CAR, this
 * starts with NO built-in roles at all, so someone building a simple
 * lamp isn't forced to fill in motor/audio roles a train needs before
 * they're allowed to save anything.
 *
 * Functions get added the exact same way custom roles are added to any
 * existing profile (see CustomRoleStorage) — a user-created profile is
 * just a profileKey with only custom roles and no built-in ones. Pin
 * assignments (PinConfigStorage), buttons (ControlButtonStorage), and
 * gamepad mappings are all already keyed generically by profileKey, so
 * they work correctly for a custom profile with zero changes needed —
 * the only genuinely new piece is creating the profile itself and
 * making the profile-tab UI dynamic instead of two hardcoded tabs.
 */
data class CustomProfile(
    val key: String,
    val displayName: String,
    val boardKey: String
)

class CustomProfileStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_custom_profiles", Context.MODE_PRIVATE)
    private val LIST_KEY = "profiles"

    fun loadProfiles(): MutableList<CustomProfile> {
        val raw = prefs.getString(LIST_KEY, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val result = mutableListOf<CustomProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                CustomProfile(
                    key = o.getString("key"),
                    displayName = o.getString("displayName"),
                    boardKey = o.getString("boardKey")
                )
            )
        }
        return result
    }

    fun saveProfiles(profiles: List<CustomProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            val o = JSONObject()
            o.put("key", p.key)
            o.put("displayName", p.displayName)
            o.put("boardKey", p.boardKey)
            arr.put(o)
        }
        prefs.edit().putString(LIST_KEY, arr.toString()).apply()
    }

    /** Turns a device name into a stable, unique profile key, e.g. "Lamp" -> "lamp". */
    fun slugify(name: String, existingKeys: Set<String>): String {
        var base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (base.isEmpty()) base = "device"
        var candidate = base
        var i = 1
        while (existingKeys.contains(candidate)) {
            i++
            candidate = "${base}_$i"
        }
        return candidate
    }

    fun addProfile(displayName: String, boardKey: String): CustomProfile {
        val existing = loadProfiles()
        val existingKeys = (Profiles.ALL.map { it.key } + existing.map { it.key }).toSet()
        val key = slugify(displayName, existingKeys)
        val profile = CustomProfile(key, displayName, boardKey)
        existing.add(profile)
        saveProfiles(existing)
        return profile
    }

    fun deleteProfile(key: String) {
        saveProfiles(loadProfiles().filterNot { it.key == key })
    }
}
