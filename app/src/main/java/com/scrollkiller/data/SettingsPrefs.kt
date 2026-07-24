package com.scrollkiller.data

import android.content.Context

/**
 * Tiny wrapper over one SharedPreferences file for user settings, so the writer (the
 * Settings tab) and the reader (the overlay) can't drift on key/file names. No DI —
 * matches the app's manual-graph, ₹0-complexity constraint.
 */
object SettingsPrefs {

    private const val FILE = "scrollkiller_settings"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"

    /** Whether the floating counter bubble may show. Default on (matches Phase-1 behaviour). */
    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
