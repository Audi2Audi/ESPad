package com.espad32.controller.pinmapper

import android.content.Context

/**
 * Merges Profiles.ALL (the two built-in profiles) with user-created
 * ones (CustomProfileStorage) into one list — for screens that just
 * need to display or look up "every profile that exists," not manage
 * creation/deletion themselves.
 */
object ProfileResolver {
    fun allProfiles(context: Context): List<DeviceProfile> {
        val custom = CustomProfileStorage(context).loadProfiles().map { c ->
            DeviceProfile(
                key = c.key,
                displayName = c.displayName,
                boardKey = c.boardKey,
                roles = emptyList(),
                defaults = emptyMap()
            )
        }
        return Profiles.ALL + custom
    }
}
