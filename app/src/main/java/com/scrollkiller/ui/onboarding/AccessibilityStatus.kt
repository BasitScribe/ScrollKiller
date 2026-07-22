package com.scrollkiller.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.scrollkiller.service.ReelScrollAccessibilityService

/**
 * Helpers for the accessibility onboarding flow.
 *
 * Android deliberately forbids an app from programmatically enabling its own
 * accessibility service (it would defeat the security model). Deep-linking the user
 * to Settings IS the sanctioned path — [openAccessibilitySettings] — and our job is
 * to make that step guided and honest (see DisclosureScreen).
 */
object AccessibilityStatus {

    /**
     * True if our [ReelScrollAccessibilityService] is currently enabled.
     *
     * Reads the system's colon-separated list of enabled services and looks for our
     * flattened component name. Preferred over AccessibilityManager.getEnabledServices
     * because it reflects the Settings toggle even before the service rebinds.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ReelScrollAccessibilityService::class.java)
            .flattenToString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** Deep-link into the system Accessibility settings screen. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Needed because we may launch from a non-Activity context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
