package com.espad32.controller.controls

import android.content.Context

/**
 * MainActivity (the live driving screen) has no built-in concept of
 * "which device profile am I controlling" — it predates the
 * Train/RC Car profile system entirely. This is the seam that connects
 * them: whichever profile was last selected in the Pin Mapper or
 * Controls screen becomes the "active" one, and MainActivity reads it
 * to know which set of Control buttons to render live.
 *
 * This is a simple last-selected-wins model — there's no concept yet
 * of "the ESP32 currently connected over WiFi is definitely running
 * profile X." That's a real gap once live device sync exists (the
 * app can't actually know which firmware profile is on the other end
 * without the device telling it), but it's out of scope until that
 * transport work happens — see PIN_MAPPER_ROADMAP.md.
 */
object ActiveProfile {
    private const val PREFS = "espad_active_profile"
    private const val KEY = "active_profile_key"

    fun get(context: Context, fallback: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, fallback) ?: fallback
    }

    fun set(context: Context, profileKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, profileKey).apply()
    }
}
