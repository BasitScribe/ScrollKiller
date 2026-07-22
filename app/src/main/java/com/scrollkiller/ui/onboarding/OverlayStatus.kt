package com.scrollkiller.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helpers for the overlay ("draw over other apps") permission — the second
 * onboarding step, gating the floating live-counter bubble.
 *
 * Mirrors [AccessibilityStatus]. Like accessibility, the app cannot grant this
 * itself; deep-linking to Settings is the sanctioned path. Unlike accessibility, it
 * is OPTIONAL — detection and the in-app counter work without it, so the onboarding
 * step is skippable and the bubble simply won't appear until it's granted.
 */
object OverlayStatus {

    /** True if the app may draw overlays (SYSTEM_ALERT_WINDOW granted). */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Deep-link into the per-app "Display over other apps" settings screen. */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
