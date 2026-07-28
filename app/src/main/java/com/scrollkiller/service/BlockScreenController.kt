package com.scrollkiller.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
 * @param onOpenChooser user asked to earn their way out (D50/D53). Opens the chooser; does NOT
 *   start anything. An ALTERNATIVE to Exit, never a replacement — every panel this class shows
 *   carries its own Exit.
 * @param onChooseChallenge user picked a specific challenge from the chooser. Null spec means
 *   "Surprise me" — the caller draws one, because which challenges are available is not this
 *   class's business.
 * @param onCancelChallenge user backed out of the challenge or the chooser, returning to the block.
 * @param onWindowLost the system took our window away without us asking (D52). The only
 *   trustworthy signal that the block is not on screen on a ROM whose permission query lies.
 * @param onWindowConfirmed the window's attach LANDED. Fires asynchronously — see [show] for why
 *   attachment cannot be judged synchronously — so the caller learns about a successful block from
 *   here rather than from [show]'s return value.
 * @param isBubbleAttached is our OTHER overlay window up right now? Purely diagnostic: the bubble
 *   is the same window type over the same app, so it is the one bit that separates "this device
 *   refuses our overlays" from "this device refuses THIS window".
 */
class BlockScreenController(
    private val context: Context,
    private val onExit: () -> Unit,
    private val onOpenChooser: () -> Unit,
    private val onChooseChallenge: (ChallengeSpec?) -> Unit,
    private val onCancelChallenge: () -> Unit,
    private val onWindowLost: () -> Unit = {},
    private val onWindowConfirmed: () -> Unit = {},
    private val isBubbleAttached: () -> Boolean = { false },
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

        /**
         * The window did not appear AND `Settings.canDrawOverlays` says no — so the plain,
         * queryable, user-fixable explanation is the true one. "Grant the permission" is useful
         * advice here and nowhere else.
         *
         * This is a CLASSIFICATION OF AN OBSERVED FAILURE, not a pre-check (D70). The permission
         * is consulted only after `addView` has already been tried and lost, purely to choose
         * which sentence the user reads. Nothing in this class asks Android for permission before
         * attempting — that prediction is what left blocking dead on a device where it was granted.
         */
        NO_PERMISSION,

        /**
         * The window did not appear even though the permission query said it should — an
         * exception from `addView`, or an attach that never landed. THE case this ROM produces.
         */
        FAILED,

        /** Suppressed by [BlockRetryPolicy] after a recent failure. Costs nothing; inflates nothing. */
        COOLING_DOWN,

        /**
         * The window was added and whether it attached is NOT YET KNOWABLE.
         *
         * ## Why a synchronous answer was never available (the D52/D70/D71 through-line)
         * `View.isAttachedToWindow()` returns `mAttachInfo != null`, and `mAttachInfo` reaches the
         * view tree in `host.dispatchAttachedToWindow(...)`, which runs inside
         * `ViewRootImpl.performTraversals()` — scheduled through the Choreographer by the
         * `requestLayout()` in `ViewRootImpl.setView()`. It is not synchronous with `addView`.
         *
         * So the check this class has made since D52 — read `isAttachedToWindow` on the statement
         * after `addView` — reads a healthy window as FAILED, roughly one frame too early. That
         * single mistake is the suspected common cause of every block failure recorded so far, and
         * it explains both shapes the bug has taken: before D71 the misjudged window was left in
         * the WindowManager, attached a frame later and became the un-dismissable full-screen trap
         * the user actually saw; after D71 it is removed a frame early and nothing appears at all.
         *
         * This value exists so the honest answer can be given. The caller must NOT treat it as
         * failure — no warning, no runtime-denied flag, no cooldown — because nothing has failed
         * yet. Resolution arrives later through [onWindowConfirmed] or [onWindowLost].
         *
         * THIS IS A HYPOTHESIS UNDER TEST, not a concluded fix. The probe logs what actually
         * happens on the device so the next session reasons from evidence instead of a fourth guess.
         */
        PENDING_ATTACH,
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

    /**
     * EVERY root this controller has handed to [WindowManager.addView] and not yet removed —
     * whether or not the attempt went on to succeed.
     *
     * ## The trap this field exists to make impossible (D71)
     * [view] is only assigned after a VERIFIED attach, which is right. But the verification path
     * used to `return FAILED` while the root was still inside the WindowManager, and nothing held
     * a reference to it any more. The result was a full-screen, OPAQUE, focusable
     * `TYPE_APPLICATION_OVERLAY` window that:
     *  - no longer answered [isShowing], so [hide] returned immediately and Exit, "5 more minutes"
     *    and the challenge button all fired their listeners and did nothing;
     *  - never reached `requestFocus`, so the old root-focused Back listener never ran either;
     *  - kept [OverlayController.isBlocking] false, so the service's surface hysteresis expired and
     *    the block outlived leaving Instagram;
     *  - outranked the launcher, so the user could not even reach their home screen;
     *  - and was joined by a SECOND one when the retry cooldown lapsed, so dismissing the tracked
     *    window merely revealed a dead one underneath.
     *
     * That is a P0 against invariant 6, and it was structural: ownership was inferred from success
     * rather than recorded on the action. So this is set the instant `addView` RETURNS — before any
     * verification, before any decision — and cleared only by [removeFromWindow], which is the one
     * place `removeView` is called. If a window exists, this points at it.
     */
    private var attachedRoot: View? = null

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

    /** Runs the attach probes. Main looper — every WindowManager touch in this class is on it. */
    private val probeHandler = Handler(Looper.getMainLooper())

    /** The platform of the in-flight attempt, for the diagnostic line. */
    private var pendingPlatform: Platform? = null

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
        /**
         * The attach LANDED. This callback was a no-op until now, which is the irony of the whole
         * saga: the correct, framework-provided answer to "did the window attach?" was already
         * wired up and being ignored in favour of reading `isAttachedToWindow` a frame too early.
         */
        override fun onViewAttachedToWindow(v: View) {
            Log.d(TAG, "block: ATTACH LANDED (listener) — the window exists")
            confirmAttached(v, "listener")
        }

        override fun onViewDetachedFromWindow(v: View) {
            // The window is gone either way, so ownership is released either way — otherwise a
            // system teardown would leave [attachedRoot] pointing at a dead view and the next
            // sweep would try to remove it twice.
            val wasPending = attachedRoot === v && view !== v
            if (attachedRoot === v) {
                attachedRoot = null
                probeHandler.removeCallbacksAndMessages(null)
            }
            if (wasPending) {
                // Detached before the attach ever resolved — the D52 shape, where `addView`
                // succeeds and the system takes the window away immediately. Reported here rather
                // than left to the deadline probe, which would find the attempt already gone and
                // say nothing at all.
                pendingPlatform = null
                retry.recordFailure(System.currentTimeMillis())
                Log.w(TAG, "block: DETACHED WHILE PENDING — the system took it before it attached.")
                onWindowLost()
                return
            }
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
    fun show(platform: Platform, guiltLine: String): ShowResult {
        // TOTALITY BOUNDARY. This method's contract is that it RETURNS a ShowResult; it must not
        // also have an exceptional exit, and until now everything before `addView` — inflation,
        // styling, findViewById, ChallengeAvailability's sensor and package queries, getString —
        // was outside any catch. One of them threw on a real device and the exception propagated
        // out through OverlayController.render, which is collected in a Flow: a throw there
        // CANCELS THE COLLECTION, so the crash did not merely log, it silently killed the count
        // collector for that surface. Two failures, one missing catch.
        //
        // Anything unexpected is a FAILED result plus a swept window, never a thrown exception.
        return try {
            showInternal(platform, guiltLine)
        } catch (e: Exception) {
            Log.e(TAG, "block: show() THREW — ${OverlayDiagnostics.cause(e)}", e)
            // Whatever went wrong, a window may already exist. Getting rid of it matters more than
            // knowing which line failed (D71).
            sweepOrphan()
            hide("show() threw")
            retry.recordFailure(System.currentTimeMillis())
            ShowResult.FAILED
        }
    }

    @Suppress("UNUSED_PARAMETER") // kept for parity with the bubble + future per-platform copy
    private fun showInternal(platform: Platform, guiltLine: String): ShowResult {
        // IDEMPOTENCE FIRST, before any permission query or inflation. A block that is already up
        // must cost one field read per emission and nothing else.
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_guilt).text = guiltLine
            return ShowResult.ALREADY_SHOWING
        }

        // An attempt whose attach has not resolved yet. Inflating a second window here is how the
        // pre-D71 build ended up stacking two full-screen overlays, so a pending attempt is left
        // alone until its probe decides.
        attachedRoot?.let {
            Log.d(TAG, "block: PENDING_ATTACH still resolving; not inflating a second window")
            return ShowResult.PENDING_ATTACH
        }

        val now = System.currentTimeMillis()
        if (!retry.mayAttempt(now)) {
            Log.d(TAG, "block: COOLING_DOWN, ${retry.remainingMs(now)}ms left; not inflating")
            return ShowResult.COOLING_DOWN
        }

        // NO PERMISSION PRE-CHECK. `Settings.canDrawOverlays` is not consulted before the attempt
        // and must not be (D70): on the ROMs this app runs on the query lies in BOTH directions,
        // and a predictive gate here is what left a device with the permission granted refusing to
        // draw the block at all. The attempt is the only honest question; the query is asked later,
        // and only to word the failure.
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_block, null) as BlockRootView
        styleFromBrand(root)   // the layout ships colourless; brand is applied here (D58)
        root.findViewById<TextView>(R.id.block_guilt).text = guiltLine
        root.findViewById<Button>(R.id.block_exit).setOnClickListener(tapListener("block.exit", onExit))

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
            challenge.setOnClickListener(tapListener("challenge.open", onOpenChooser))
        } else {
            challenge.visibility = View.GONE
        }

        // Every panel's own way out. Exit is duplicated across all three ON PURPOSE: a chooser or
        // challenge screen offering only "Back" would be a second screen to escape before you can
        // escape, which is exactly what invariant 6 forbids.
        root.findViewById<Button>(R.id.chooser_exit).setOnClickListener(tapListener("chooser.exit", onExit))
        root.findViewById<Button>(R.id.chooser_back)
            .setOnClickListener(tapListener("chooser.back", onCancelChallenge))
        root.findViewById<Button>(R.id.challenge_exit)
            .setOnClickListener(tapListener("challenge.exit", onExit))
        root.findViewById<Button>(R.id.challenge_cancel)
            .setOnClickListener(tapListener("challenge.cancel", onCancelChallenge))

        // Back = Exit, and it can't dismiss the block to reveal Instagram. Handled by
        // [BlockRootView.dispatchKeyEvent] rather than an OnKeyListener, so it works regardless of
        // which view holds focus — see that class for why the focus-dependent version was a trap.
        // Wired BEFORE addView: no window this class creates is ever on screen with Back unwired.
        root.onBack = { logTap("back") { onExit() } }
        root.isFocusableInTouchMode = true

        // Start on the block panel explicitly rather than inheriting it from the XML's initial
        // visibilities. "Exactly one panel is visible" is the invariant; asserting it in the one
        // place that also enforces it later means the starting state cannot drift out of the
        // layout file unnoticed.
        showPanel(root, R.id.block_panel)

        // Watch for the system taking the window away. Added BEFORE addView so a refusal that
        // detaches immediately is still heard.
        root.addOnAttachStateChangeListener(attachWatcher)

        val params = createLayoutParams()
        pendingPlatform = platform
        // OWNERSHIP IS RECORDED BEFORE THE CALL, not after it (D71, tightened). `addView` can in
        // principle register the view and then throw, and an ownership record that depends on a
        // clean return is exactly the reasoning that produced the trap. A spurious record on a
        // failed add is harmless — `removeView` on an unadded view only logs — while a missing one
        // is a full-screen overlay nobody can dismiss. The asymmetry decides the ordering.
        attachedRoot = root
        layoutParams = params
        try {
            // DEBUG-only injection point, compiled out of release builds (D71). See
            // [BlockFailureInjector] for why the trap needs to be reproducible on demand.
            if (BlockFailureInjector.shouldThrow()) {
                throw WindowManager.BadTokenException("forced addView failure (DEBUG injector)")
            }
            windowManager.addView(root, params)
        } catch (e: Exception) {
            // The honest failure: the window manager said no, out loud, with a type and a message.
            Log.w(TAG, "block: addView REFUSED — ${OverlayDiagnostics.cause(e)}", e)
            Log.w(TAG, diagnostics("addView-threw", params))
            removeFromWindow(root, "addView threw")
            retry.recordFailure(now)
            return classifyFailure("addView threw")
        }

        // DO NOT JUDGE THE ATTACH HERE. See ShowResult.PENDING_ATTACH: `isAttachedToWindow` cannot
        // be true yet, because the flag is set in performTraversals on a later frame. The
        // synchronous reading is logged only as evidence for the hypothesis under test — if it is
        // false here and true a frame later, every "the ROM is refusing" verdict this project has
        // recorded was a misread of our own timing.
        val syncAttached = root.isAttachedToWindow
        Log.d(
            TAG,
            "block: addView returned; attachedSync=$syncAttached " +
                "(expected false if the premature-check hypothesis holds) " +
                "injector=${BlockFailureInjector.describe()}",
        )
        scheduleAttachProbes(root, params)
        return ShowResult.PENDING_ATTACH
    }

    /**
     * Watch for the attach landing, and give up on it at a deadline.
     *
     * THREE observation points, deliberately, because the point of this build is to find out which
     * one is telling the truth:
     *  - [attachWatcher]'s `onViewAttachedToWindow`, the framework's own callback and the earliest
     *    honest answer;
     *  - the next frame via [View.post], which runs after the traversal that sets `mAttachInfo`, so
     *    it is where a healthy window MUST read attached;
     *  - a [BlockLimits.ATTACH_DEADLINE_MS] backstop that decides the failure if neither fired.
     *
     * The window stays on screen for that window of time with [view] unset — which was the trap
     * state before D71 and is safe now precisely because of it: [attachedRoot] owns it, every
     * button and Back were wired before `addView`, `hide()` is unconditional, and `sweepOrphan()`
     * runs on leaving the app. Invariant 6 holds throughout the pending period, which is the only
     * reason this probe is allowed to exist at all.
     */
    private fun scheduleAttachProbes(root: View, params: WindowManager.LayoutParams) {
        root.post {
            if (attachedRoot !== root) return@post          // already resolved or torn down
            val attached = root.isAttachedToWindow && !BlockFailureInjector.shouldFakeNoAttach()
            Log.d(TAG, "block: PROBE next-frame attached=$attached")
            if (attached) confirmAttached(root, "next-frame")
        }
        probeHandler.postDelayed(
            {
                if (attachedRoot !== root || view === root) return@postDelayed
                // The injector's NO_ATTACH mode lies here rather than at addView, so the forced
                // failure now exercises the REAL failure path — probes fire, deadline expires,
                // window is removed — instead of a shortcut that no longer exists (D71).
                val attached = root.isAttachedToWindow && !BlockFailureInjector.shouldFakeNoAttach()
                Log.w(TAG, "block: PROBE deadline attached=$attached")
                if (attached) {
                    confirmAttached(root, "deadline")
                } else {
                    // NOW it is a genuine refusal: the window has had frames to attach and did not.
                    Log.w(TAG, diagnostics("no-attach-by-deadline", params))
                    removeFromWindow(root, "attach never landed")
                    retry.recordFailure(System.currentTimeMillis())
                    val result = classifyFailure("no attach after ${BlockLimits.ATTACH_DEADLINE_MS}ms")
                    Log.w(TAG, "block: resolved PENDING_ATTACH → $result")
                    onWindowLost()
                }
            },
            BlockLimits.ATTACH_DEADLINE_MS,
        )
    }

    /**
     * The attach landed: promote the pending window to the tracked one.
     *
     * Idempotent — it can arrive from the listener, the next-frame post or the deadline, and
     * whichever is first wins. Everything [showInternal] used to do on its synchronous success
     * path happens here instead, because here is where success is actually known.
     */
    private fun confirmAttached(root: View, via: String) {
        if (attachedRoot !== root || view === root) return
        probeHandler.removeCallbacksAndMessages(null)
        root.requestFocus()
        view = root
        keepingScreenOn = false   // matches the freshly created params; no KEEP_SCREEN_ON yet
        retry.recordSuccess()
        Log.d(
            TAG,
            "block: SHOWN on $pendingPlatform via=$via " +
                "(focused=${root.isFocused} bubbleAttached=${isBubbleAttached()})",
        )
        onWindowConfirmed()
    }

    /** The full state block for a failure. See [OverlayDiagnostics] for why it prints everything. */
    private fun diagnostics(stage: String, params: WindowManager.LayoutParams): String =
        OverlayDiagnostics.state(context, stage, params, isBubbleAttached(), pendingPlatform)

    /**
     * Word an ALREADY-OBSERVED failure for the user.
     *
     * The permission query is asked HERE and only here — after the attempt has been made and lost —
     * because the two failures need completely different advice: [ShowResult.NO_PERMISSION] means
     * "grant it and this works", [ShowResult.FAILED] means "your settings already say granted and
     * the system is refusing anyway". Getting that backwards sends someone to a settings screen
     * whose switch is already on.
     *
     * What it must never be is a gate. See [ShowResult.NO_PERMISSION] and D70.
     */
    private fun classifyFailure(stage: String): ShowResult =
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "block: NO_PERMISSION — $stage, and canDrawOverlays=false. Honest gap.")
            ShowResult.NO_PERMISSION
        } else {
            Log.w(TAG, "block: FAILED — $stage while canDrawOverlays=true. The ROM is refusing.")
            ShowResult.FAILED
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
                    label = context.getString(
                        R.string.challenge_option,
                        context.getString(spec.promptRes, spec.target),
                        BlockLimits.CHALLENGE_GRACE_MINUTES,
                    ),
                    // The spec id, not the label: the log stays greppable and stable when the copy
                    // changes or the pack is translated.
                    name = "chooser.option[${spec.id}]",
                ) { onChooseChallenge(spec) },
            )
        }
        // "Surprise me" last, and only when there is actually a choice to be surprised by. A single
        // available challenge makes it a second button that does the same thing as the first.
        if (specs.size > 1) {
            options.addView(
                optionButton(
                    label = context.getString(R.string.challenge_surprise),
                    name = "chooser.option[surprise]",
                ) { onChooseChallenge(null) },
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
    private fun optionButton(label: String, name: String, onClick: () -> Unit): Button =
        Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (OPTION_GAP_DP * context.resources.displayMetrics.density).toInt() }
            text = label
            styleSecondary(this)
            setOnClickListener(tapListener(name, onClick))
        }

    /**
     * Every control on this window goes through here, and that is a debugging requirement rather
     * than tidiness (D71).
     *
     * When the block trapped a user, the single most expensive unknown was whether their taps were
     * REACHING the buttons at all. "Exit does nothing" has two completely different causes — the
     * touch never arrived (wrong window flags, a view on top, an unowned window nobody is
     * listening to) or the handler ran and its work was a no-op — and they need opposite fixes.
     * Without a log at the listener there is no way to tell them apart from the outside, and the
     * device is the only place the bug reproduces.
     *
     * So each tap prints TWICE: once on entry, and once after the handler with the state that
     * decides whether it actually did anything. A tap that never registered prints nothing; a tap
     * that fired but failed prints a pair whose second line still says `showing=true`.
     */
    private fun tapListener(name: String, onClick: () -> Unit) =
        View.OnClickListener { logTap(name, onClick) }

    /** [tapListener]'s body, also used for the hardware Back key (which has no OnClickListener). */
    private fun logTap(name: String, action: () -> Unit) {
        Log.d(TAG, "block: TAP $name (showing=$isShowing)")
        try {
            action()
        } catch (e: Exception) {
            // A throwing handler must never be the reason a block stays up. Report it and let the
            // other exits — Back, the other panels' Exit, leaving the app — still work.
            Log.e(TAG, "block: TAP $name → THREW; the window may still be up", e)
            return
        }
        Log.d(TAG, "block: TAP $name → done (showing=$isShowing, window=${attachedRoot != null})")
    }

    /**
     * The ONE place `removeView` is called, so ownership can only be released by an actual removal.
     *
     * Idempotent by construction: clearing [attachedRoot] first means a second call for the same
     * view is a no-op on the field, and `removeView` on an already-removed view only logs.
     */
    private fun removeFromWindow(v: View, reason: String) {
        if (attachedRoot === v) {
            attachedRoot = null
            pendingPlatform = null
            // A probe for a window that no longer exists would resolve a stale attempt.
            probeHandler.removeCallbacksAndMessages(null)
        }
        v.removeOnAttachStateChangeListener(attachWatcher)
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "block: removeView failed ($reason)", e)
        }
    }

    /**
     * Remove any block window this controller owns but is no longer tracking.
     *
     * The belt to [removeFromWindow]'s braces. Every failure path now cleans up after itself, so in
     * a correct build this finds nothing — which is exactly why it is called from the paths that
     * run when the user leaves the tracked app ([OverlayController.offSurface]) and when the
     * service dies. If a future edit ever reintroduces an early return that skips the removal, the
     * window dies on leaving Instagram instead of covering the launcher indefinitely, and this logs
     * loudly enough to find it.
     */
    fun sweepOrphan() {
        val orphan = attachedRoot ?: return
        if (orphan === view) return   // tracked and healthy; not an orphan
        Log.w(TAG, "block: SWEEP — an unowned block window existed; removing it")
        removeFromWindow(orphan, "orphan sweep")
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
     *  - **Back / Cancel** — the quietest: a ghost outline. They return to a previous panel rather
     *    than resolving anything, so they stay fully available and uncelebrated.
     *
     * The ghost tier used to have a third member, the free "5 more minutes" button — the giving-in
     * option, deliberately the quietest thing on the screen. It was removed outright at D74; what
     * remains of that reasoning is that Exit stays loudest, which was never about the snooze.
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
        listOf(R.id.chooser_back, R.id.challenge_cancel).forEach { id ->
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
        val tracked = view
        val owned = attachedRoot
        if (tracked == null && owned == null) return

        // Any in-flight attach probe is about to be resolving a window that is being torn down.
        probeHandler.removeCallbacksAndMessages(null)
        pendingPlatform = null

        // Cleared BEFORE removeView: the detach callback is about to fire, and it must not be
        // mistaken for the system taking the window away from us.
        view = null
        layoutParams = null
        // The window is going; whatever it was holding awake goes with it. Reset the flag so the
        // NEXT block starts from a known state rather than believing it is still holding the screen.
        keepingScreenOn = false

        tracked?.let { removeFromWindow(it, reason) }
        // UNCONDITIONAL, and not guarded on `view` being set (D71). hide() used to open with
        // `val v = view ?: return`, which meant that once a window went untracked every dismissal
        // path — Exit, Back, challenge completion, leaving the app — silently did nothing
        // while the window stayed on screen. A teardown must be able to tear down whatever exists,
        // not only what it expected to exist.
        if (owned != null && owned !== tracked) {
            Log.w(TAG, "block: hide found an untracked window; removing it too")
            removeFromWindow(owned, "$reason (untracked)")
        }
        Log.d(TAG, "block: hide ($reason)")
    }

    /** Teardown for service destroy/unbind. Sweeps first: nothing may outlive the service. */
    fun destroy() {
        sweepOrphan()
        hide("service destroy")
    }

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
