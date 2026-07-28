package com.scrollkiller.challenge

import android.content.Context
import com.scrollkiller.ui.onboarding.MotionStatus

/**
 * Can this device actually run this challenge, right now? The ONE predicate the block screen asks
 * before offering a challenge, and the reason a control that cannot do its job never appears on a
 * screen covering another app.
 *
 * ## Why this is per-SPEC and not one app-wide flag
 * It used to be [MotionStatus.isAvailable], which means "`ACTIVITY_RECOGNITION` is held AND step
 * hardware is present". That was exactly right while WALK was the only challenge and completely
 * wrong the moment a second one existed, because the step sensors are the ONLY ones behind a runtime
 * permission — the accelerometer needs no grant at all.
 *
 * Asking one question for every challenge therefore hid challenges that work fine: a user who
 * declined the motion permission, or a device with no pedometer, lost the jump challenge too, for a
 * permission jumping never needed. Availability is a property of the STRATEGY, so it is answered
 * per strategy here.
 *
 * [MotionStatus] is deliberately left alone — it is still the right question for Settings' motion
 * row and for [com.scrollkiller.permission.PermissionHealthReader], both of which are asking
 * specifically about step access.
 */
object ChallengeAvailability {

    /**
     * True when [spec] can run: its hardware exists and any permission its strategy needs is held.
     *
     * Both halves fail differently and that difference is why they are checked together rather than
     * being reported separately — no permission is fixable by the user in Settings, no sensor is not
     * fixable at all, and neither can be requested from an AccessibilityService (there is no
     * Activity). From the block screen's point of view the only useful answer is "offer it or don't".
     */
    fun isAvailable(context: Context, spec: ChallengeSpec): Boolean {
        // A strategy with no source is unbuilt; the registry's IMPLEMENTED gate should already have
        // kept it out of `enabled`, and this is the second line of defence rather than the first.
        val source = ChallengeSensors.sourceFor(context, spec.sensorStrategy) ?: return false
        if (!source.hasSensor()) return false

        return when (spec.sensorStrategy) {
            // Step sensors sit behind ACTIVITY_RECOGNITION (API 29+; MotionStatus handles 26–28,
            // where the platform required no grant).
            SensorStrategy.STEP_EVENTS,
            SensorStrategy.STEP_CUMULATIVE,
            -> MotionStatus.hasPermission(context)

            // Neither the accelerometer nor the proximity sensor is behind a dangerous permission and
            // neither ever has been, so the hardware check above is the whole question for these.
            //
            // PROXIMITY_HOLD is the one whose hardware check can genuinely FAIL on a shipped device:
            // its source requires proximity AND accelerometer, and proximity — while near-universal —
            // is absent on some tablets and budget handsets. Such a device simply never sees the
            // forehead row, which is the graceful-absence rule rather than a special case.
            SensorStrategy.ACCEL_PEAKS,
            SensorStrategy.ORIENTATION_HOLD,
            SensorStrategy.PROXIMITY_HOLD,
            -> true
        }
    }

    /** The subset of [ChallengeRegistry.enabled] this device can run. May be empty. */
    fun available(context: Context): List<ChallengeSpec> =
        ChallengeRegistry.enabled.filter { isAvailable(context, it) }
}
