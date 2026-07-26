package com.espad32.controller.pinmapper

/**
 * Merges a profile's built-in roles (with any label overrides applied)
 * and its user-added custom roles into one list. PinMapperActivity owns
 * custom roles/overrides as in-memory state (since it's the screen that
 * adds/renames/deletes them) and has its own equivalent inline — this
 * is for other screens (Controls) that only need to *read* the merged
 * list, not manage it.
 */
object RoleResolver {
    fun effectiveRoles(profile: DeviceProfile, customRoleStorage: CustomRoleStorage): List<PinRoleDef> {
        val labelOverrides = customRoleStorage.loadLabelOverrides(profile.key)
        val customRoles = customRoleStorage.loadCustomRoles(profile.key)

        val builtIn = profile.roles.map { r ->
            PinRoleDef(r.key, labelOverrides[r.key] ?: r.label, r.group, r.type)
        }
        val custom = customRoles.map { c -> PinRoleDef(c.key, c.label, c.group, c.type) }
        return builtIn + custom
    }
}
