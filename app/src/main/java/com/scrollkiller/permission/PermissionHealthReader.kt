package com.scrollkiller.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.scrollkiller.ui.onboarding.AccessibilityStatus
import com.scrollkiller.ui.onboarding.OverlayStatus

/**
 * Reads the live permission state off the device and turns it into a [PermissionHealth].
 *
 * The Android half of D51's single source of truth: this does the READING, [PermissionHealth] does
 * the deciding. Splitting them is what makes every rule in that class unit-testable, and it means
 * there is exactly one place that knows how to ask the system each question.
 *
 * Deliberately composes the EXISTING helpers rather than re-implementing them —
 * [AccessibilityStatus.isServiceEnabled] and [OverlayStatus.canDrawOverlays] were already correct
 * and already used by the onboarding flow. A second implementation of "is the service on" is how
 * the Activity and the service end up disagreeing.
 */
object PermissionHealthReader {

    /** The runtime permission the warning notification needs, for the Settings request launcher. */
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** The whole picture, read fresh. Cheap enough to call on resume and on surface entry. */
    fun of(context: Context): PermissionHealth = PermissionHealth(
        accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context),
        canDrawOverlays = OverlayStatus.canDrawOverlays(context),
        canNotify = canNotify(context),
    )

    /**
     * May we post a notification?
     *
     * Two conditions, and both genuinely happen. The runtime grant only exists from API 33 —
     * below that notifications need no permission at all, and returning false there would make
     * every older device permanently "degraded" for no reason (the same shape as
     * [com.scrollkiller.ui.onboarding.MotionStatus.hasPermission] handling API 26–28).
     *
     * [NotificationManagerCompat.areNotificationsEnabled] is checked as well because a user can
     * switch the app's notifications off in system settings WITHOUT revoking the permission, and
     * a notification posted into a disabled channel is exactly the silent failure this whole
     * session exists to eliminate.
     */
    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Send the user to the right system screen for [gap].
     *
     * [PermissionGap.NOTIFICATIONS] goes to the app's notification settings rather than requesting
     * the runtime permission, because this is reachable from a non-Activity context. The in-app
     * Settings row does the proper `RequestPermission` request; this is the deep-link fallback for
     * a user who already denied it once, where the system will not show the dialog again.
     */
    fun openSettingsFor(context: Context, gap: PermissionGap) {
        when (gap) {
            PermissionGap.ACCESSIBILITY -> AccessibilityStatus.openAccessibilitySettings(context)
            PermissionGap.OVERLAY -> OverlayStatus.openOverlaySettings(context)
            PermissionGap.NOTIFICATIONS -> openNotificationSettings(context)
        }
    }

    private fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
