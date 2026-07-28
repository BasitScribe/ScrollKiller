package com.scrollkiller.challenge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The buzz that tells a user what the ring cannot.
 *
 * ## Why this exists at all
 * The face-down challenge asks the user to put the screen against a table for thirty seconds, which
 * means the live progress ring — the only feedback the mechanic had — is pointed at the floor and
 * completely invisible. Without a signal the user's only option is to flip the phone up to check,
 * and flipping up BREAKS the hold and resets it to zero (see [HoldDetector]). So they check, lose
 * their progress, cannot tell why, and conclude the challenge is broken. The buzz is what makes the
 * hold challenges usable rather than merely implemented.
 *
 * Two distinct signals, because "you're done" and "you just lost it" must not be confusable:
 *  - [complete] — one long buzz. Flip up; the block is gone.
 *  - [broken] — two short buzzes. Put it back down; you are starting again.
 *
 * ## Optional, like every other capability
 * `VIBRATE` is a NORMAL permission, granted at install with no runtime prompt and no Settings row to
 * deep-link to — so unlike the sensors there is nothing for the user to fix and nothing to gate a
 * challenge on. A device with no vibrator (or a manufacturer API that throws) simply gets silence:
 * every path here fails soft, because a missing buzz must never take down an accessibility service
 * mid-challenge.
 *
 * Main thread only, like the rest of the overlay.
 */
class ChallengeHaptics(context: Context) {

    private val vibrator: Vibrator? = resolve(context)

    /** Finished: one solid buzz, long enough to be unmistakable through a table. */
    fun complete() = play(VibrationEffect.createOneShot(COMPLETE_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    /**
     * Hold broken: two short buzzes.
     *
     * Deliberately a different SHAPE rather than a different length — a single shorter buzz would be
     * indistinguishable from [complete] through a cushion, and confusing "done" with "you lost it" is
     * the one mistake this class exists to prevent. Waveform timings alternate off/on starting with
     * off, hence the leading 0.
     */
    fun broken() = play(VibrationEffect.createWaveform(BROKEN_PATTERN, -1))

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(effect)
        } catch (e: Exception) {
            // Some OEM vibrator services throw when the device is in a mode that suppresses haptics.
            // A silent challenge is a degraded challenge; a crashed service is a dead app.
            Log.w(TAG, "vibrate failed", e)
        }
    }

    private companion object {
        const val TAG = "ScrollKiller"

        /** One long buzz for completion. */
        const val COMPLETE_MS = 400L

        /** off, on, off, on — a double tap that cannot be mistaken for the completion buzz. */
        val BROKEN_PATTERN = longArrayOf(0, 60, 90, 60)

        /**
         * `VibratorManager` is the API 31+ route and `getSystemService(Vibrator)` is deprecated there;
         * below 31 only the latter exists. Both are wrapped because a null service is possible on
         * stripped ROMs.
         */
        fun resolve(context: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") // the only route below API 31
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "no vibrator service", e)
            null
        }
    }
}
