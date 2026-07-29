package com.espad32.controller.pinmapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-created device profile. Train and RC Car are now ALSO stored
 * here (seeded once on first run, see seedBuiltInsIfNeeded below) —
 * there's no longer a separate "these two are special and compiled in"
 * tier. Anyone who doesn't have a train or RC car can delete them, or
 * delete/rename their individual functions, the exact same way they
 * would for any device they create themselves.
 */
data class CustomProfile(
    val key: String,
    val displayName: String,
    val boardKey: String,
    // GPIO defaults for "Reset Defaults" — e.g. Train's motor_dir_a -> 26.
    // Empty for a genuinely new user-created device (nothing to reset to).
    val defaults: Map<String, Int> = emptyMap(),
    // Which IP to connect to for THIS device — e.g. Train might always
    // be its own AP at 192.168.4.1, while a different device lives on
    // the home network at some other address. Null until the user sets
    // it (via Pin Mapper's device-management dialog). Deliberately just
    // an IP, not SSID/password — joining the right WiFi network is an
    // OS-level phone setting outside the app's control; once the phone
    // is already on the right network, the IP is the only thing this
    // app itself needs to remember per device.
    val connectionIp: String? = null
)

class CustomProfileStorage(context: Context) {

    private val prefs = context.getSharedPreferences("espad_custom_profiles", Context.MODE_PRIVATE)
    private val LIST_KEY = "profiles"
    private val SEEDED_KEY = "seeded_v1"

    fun loadProfiles(): MutableList<CustomProfile> {
        val raw = prefs.getString(LIST_KEY, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val result = mutableListOf<CustomProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val defaultsObj = o.optJSONObject("defaults")
            val defaults = mutableMapOf<String, Int>()
            defaultsObj?.keys()?.forEach { k -> defaults[k] = defaultsObj.getInt(k) }
            result.add(
                CustomProfile(
                    key = o.getString("key"),
                    displayName = o.getString("displayName"),
                    boardKey = o.getString("boardKey"),
                    defaults = defaults,
                    connectionIp = if (o.has("connectionIp") && !o.isNull("connectionIp")) o.getString("connectionIp") else null
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
            val defaultsObj = JSONObject()
            p.defaults.forEach { (k, v) -> defaultsObj.put(k, v) }
            o.put("defaults", defaultsObj)
            if (p.connectionIp != null) o.put("connectionIp", p.connectionIp)
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
        val existingKeys = existing.map { it.key }.toSet()
        val key = slugify(displayName, existingKeys)
        val profile = CustomProfile(key, displayName, boardKey)
        existing.add(profile)
        saveProfiles(existing)
        return profile
    }

    fun deleteProfile(key: String) {
        saveProfiles(loadProfiles().filterNot { it.key == key })
    }

    fun setConnectionIp(key: String, ip: String?) {
        val updated = loadProfiles().map { if (it.key == key) it.copy(connectionIp = ip?.ifBlank { null }) else it }
        saveProfiles(updated)
    }

    /**
     * Runs exactly once per install (guarded by a persisted flag, not
     * "does train exist" — that distinction matters: if it checked
     * existence instead, deleting Train would just bring it back the
     * next time this runs). Seeds Train and RC Car as ordinary
     * CustomProfile entries, and their original functions into
     * CustomRoleStorage — additively, alongside anything a user already
     * added (like a custom "LED" function), never overwriting existing
     * entries. Existing pin assignments (PinConfigStorage) and Controls
     * buttons are untouched by this entirely — they're keyed by role
     * string regardless of where the role "lives" conceptually, so
     * nothing already saved needs migrating.
     */
    fun seedBuiltInsIfNeeded(roleStorage: CustomRoleStorage) {
        if (prefs.getBoolean(SEEDED_KEY, false)) return

        val existing = loadProfiles()
        val existingKeys = existing.map { it.key }.toSet()

        if (!existingKeys.contains("train")) {
            existing.add(
                CustomProfile(
                    key = "train",
                    displayName = "Train (TB6612FNG + MAX98357A)",
                    boardKey = Boards.D1_MINI32.key,
                    defaults = mapOf(
                        "motor_dir_a" to 26, "motor_dir_b" to 27, "motor_pwm" to 14,
                        "motor_standby" to 12, "audio_bclk" to 25, "audio_lrc" to 33, "audio_din" to 32
                    )
                )
            )
        }
        if (!existingKeys.contains("rc_car")) {
            existing.add(
                CustomProfile(
                    key = "rc_car",
                    displayName = "RC Car",
                    boardKey = Boards.D1_MINI32.key,
                    defaults = mapOf(
                        "motor_a_dir1" to 16, "motor_a_dir2" to 17, "motor_a_pwm" to 4,
                        "steering_servo" to 18, "headlight" to 19
                    )
                )
            )
        }
        saveProfiles(existing)

        seedRolesAdditively(
            roleStorage, "train", listOf(
                CustomRole("motor_dir_a", "Motor direction A", "Motor", RoleType.DIGITAL_OUTPUT),
                CustomRole("motor_dir_b", "Motor direction B", "Motor", RoleType.DIGITAL_OUTPUT),
                CustomRole("motor_pwm", "Motor speed (PWM)", "Motor", RoleType.PWM_OUTPUT),
                CustomRole("motor_standby", "Motor standby", "Motor", RoleType.DIGITAL_OUTPUT),
                CustomRole("audio_bclk", "Audio bit clock", "Audio", RoleType.AUDIO_SIGNAL),
                CustomRole("audio_lrc", "Audio L/R clock", "Audio", RoleType.AUDIO_SIGNAL),
                CustomRole("audio_din", "Audio data in", "Audio", RoleType.AUDIO_SIGNAL)
            )
        )
        seedRolesAdditively(
            roleStorage, "rc_car", listOf(
                CustomRole("motor_a_dir1", "Motor A direction 1", "Drive", RoleType.DIGITAL_OUTPUT),
                CustomRole("motor_a_dir2", "Motor A direction 2", "Drive", RoleType.DIGITAL_OUTPUT),
                CustomRole("motor_a_pwm", "Motor A speed (PWM)", "Drive", RoleType.PWM_OUTPUT),
                CustomRole("steering_servo", "Steering servo", "Steering", RoleType.SERVO),
                CustomRole("headlight", "Headlights", "Lights", RoleType.DIGITAL_OUTPUT)
            )
        )

        prefs.edit().putBoolean(SEEDED_KEY, true).apply()
    }

    private fun seedRolesAdditively(roleStorage: CustomRoleStorage, profileKey: String, seedRoles: List<CustomRole>) {
        val existingRoles = roleStorage.loadCustomRoles(profileKey)
        val existingRoleKeys = existingRoles.map { it.key }.toSet()
        val toAdd = seedRoles.filterNot { existingRoleKeys.contains(it.key) }
        if (toAdd.isNotEmpty()) {
            roleStorage.saveCustomRoles(profileKey, existingRoles + toAdd)
        }
    }
}
