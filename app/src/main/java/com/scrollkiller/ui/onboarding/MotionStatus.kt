package com.scrollkiller.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.scrollkiller.challenge.StepSensorSource

/**
 * Helpers for the step-sensor access the physical challenges need (D50).
 *
 * Mirrors [OverlayStatus] and [AccessibilityStatus], with ONE structural difference worth naming:
 * those two are system settings the app cannot grant and can only deep-link to, whereas
 * `ACTIVITY_RECOGNITION` is an ordinary RUNTIME PERMISSION — so it is requested with
 * `ActivityResultContracts.RequestPermission` from the Activity, and there is no
 * `openMotionSettings` here to match their `open…Settings`. An accessibility service has no
 * Activity, which is exactly why the request lives in Settings rather than on the block screen.
 *
 * Like the overlay, this is OPTIONAL: without it the challenge is simply not offered, and
 * detection, the counter, the block, Exit and "5 more minutes" are all completely unaffected.
 */
object MotionStatus {

    /** The runtime permission the step sensors sit behind, for the Activity's request launcher. */
    const val PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION

    /**
     * Is the step-sensor permission held?
     *
     * TRUE on API 26–28 without asking anyone: `ACTIVITY_RECOGNITION` only became a dangerous
     * permission in API 29, and before that the platform's step sensors needed no grant at all.
     * Returning false there would hide the challenge from devices that can run it perfectly well.
     */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Does the hardware have a step detector or counter? Some devices genuinely have neither. */
    fun hasStepSensor(context: Context): Boolean = StepSensorSource(context).hasSensor()

    /**
     * Can this device run a STEP challenge? Both halves must hold, and they fail differently: no
     * permission is fixable by the user in Settings, no sensor is not fixable at all.
     * [hasStepSensor] is what Settings checks before showing its row, so a device that can never
     * run a step challenge is never asked for a permission that would unlock nothing.
     *
     * NOT what the block screen asks any more. It used to be the one app-wide question, which was
     * right while WALK was the only challenge and wrong the moment a second one existed — the step
     * sensors are the only ones behind a runtime permission, so this hid a jump challenge that
     * needs no permission at all. That question is now
     * [com.scrollkiller.challenge.ChallengeAvailability.isAvailable], per spec. This remains the
     * right question for Settings' motion row and for the permission-health reader, both of which
     * are asking specifically about step access.
     */
    fun isAvailable(context: Context): Boolean =
        hasPermission(context) && hasStepSensor(context)
}
