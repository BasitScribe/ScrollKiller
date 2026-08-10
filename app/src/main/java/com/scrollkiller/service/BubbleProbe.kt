package com.scrollkiller.service

import android.util.Log
import com.scrollkiller.BuildConfig
import com.scrollkiller.brain.BrainState

/**
 * DEBUG-only: force the bubble's mascot state, fire a guilt-line reveal on demand, and dump what the
 * bubble currently believes about itself (D86).
 *
 * ## Why this exists, and it is not "for convenience"
 * The mascot changes at exactly two counts — 50 and 150 (`BrainState`) — and the app is deliberately
 * SILENT below 50 (D41), so no guilt line and therefore no reveal animation can fire before then.
 * That means the honest way to see either of the things D86 added is to scroll fifty reels, and then
 * a hundred more. Nobody is going to do that repeatedly, which in practice means nobody checks, which
 * is how "the mascot never changes" and "there are no animations" become reports that are impossible
 * to tell apart from "I was running the previous build".
 *
 * That ambiguity is the actual problem this file solves. [report] prints the internal state as
 * FACTS — the count, the state derived from it, the drawable currently on the ImageView, whether a
 * nudge is running, whether the bubble is even shown — so "it is not changing" becomes either "it is
 * already CRACKING and the art is `mascot_cracking_bubble`, so this is the wrong drawable" or "the
 * count is under fifty, so it is correctly HEALTHY". Those need completely different fixes and
 * without this they look identical from outside.
 *
 * Same posture as [BlockFailureInjector] and [SurfaceDiagnostics]: gated on [BuildConfig.DEBUG] at
 * every entry point, so a release build compiles the behaviour out and a shipped app has no way to
 * reach it. In-memory and never persisted — a forced state must not outlive the process and follow
 * somebody into a session they did not arm it for.
 *
 * Drive it over adb, in the same shape as the `DIAG_LABEL` tour stamp:
 * ```
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BUBBLE --es state cracking
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BUBBLE --es state fried
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BUBBLE --es state off
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BUBBLE --es reveal pop
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BUBBLE --es report on
 * ```
 * The state override changes what the bubble DRAWS and nothing else — it does not touch the count,
 * Room, the guilt cadence or the block decision. It is a lens on the render path, not a debug mode
 * for the app, and keeping it that narrow is what makes it safe to leave in the debug build.
 */
internal object BubbleProbe {

    private const val TAG = "ScrollKiller"

    /** Forced mascot state, or null for "use the real count". DEBUG-only; never persisted. */
    @Volatile
    private var forced: BrainState? = null

    /** Reveal style to use on the next forced nudge, or null to let [BubbleMotion] choose. */
    @Volatile
    private var forcedReveal: BubbleMotion.Reveal? = null

    /** Set by [ReelScrollAccessibilityService] so a probe broadcast can reach the live bubble. */
    @Volatile
    private var controller: OverlayController? = null

    /** True when a probe has overridden the state, so [stateFor] is worth consulting at all. */
    val isArmed: Boolean get() = BuildConfig.DEBUG && forced != null

    fun attach(target: OverlayController?) {
        if (!BuildConfig.DEBUG) return
        controller = target
    }

    /**
     * The state the bubble should draw for [count] — the forced one if a probe armed it, otherwise
     * the real answer.
     *
     * Routed through here rather than branching at the call site so that in a RELEASE build this is
     * `BrainState.forCount(count)` and nothing else, and the override cannot exist even as a
     * reachable branch.
     */
    fun stateFor(count: Int): BrainState {
        if (!BuildConfig.DEBUG) return BrainState.forCount(count)
        return forced ?: BrainState.forCount(count)
    }

    /** The reveal a forced nudge should use, or null to let the normal selection run. */
    fun revealOverride(): BubbleMotion.Reveal? = if (BuildConfig.DEBUG) forcedReveal else null

    /**
     * Handle one probe broadcast. Unknown values are logged rather than ignored — a typo'd adb
     * command that silently does nothing is worse than no probe at all, because it reads as the
     * feature being broken.
     */
    fun handle(state: String?, reveal: String?, report: String?) {
        if (!BuildConfig.DEBUG) return

        state?.let { raw ->
            forced = when (raw.lowercase()) {
                "healthy" -> BrainState.HEALTHY
                "cracking" -> BrainState.CRACKING
                "fried" -> BrainState.FRIED
                "off", "auto", "clear" -> null
                else -> {
                    Log.w(TAG, "BUBBLE probe: unknown state '$raw' (healthy|cracking|fried|off)")
                    return@let
                }
            }
            Log.i(TAG, "BUBBLE probe: state forced to ${forced ?: "AUTO (real count)"}")
            controller?.probeRefresh()
        }

        reveal?.let { raw ->
            forcedReveal = when (raw.lowercase()) {
                "fade" -> BubbleMotion.Reveal.FADE
                "rise" -> BubbleMotion.Reveal.RISE
                "pop" -> BubbleMotion.Reveal.POP
                "sweep" -> BubbleMotion.Reveal.SWEEP
                "auto", "off", "clear" -> null
                else -> {
                    Log.w(TAG, "BUBBLE probe: unknown reveal '$raw' (fade|rise|pop|sweep|auto)")
                    return@let
                }
            }
            Log.i(TAG, "BUBBLE probe: reveal = ${forcedReveal ?: "AUTO (tier + line)"}, firing one")
            controller?.probeNudge()
        }

        if (report != null) controller?.probeReport()
    }

    /**
     * One greppable block naming everything that decides what the bubble looks like right now.
     *
     * Deliberately a single multi-line log rather than several calls: the whole point is to be able
     * to paste ONE thing into a bug report, in the same way [OverlayDiagnostics] prints one state
     * block on a genuine block refusal rather than scattering fields across the transcript.
     */
    fun report(
        count: Int,
        derived: BrainState,
        drawn: BrainState,
        artRes: Int,
        artName: String,
        bubbleShown: Boolean,
        nudging: Boolean,
        expanded: Boolean,
        animatorScale: Float,
        animatorAlpha: Float,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            """
            |BUBBLE REPORT
            |  count=$count
            |  BrainState.forCount(count)=$derived   (flips at ${BrainState.CRACKING_AT} and ${BrainState.FRIED_AT})
            |  drawn=$drawn ${if (isArmed) "<- FORCED BY PROBE" else ""}
            |  art=$artName (res=$artRes)
            |  bubbleShown=$bubbleShown  nudging=$nudging  expanded=$expanded
            |  headline alpha=$animatorAlpha scale=$animatorScale  (both should be 1.0 at rest)
            |  motion budget=${BubbleMotion.revealBudgetMs}ms  mascot swap=${BubbleMotion.mascotSwapMs}ms
            |  ⚑ if drawn matches BrainState.forCount and you expected a change, the COUNT is the
            |    thing that has not moved — the mascot only changes at ${BrainState.CRACKING_AT} and ${BrainState.FRIED_AT}.
            |  ⚑ if alpha or scale is not 1.0 with nudging=false, a settle() was missed.
            """.trimMargin(),
        )
    }
}
