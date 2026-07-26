package com.espad32.controller.pinmapper

import android.content.Context

/**
 * Returns every device profile that exists — Train, RC Car, and
 * anything user-created — all from the same storage, since Train/RC
 * Car are seeded there once on first run rather than being a separate
 * compiled-in tier. See CustomProfileStorage.seedBuiltInsIfNeeded for
 * why deleting one doesn't bring it back on the next call.
 */
object ProfileResolver {
    fun allProfiles(context: Context): List<DeviceProfile> {
        val profileStorage = CustomProfileStorage(context)
        val roleStorage = CustomRoleStorage(context)
        profileStorage.seedBuiltInsIfNeeded(roleStorage)

        return profileStorage.loadProfiles().map { c ->
            DeviceProfile(
                key = c.key,
                displayName = c.displayName,
                boardKey = c.boardKey,
                roles = emptyList(),
                defaults = c.defaults
            )
        }
    }
}
