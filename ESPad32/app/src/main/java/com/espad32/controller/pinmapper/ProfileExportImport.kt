package com.espad32.controller.pinmapper

import android.content.Context
import com.espad32.controller.controls.ControlButtonDef
import com.espad32.controller.controls.ControlButtonStorage
import com.espad32.controller.controls.ControlType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports a full device profile as portable JSON — functions
 * (custom roles, with any label overrides already flattened in),
 * pin assignments for the profile's board, and Controls buttons.
 *
 * NOT included, on purpose:
 * - Gamepad button/axis mappings. ControllerMapping is a single global
 *   list for the whole app (which physical GameSir button does what),
 *   not scoped per device profile at all — there's no clean way to
 *   export "this profile's gamepad mappings" when the mapping itself
 *   doesn't know which profile it belongs to. A real limitation, not
 *   an oversight — see PIN_MAPPER_ROADMAP.md.
 * - Board definitions. Boards (D1 Mini32, ESP32 DevKit V1, etc) are
 *   compiled into the app itself, not device-specific data — any
 *   install of this same app already recognizes the same board keys,
 *   so only the boardKey string needs to travel with the export.
 */
object ProfileExportImport {

    private const val FORMAT_VERSION = 1

    fun buildExportJson(
        context: Context,
        profile: DeviceProfile,
        roles: List<PinRoleDef>
    ): String {
        val pinStorage = PinConfigStorage(context)
        val buttonStorage = ControlButtonStorage(context)

        val boardKey = pinStorage.loadSelectedBoard(profile.key, profile.boardKey)
        val assignments = pinStorage.load(profile.key, boardKey, profile.defaults)
        val buttons = buttonStorage.loadButtons(profile.key)

        val root = JSONObject()
        root.put("espadProfileExport", FORMAT_VERSION)

        val profileObj = JSONObject()
        profileObj.put("displayName", profile.displayName)
        profileObj.put("boardKey", boardKey)
        root.put("profile", profileObj)

        val rolesArr = JSONArray()
        roles.forEach { r ->
            val o = JSONObject()
            o.put("key", r.key)
            o.put("label", r.label)
            o.put("group", r.group)
            o.put("type", r.type.name)
            rolesArr.put(o)
        }
        root.put("roles", rolesArr)

        val pinsObj = JSONObject()
        assignments.forEach { (roleKey, gpio) -> if (gpio != null) pinsObj.put(roleKey, gpio) }
        root.put("pinAssignments", pinsObj)

        val buttonsArr = JSONArray()
        buttons.forEach { b ->
            val o = JSONObject()
            o.put("label", b.label)
            o.put("roleKey", b.roleKey)
            o.put("controlType", b.controlType.name)
            buttonsArr.put(o)
        }
        root.put("buttons", buttonsArr)

        return root.toString(2)
    }

    data class ImportResult(val profile: DeviceProfile?, val error: String?)

    fun importFromJson(context: Context, json: String): ImportResult {
        val root: JSONObject
        try {
            root = JSONObject(json)
        } catch (e: Exception) {
            return ImportResult(null, "Not valid JSON — check the text was copied completely.")
        }

        if (!root.has("profile") || !root.has("roles")) {
            return ImportResult(null, "Missing 'profile' or 'roles' — this doesn't look like an ESPad profile export.")
        }

        val profileObj = root.getJSONObject("profile")
        val displayName = profileObj.optString("displayName", "Imported Device")
        // .ifBlank, not just optString's own missing-key default — a
        // device-exported profile (from the web UI) sends boardKey as
        // a present-but-empty string, since the device has no board
        // concept at all. optString's default only covers the key
        // being absent entirely, not present-but-blank, so without
        // this the board would silently resolve to "" — Boards.byKey()
        // falls back safely when actually looked up, but the imported
        // profile's board tab would never show as selected anywhere.
        val boardKey = profileObj.optString("boardKey", Boards.D1_MINI32.key).ifBlank { Boards.D1_MINI32.key }

        val profileStorage = CustomProfileStorage(context)
        val roleStorage = CustomRoleStorage(context)
        val pinStorage = PinConfigStorage(context)
        val buttonStorage = ControlButtonStorage(context)

        // Always create a NEW profile with a fresh, guaranteed-unique
        // key — never overwrite an existing one, even if importing back
        // onto the same device a profile was exported from.
        val existingKeys = profileStorage.loadProfiles().map { it.key }.toSet()
        val newKey = profileStorage.slugify(displayName, existingKeys)

        val rolesArr = root.optJSONArray("roles") ?: JSONArray()
        val importedRoles = mutableListOf<CustomRole>()
        for (i in 0 until rolesArr.length()) {
            val r = rolesArr.getJSONObject(i)
            val type = try {
                RoleType.valueOf(r.optString("type", "DIGITAL_OUTPUT"))
            } catch (e: Exception) {
                RoleType.DIGITAL_OUTPUT
            }
            importedRoles.add(
                CustomRole(
                    key = r.getString("key"),
                    label = r.getString("label"),
                    group = r.optString("group", "Custom"),
                    type = type
                )
            )
        }
        if (importedRoles.isEmpty()) {
            return ImportResult(null, "No functions found in this export — nothing to import.")
        }

        val pinsObj = root.optJSONObject("pinAssignments") ?: JSONObject()
        val assignments = mutableMapOf<String, Int?>()
        pinsObj.keys().forEach { roleKey -> assignments[roleKey] = pinsObj.getInt(roleKey) }

        val buttonsArr = root.optJSONArray("buttons") ?: JSONArray()
        val importedButtons = mutableListOf<ControlButtonDef>()
        for (i in 0 until buttonsArr.length()) {
            val b = buttonsArr.getJSONObject(i)
            val controlType = try {
                ControlType.valueOf(b.optString("controlType", "TOGGLE"))
            } catch (e: Exception) {
                ControlType.TOGGLE
            }
            importedButtons.add(
                ControlButtonDef(
                    id = "btn_${System.currentTimeMillis()}_$i", // fresh id, avoids any collision
                    label = b.getString("label"),
                    roleKey = b.getString("roleKey"),
                    controlType = controlType
                )
            )
        }

        // Persist everything under the new key.
        profileStorage.saveProfiles(
            profileStorage.loadProfiles().also {
                it.add(CustomProfile(newKey, displayName, boardKey))
            }
        )
        roleStorage.saveCustomRoles(newKey, importedRoles)
        pinStorage.save(newKey, boardKey, assignments)
        pinStorage.saveSelectedBoard(newKey, boardKey)
        if (importedButtons.isNotEmpty()) {
            buttonStorage.saveButtons(newKey, importedButtons)
        }

        val resultProfile = DeviceProfile(newKey, displayName, boardKey, emptyList(), emptyMap())
        return ImportResult(resultProfile, null)
    }
}
