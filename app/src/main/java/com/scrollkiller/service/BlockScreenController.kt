package com.scrollkiller.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.scrollkiller.R
import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.challenge.ChallengeSpec
import com.scrollkiller.challenge.ProgressRingView
import com.scrollkiller.ui.onboarding.MotionStatus

/**
 * Owns the full-screen block overlay drawn over the reel surface once the daily limit
 * is hit. Sibling of [OverlayController] (which owns the passive bubble); kept out of
 * the AccessibilityService so the service stays event-only.
 *
 * Robustness / policy properties:
 *  - FOCUSABLE full-screen window so it traps input and receives Back — the user can't
 *    poke Instagram behind it. Back is mapped to [onExit] (never a silent dismiss).
 *  - add/removeView on show/hide (not a lingering GONE view): a focusable window must
 *    not sit around intercepting input when we're not blocking. Blocking is rare, so
 *    there's no churn concern (unlike the per-scroll bubble — D17).
 *  - NEVER a trap: Exit + Back both leave. NEVER impersonates IG/system chrome.
 *    This is CLAUDE.md invariant 6, not a nice-to-have — every path out of this class must
 *    work without the count, a timer, the network or a challenge cooperating.
 *  - Optional: a no-op if the overlay permission isn't granted, so blocking never
 *    hard-depends on it (matches the bubble).
 *
 * All methods run on the service main thread.
 *
 * @param onExit user chose to leave (Exit button or Back).
 * @param onSnooze user asked for [BlockLimits.GRACE_MINUTES] more minutes. Grants a timed
 *   reprieve.
 * @param onStartChallenge user chose the physical unlock (D50). An ALTERNATIVE to Exit, never a
 *   replacement — both panels this class shows carry their own Exit.
 * @param onCancelChallenge user backed out of the challenge, returning to the block.
 */
class BlockScreenController(
    private val context: Context,
    private val onExit: () -> Unit,
    private val onSnooze: () -> Unit,
    private val onStartChallenge: () -> Unit,
    private val onCancelChallenge: () -> Unit,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The block view while shown; null when hidden. */
    private var view: View? = null

    /** True while the block window is attached. */
    val isShowing: Boolean get() = view != null

    /**
     * Show the block for [platform] with a [guiltLine]. Idempotent — if already
     * showing, just refresh the guilt text. No-op without the overlay permission.
     */
    @Suppress("UNUSED_PARAMETER") // kept for parity with the bubble + future per-platform copy
    fun show(platform: Platform, guiltLine: String) {
        if (!Settings.canDrawOverlays(context)) {
            Log.d(TAG, "Overlay permission not granted; block not shown")
            return
        }
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_guilt).text = guiltLine
            return
        }

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        root.findViewById<TextView>(R.id.block_guilt).text = guiltLine
        val snooze = root.findViewById<Button>(R.id.block_snooze)
        // Formatted from the constant, never written as copy: the sentence the user reads and the
        // reprieve they are granted are then the same number by construction (D49).
        snooze.text = context.getString(R.string.block_snooze, BlockLimits.GRACE_MINUTES)
        snooze.setOnClickListener { onSnooze() }
        root.findViewById<Button>(R.id.block_exit).setOnClickListener { onExit() }

        // The physical unlock (D50). Shown only when the device can actually run it — see
        // MotionStatus; a button that cannot do its job has no business on a screen covering
        // another app, and the permission it would need cannot be requested from here anyway
        // (no Activity), so pointing at it would be a signpost rather than a fix.
        val challenge = root.findViewById<Button>(R.id.block_challenge)
        val spec = ChallengeRegistry.default
        if (spec != null && MotionStatus.isAvailable(context)) {
            // Names its cost AND its reward, because the design rests on the user choosing the
            // harder path knowingly: a small free thing beside a larger earned one.
            challenge.text = context.getString(
                R.string.block_challenge,
                spec.target,
                BlockLimits.CHALLENGE_GRACE_MINUTES,
            )
            challenge.visibility = View.VISIBLE
            challenge.setOnClickListener { onStartChallenge() }
        } else {
            challenge.visibility = View.GONE
        }

        // The challenge panel's own way out. Exit is duplicated across both panels ON PURPOSE:
        // a challenge screen offering only "Back" would be a second screen to escape before you
        // can escape, which is exactly what invariant 6 forbids.
        root.findViewById<Button>(R.id.challenge_exit).setOnClickListener { onExit() }
        root.findViewById<Button>(R.id.challenge_cancel).setOnClickListener { onCancelChallenge() }

        // Intercept Back so it can't dismiss the block to reveal Instagram — Back = Exit.
        //
        // The listener stays on the ROOT and the root keeps focus (see requestFocus below): a
        // ViewGroup's OnKeyListener only runs when the ViewGroup ITSELF is the focused view, so
        // moving focus onto one of the buttons would silently stop Back being handled at all —
        // and an unhandled Back on a WindowManager-added view does nothing, which is the trap
        // invariant 6 forbids. TalkBack order is solved in the LAYOUT instead (Exit comes first).
        //
        // DOWN is consumed as well as UP so the framework never gets a half-handled Back; the
        // action fires on UP, which is where a press is committed.
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_UP) onExit()
                true
            }
        }

        try {
            windowManager.addView(root, createLayoutParams())
        } catch (e: Exception) {
            Log.w(TAG, "addView failed; block not shown", e)
            return
        }
        root.requestFocus()
        view = root
    }

    /**
     * Swap the window's content to the challenge (D50).
     *
     * A CHILD toggle inside the already-attached window — the same shape as the bubble's expand
     * (D37), not a second window and not a rebuild. The root, its focus and its Back listener are
     * untouched, so Back still maps to Exit while the challenge is up.
     *
     * No-op when the block is not showing: a challenge with no block behind it has nothing to
     * dismiss on completion.
     */
    fun showChallenge(spec: ChallengeSpec) {
        val root = view ?: return
        root.findViewById<TextView>(R.id.challenge_prompt).text =
            context.getString(spec.promptRes, spec.target)
        root.findViewById<TextView>(R.id.challenge_reward).text =
            context.getString(R.string.challenge_reward, BlockLimits.CHALLENGE_GRACE_MINUTES)
        root.findViewById<ProgressRingView>(R.id.challenge_ring).setProgress(0, spec.target)
        root.findViewById<View>(R.id.block_panel).visibility = View.GONE
        root.findViewById<View>(R.id.challenge_panel).visibility = View.VISIBLE
    }

    /** Back to the block itself, abandoning any challenge on screen. No-op when not showing. */
    fun showBlockPanel() {
        val root = view ?: return
        root.findViewById<View>(R.id.challenge_panel).visibility = View.GONE
        root.findViewById<View>(R.id.block_panel).visibility = View.VISIBLE
    }

    /** Push the live step count into the ring. Cheap; the ring skips an unchanged redraw. */
    fun renderChallengeProgress(progress: Int, target: Int) {
        view?.findViewById<ProgressRingView>(R.id.challenge_ring)?.setProgress(progress, target)
    }

    /** Remove the block window if shown. */
    fun hide() {
        val v = view ?: return
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        view = null
    }

    /** Teardown for service destroy/unbind. */
    fun destroy() = hide()

    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION") // min-SDK 26, so the overlay type is correct.
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Focusable (no FLAG_NOT_FOCUSABLE) so it captures touches + Back.
            // LAYOUT_IN_SCREEN so it truly covers the whole surface including system bars.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
    }

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
