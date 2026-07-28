package com.scrollkiller.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.scrollkiller.R
import com.scrollkiller.challenge.ChallengeAvailability
import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.challenge.ChallengeSpec
import com.scrollkiller.challenge.ProgressRingView
import com.scrollkiller.challenge.ProgressUnit
import com.scrollkiller.ui.theme.Brand

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
 * @param onOpenChooser user asked to earn their way out (D50/D53). Opens the chooser; does NOT
 *   start anything. An ALTERNATIVE to Exit, never a replacement — every panel this class shows
 *   carries its own Exit.
 * @param onChooseChallenge user picked a specific challenge from the chooser. Null spec means
 *   "Surprise me" — the caller draws one, because which challenges are available is not this
 *   class's business.
 * @param onCancelChallenge user backed out of the challenge or the chooser, returning to the block.
 * @param onWindowLost the system took our window away without us asking (D52). The only
 *   trustworthy signal that the block is not on screen on a ROM whose permission query lies.
 */
class BlockScreenController(
    private val context: Context,
    private val onExit: () -> Unit,
    private val onSnooze: () -> Unit,
    private val onOpenChooser: () -> Unit,
    private val onChooseChallenge: (ChallengeSpec?) -> Unit,
    private val onCancelChallenge: () -> Unit,
    private val onWindowLost: () -> Unit = {},
) {

    /**
     * What happened when the block was asked to show itself.
     *
     * [show] used to return Unit and swallow every failure, which is what let a refused window
     * turn into a retry storm — the caller had no way to know the difference between "it is up"
     * and "it will never be up" (D52). Making the outcome a value forces the question to be
     * answered at the call site.
     */
    enum class ShowResult {
        /** The window was created and is verifiably attached. */
        SHOWN,

        /** Already up; the guilt text was refreshed and nothing else happened. */
        ALREADY_SHOWING,

        /** `Settings.canDrawOverlays` says no. Honest, queryable, fixable by the user. */
        NO_PERMISSION,

        /**
         * The window did not appear even though the permission query said it should — an
         * exception from `addView`, or an attach that never landed. THE case this ROM produces.
         */
        FAILED,

        /** Suppressed by [BlockRetryPolicy] after a recent failure. Costs nothing; inflates nothing. */
        COOLING_DOWN,
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * The block view while shown; null when hidden.
     *
     * ## This field used to do two jobs and fail at one of them (D52)
     * It is both the view handle AND the idempotence guard ([isShowing], and the fast path in
     * [show]). When `addView` failed it was left null, so the guard never armed and every
     * subsequent emission inflated a fresh `block_root` — the churn in the capture. It is now
     * assigned ONLY after a verified attach, and cleared by [attachWatcher] the moment the system
     * takes the window away, so "is showing" means the window exists rather than "we once tried".
     */
    private var view: View? = null

    /** Rate-limits retries after a refusal so a failure can never become a rebuild loop. */
    private val retry = BlockRetryPolicy()

    /**
     * Live layout params for the attached window, so [setKeepScreenOn] can mutate the flags and
     * re-apply them. Null whenever [view] is (they are created and discarded together).
     *
     * Retained only because the hold challenges need it — a challenge that outlasts the screen
     * timeout has to keep the display awake (D54). [OverlayController] holds its bubble's params the
     * same way and for the same kind of reason.
     */
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Whether the screen is currently being held awake. Tracked so the toggle is idempotent. */
    private var keepingScreenOn = false

    /** True while the block window is attached — verified, not assumed. */
    val isShowing: Boolean get() = view != null

    /**
     * Notices the system detaching our window underneath us.
     *
     * This is the load-bearing part of D52. On MediaTek/Chinese ROMs `addView` can SUCCEED and the
     * window still never composites — AppOps refuses the op after the fact ("Operation not
     * started") and the window is torn down. No permission query detects that:
     * `Settings.canDrawOverlays` returns true throughout. The only honest signal available is the
     * view being detached without us calling [hide], and this is where we hear about it.
     */
    private val attachWatcher = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
            // hide() clears `view` BEFORE removing, so reaching here with it still set means the
            // detach was not ours.
            if (view !== v) return
            view = null
            layoutParams = null
            keepingScreenOn = false   // the window took the flag with it
            retry.recordFailure(System.currentTimeMillis())
            Log.w(TAG, "block: WINDOW LOST — system detached it (AppOps/ROM). Treating as refused.")
            onWindowLost()
        }
    }

    /**
     * Show the block for [platform] with a [guiltLine]. Idempotent: an already-showing block only
     * has its guilt text refreshed, and a recently-refused one is not even inflated.
     *
     * @return what actually happened. The caller MUST act on a non-success — see [ShowResult].
     */
    @Suppress("UNUSED_PARAMETER") // kept for parity with the bubble + future per-platform copy
    fun show(platform: Platform, guiltLine: String): ShowResult {
        // IDEMPOTENCE FIRST, before any permission query or inflation. A block that is already up
        // must cost one field read per emission and nothing else.
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_guilt).text = guiltLine
            return ShowResult.ALREADY_SHOWING
        }

        val now = System.currentTimeMillis()
        if (!retry.mayAttempt(now)) {
            Log.d(TAG, "block: COOLING_DOWN, ${retry.remainingMs(now)}ms left; not inflating")
            return ShowResult.COOLING_DOWN
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "block: NO_PERMISSION (canDrawOverlays=false); not shown")
            return ShowResult.NO_PERMISSION
        }

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        styleFromBrand(root)   // the layout ships colourless; brand is applied here (D58)
        root.findViewById<TextView>(R.id.block_guilt).text = guiltLine
        val snooze = root.findViewById<Button>(R.id.block_snooze)
        // Formatted from the constant, never written as copy: the sentence the user reads and the
        // reprieve they are granted are then the same number by construction (D49).
        snooze.text = context.getString(R.string.block_snooze, BlockLimits.GRACE_MINUTES)
        snooze.setOnClickListener { onSnooze() }
        root.findViewById<Button>(R.id.block_exit).setOnClickListener { onExit() }

        // The physical unlock (D50/D53). Shown only when this device can run AT LEAST ONE challenge
        // — a button that cannot do its job has no business on a screen covering another app, and
        // the permission a step challenge would need cannot be requested from here anyway (no
        // Activity), so pointing at it would be a signpost rather than a fix.
        //
        // Availability is asked PER SPEC now (ChallengeAvailability), not once app-wide: the step
        // sensors are the only ones behind a runtime permission, so the old single question hid the
        // jump challenge from anyone who declined a permission jumping never needed.
        val challenge = root.findViewById<Button>(R.id.block_challenge)
        val available = ChallengeAvailability.available(context)
        if (available.isNotEmpty()) {
            // Names the REWARD; the chooser names each option's cost. Formatted from the constant
            // so the promise and the reprieve are the same number by construction (D49).
            challenge.text =
                context.getString(R.string.block_challenge, BlockLimits.CHALLENGE_GRACE_MINUTES)
            challenge.visibility = View.VISIBLE
            challenge.setOnClickListener { onOpenChooser() }
        } else {
            challenge.visibility = View.GONE
        }

        // Every panel's own way out. Exit is duplicated across all three ON PURPOSE: a chooser or
        // challenge screen offering only "Back" would be a second screen to escape before you can
        // escape, which is exactly what invariant 6 forbids.
        root.findViewById<Button>(R.id.chooser_exit).setOnClickListener { onExit() }
        root.findViewById<Button>(R.id.chooser_back).setOnClickListener { onCancelChallenge() }
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

        // Watch for the system taking the window away. Added BEFORE addView so a refusal that
        // detaches immediately is still heard.
        root.addOnAttachStateChangeListener(attachWatcher)

        val params = createLayoutParams()
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            // The honest failure: the permission query said yes and the window manager said no.
            // Record it so the next emissions are suppressed rather than inflating again — this
            // return path is exactly where the churn came from (D52).
            root.removeOnAttachStateChangeListener(attachWatcher)
            retry.recordFailure(now)
            Log.w(TAG, "block: FAILED — addView refused (canDrawOverlays said true). Cooling down.", e)
            return ShowResult.FAILED
        }

        // VERIFY, do not assume. `addView` returning without throwing is not proof the window
        // exists on a ROM that refuses the op at composition time; `isAttachedToWindow` is.
        if (!root.isAttachedToWindow) {
            root.removeOnAttachStateChangeListener(attachWatcher)
            retry.recordFailure(now)
            Log.w(TAG, "block: FAILED — addView returned but the view never attached. Cooling down.")
            return ShowResult.FAILED
        }

        root.requestFocus()
        view = root
        layoutParams = params
        keepingScreenOn = false   // matches the freshly created params; no KEEP_SCREEN_ON yet
        retry.recordSuccess()
        Log.d(TAG, "block: SHOWN on $platform")
        return ShowResult.SHOWN
    }

    /**
     * Swap the window's content to the CHOOSER, listing every challenge this device can run plus
     * "Surprise me" (D53).
     *
     * Rows are built here rather than declared in the layout because which challenges exist is
     * [ChallengeRegistry]'s business and which of them run on this device is
     * [ChallengeAvailability]'s — a row per spec in XML would be a second list to drift from those.
     * The container is cleared first so re-opening the chooser cannot stack duplicates.
     *
     * Each row names its own COST ("Jump 10 times") alongside the shared reward, which is the half
     * of D49's rule the block-panel button can no longer carry now that it does not name a specific
     * challenge.
     *
     * @param specs what to offer. Empty is a no-op — the button that opens this is already hidden in
     *   that case, so reaching here empty means availability changed underneath us.
     */
    fun showChooser(specs: List<ChallengeSpec>) {
        val root = view ?: return
        if (specs.isEmpty()) return

        val options = root.findViewById<LinearLayout>(R.id.chooser_options)
        options.removeAllViews()
        specs.forEach { spec ->
            options.addView(
                optionButton(
                    context.getString(
                        R.string.challenge_option,
                        context.getString(spec.promptRes, spec.target),
                        BlockLimits.CHALLENGE_GRACE_MINUTES,
                    ),
                ) { onChooseChallenge(spec) },
            )
        }
        // "Surprise me" last, and only when there is actually a choice to be surprised by. A single
        // available challenge makes it a second button that does the same thing as the first.
        if (specs.size > 1) {
            options.addView(
                optionButton(context.getString(R.string.challenge_surprise)) {
                    onChooseChallenge(null)
                },
            )
        }

        // Leaving a challenge for the chooser must release the screen too — this is one of the paths
        // off the challenge panel (the user tapped Back, then re-opened the chooser).
        setKeepScreenOn(false)
        showPanel(root, R.id.chooser_panel)
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
        root.findViewById<ProgressRingView>(R.id.challenge_ring)
            .setProgress(0, spec.target, spec.unit.ringLabel(0, spec.target))
        showPanel(root, R.id.challenge_panel)
        // The challenge is now the thing on screen and it may outlast the screen timeout — see
        // setKeepScreenOn. Every path off this panel turns it back off.
        setKeepScreenOn(true)
    }

    /** Back to the block itself, abandoning any chooser or challenge on screen. No-op when hidden. */
    fun showBlockPanel() {
        val root = view ?: return
        setKeepScreenOn(false)
        showPanel(root, R.id.block_panel)
    }

    /**
     * Hold the display awake, or stop holding it.
     *
     * ## Why a challenge needs this at all
     * A default Android screen timeout is THIRTY SECONDS, which is exactly the length of the hold
     * challenges. Left alone, the screen sleeps at the moment of truth, the AP can suspend, the
     * accelerometer stops delivering samples, and the hold silently stalls a second or two short of
     * completion — forever. The user is lying there holding a dead challenge with no way to know.
     * Walk and jump are shorter and involve motion, but they are covered by the same flag for free.
     *
     * Scoped to the challenge PANEL rather than the whole block on purpose: someone who walks away
     * from a blocked phone should not come back to a screen that has been lit for an hour. Turned off
     * by [showBlockPanel], [showChooser] and [hide], which between them cover every way off this
     * panel — completion, cancel, Exit, Back, and the system taking the window.
     *
     * Implemented as a flag change plus [WindowManager.updateViewLayout], which is a RELAYOUT of the
     * existing window — not an add/remove — so it costs nothing structurally and cannot churn the
     * surface (D30). Idempotent: an unchanged toggle skips the relayout entirely.
     */
    private fun setKeepScreenOn(on: Boolean) {
        if (on == keepingScreenOn) return
        val v = view ?: return
        val params = layoutParams ?: return
        keepingScreenOn = on
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed toggling KEEP_SCREEN_ON", e)
        }
    }

    /**
     * Make exactly one panel visible.
     *
     * Centralised because "exactly one" is the invariant, and three panels toggled by hand at five
     * call sites is how two end up on screen at once — or worse, how none do, leaving a black
     * rectangle over Instagram with no way out but Back. The ROOT is never touched: a GONE root
     * makes WindowManagerService free the window's surface (D30), and here it would also drop the
     * focus its Back listener depends on.
     */
    private fun showPanel(root: View, panelId: Int) {
        PANEL_IDS.forEach { id ->
            root.findViewById<View>(id).visibility =
                if (id == panelId) View.VISIBLE else View.GONE
        }
    }

    /** A chooser row, styled as a secondary action like the other cobalt buttons. */
    private fun optionButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (OPTION_GAP_DP * context.resources.displayMetrics.density).toInt() }
            text = label
            styleSecondary(this)
            setOnClickListener { onClick() }
        }

    /**
     * Paint the block window from [Brand] (D58).
     *
     * The layout deliberately carries NO colours — this window draws over another app, outside the
     * Compose theme, so `MaterialTheme` is unreachable and a second palette in XML is how the two
     * drifted before. Applied once at inflation, so there is no per-frame cost.
     *
     * ## The button hierarchy is an invariant-6 decision, not a taste one
     * Normal visual hierarchy would make "Exit" the quiet ghost button and the reward-bearing actions
     * loud. That is exactly backwards here: Exit must be the most FINDABLE control on a screen
     * covering someone else's app, and it is already first in traversal order for the same reason. So:
     *  - **Exit** — highest contrast on the ink ground (near-white fill, ink text). Impossible to miss,
     *    and it is also the healthiest choice, which an anti-doomscroll app should be nudging toward.
     *  - **Earn your way out / chooser rows** — cobalt fill. Clearly actionable, clearly secondary.
     *  - **"5 more minutes"** — the quietest: a ghost outline. It stays fully available (never
     *    disabled, never hidden) but it is the giving-in option and does not get celebrated.
     */
    private fun styleFromBrand(root: View) {
        root.setBackgroundColor(Brand.INK_BLOCK.toInt())

        listOf(R.id.block_title, R.id.chooser_title, R.id.challenge_prompt).forEach { id ->
            root.findViewById<TextView>(id).setTextColor(Brand.ON_DARK.toInt())
        }
        listOf(R.id.block_guilt, R.id.challenge_reward).forEach { id ->
            root.findViewById<TextView>(id).setTextColor(Brand.ON_DARK_MUTED.toInt())
        }

        // Exit, on every panel, gets the loudest treatment there is.
        listOf(R.id.block_exit, R.id.chooser_exit, R.id.challenge_exit).forEach { id ->
            stylePrimaryExit(root.findViewById(id))
        }
        styleSecondary(root.findViewById(R.id.block_challenge))
        listOf(R.id.block_snooze, R.id.chooser_back, R.id.challenge_cancel).forEach { id ->
            styleGhost(root.findViewById(id))
        }
    }

    /** Near-white fill, ink text. The most findable control on the screen, by design. */
    private fun stylePrimaryExit(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(Brand.ON_DARK.toInt())
        button.setTextColor(Brand.INK.toInt())
    }

    /** Cobalt fill, white text. Actionable and on-brand, but second to Exit. */
    private fun styleSecondary(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(Brand.COBALT.toInt())
        button.setTextColor(Brand.ON_DARK.toInt())
    }

    /** Transparent with muted text — present and tappable, not celebrated. */
    private fun styleGhost(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(Brand.ON_DARK_FAINT.toInt())
        button.setTextColor(Brand.ON_DARK_MUTED.toInt())
    }

    /**
     * Push live progress into the ring. Cheap; the ring skips an unchanged redraw, which now matters
     * more — a hold source samples ~5×/second and only one in five changes the displayed second.
     *
     * @param unit decides the centre label: `7 / 20` for a count, `18s` remaining for a hold.
     */
    fun renderChallengeProgress(progress: Int, target: Int, unit: ProgressUnit) {
        view?.findViewById<ProgressRingView>(R.id.challenge_ring)
            ?.setProgress(progress, target, unit.ringLabel(progress, target))
    }

    /**
     * Remove the block window if shown.
     *
     * @param reason why, for the log. The churn in the D52 capture was only diagnosable by
     *   inference because teardowns were anonymous; every hide now names its cause, so the next
     *   capture states which path is tearing the block down instead of us guessing.
     */
    fun hide(reason: String = "unspecified") {
        val v = view ?: return
        // Cleared and the watcher detached BEFORE removeView: the detach callback is about to
        // fire, and it must not be mistaken for the system taking the window away from us.
        view = null
        layoutParams = null
        // The window is going; whatever it was holding awake goes with it. Reset the flag so the
        // NEXT block starts from a known state rather than believing it is still holding the screen.
        keepingScreenOn = false
        v.removeOnAttachStateChangeListener(attachWatcher)
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        Log.d(TAG, "block: hide ($reason)")
    }

    /** Teardown for service destroy/unbind. */
    fun destroy() = hide("service destroy")

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

        /** Every panel [showPanel] arbitrates between. Exactly one is visible at any moment. */
        val PANEL_IDS = intArrayOf(R.id.block_panel, R.id.chooser_panel, R.id.challenge_panel)

        /** Gap between chooser rows, matching the 12dp the XML buttons use. */
        const val OPTION_GAP_DP = 12
    }
}
