package com.scrollkiller.service

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.brain.MascotArt
import com.scrollkiller.data.TodaySummary
import com.scrollkiller.stats.TimeEstimate
import com.scrollkiller.ui.theme.Brand

/**
 * The overlay bubble's view tree: a compact `[mascot] 42` pill that expands IN PLACE into a
 * small breakdown panel when tapped (D37). Built in code, not XML, because it is created by
 * [OverlayController] for a WindowManager window rather than inflated into an Activity.
 *
 * ## Compact vs expanded, and why it is ONE window
 * Compact shows the mascot and the GRAND TOTAL and nothing else (D35 for the total; D37
 * dropped the `IG 30 · YT 10` subtitle line, which is now the panel's bars). Expanded reveals
 * [panel] underneath: today's total + estimated time, then one bar per platform with a count.
 *
 * Expanding does NOT open a second window, and does not open the app. It toggles a CHILD's
 * visibility inside the window that is already attached. That distinction is the whole of D30:
 * WindowManagerService frees a window's surface when its ROOT view goes [View.GONE], so the
 * root here stays [View.VISIBLE] for the service's lifetime and only [panel] — a child — is
 * toggled. A child going GONE re-measures the window (WRAP_CONTENT), which is a RESIZE of the
 * existing surface, exactly like a digit-count change already was. It is not a
 * construct/destruct pair, and it must not be "simplified" into add/removeView.
 *
 * ## Why the mascot is an ImageView now, not a compound drawable
 * It used to be a start-side compound drawable on a lone TextView, chosen (D36) to keep the
 * overlay a single view. That is what made the mascot look small: a compound drawable draws at
 * its INTRINSIC size inside a box the TEXT's height defines, so the art could never be sized
 * to the bubble. The panel needs a ViewGroup regardless, and once there is one, an ImageView
 * bounded to [MascotArt.BUBBLE_DP] tall is both simpler and correct. The single-WINDOW property
 * — the one D30 actually protects — is untouched; "single view" never was the invariant.
 *
 * Threading: constructed and mutated only from the AccessibilityService's main thread.
 */
class BubbleView(context: Context) : LinearLayout(context) {

    private val mascot: ImageView
    private val headline: TextView
    private val panel: LinearLayout
    private val panelLine: TextView
    private val panelTotal: TextView
    private val rows: List<BarRow>

    /** True while the breakdown panel is showing. Toggled by [setExpanded]. */
    var isExpanded: Boolean = false
        private set

    /**
     * Called when the display configuration changes under this window — rotation above all,
     * but also a foldable opening or a multi-window resize.
     *
     * The overlay's own window does not reposition itself for any of those: it carries
     * [android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS], so a pill that was near
     * the bottom of a portrait screen is simply off the bottom of the landscape one. The view is
     * where the callback has to originate, because [OverlayController] is not a Context owner
     * with a configuration of its own — it drives a window, and only the window's view is told.
     * See D38.
     */
    var onDisplayConfigChanged: (() -> Unit)? = null

    /**
     * Drawable res id currently on [mascot], so the art is only swapped when the state
     * actually changes (at most twice a day — see [OverlayController.maybeNudge]). 0 = unset.
     */
    private var lastArt = 0

    /**
     * The motion layer. Everything it does is applied AFTER the content it decorates is already
     * correct, so a cancelled or system-disabled animation costs nothing but the flourish — see
     * [BubbleAnimator]'s class doc.
     */
    private val animator = BubbleAnimator(context.resources.displayMetrics.density)

    /** Accent currently on the pill's background, or null before the first tint. */
    private var lastAccent: Int? = null

    /**
     * The running tint crossfade, if any.
     *
     * Held because a `ValueAnimator` is NOT a view property animation: `settle()` cancels
     * `view.animate()` and knows nothing about this one, so without a reference an abandoned
     * crossfade would keep writing colours into a drawable belonging to a bubble that has since
     * been hidden or re-rendered — the same class of leak `settle` exists to prevent, arriving
     * through the one door it cannot see.
     */
    private var tintAnimator: android.animation.ValueAnimator? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.overlay_bubble_bg)
        val padH = dp(PAD_H_DP)
        val padV = dp(PAD_V_DP)
        setPadding(padH, padV, padH, padV)
        // The bubble must not generate its own window/content accessibility events — that fed
        // a show/hide flicker (D16) — and a decorative counter needn't be announced. HIDE_
        // DESCENDANTS, not NO: this is a ViewGroup now, and NO alone does not cover children.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        // --- header: always visible, and the compact bubble in its entirety ---
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mascot = ImageView(context).apply {
            // Height-bounded to the pre-scaled bubble family's own size, with the width
            // following the art's aspect (the masters are trimmed to the character, so they
            // are taller than wide — see tools/mascot_import.py). At this exact height the
            // bitmap for the device's density is already the right size, so FIT_CENTER does
            // no actual scaling; it is here so a missing density bucket degrades to a clean
            // aspect-preserving fit instead of a stretch.
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(MascotArt.BUBBLE_DP))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        headline = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(GAP_DP) }
            setTextColor(TEXT_COLOR)      // white stays legible over the accent-tinted pill
            textSize = HEADLINE_SP
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(mascot)
        header.addView(headline)
        addView(header)

        // --- panel: GONE until tapped ---
        panel = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            minimumWidth = dp(PANEL_MIN_WIDTH_DP)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(PANEL_TOP_MARGIN_DP) }
        }
        // The guilt line, at the TOP of the panel — above the numbers, because the line is what
        // the app is saying and the bars are the evidence for it. Same pinned line Home's header
        // and the compact nudge show at this moment (D41): they all read GuiltLines.current().
        // GONE below the tier threshold, which is most of the day and is the designed silence.
        panelLine = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(PANEL_LINE_MARGIN_DP) }
            visibility = GONE
            setTextColor(TEXT_COLOR)     // full white: this is the message, not a label
            textSize = PANEL_LINE_SP
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panelTotal = TextView(context).apply {
            setTextColor(PANEL_TEXT_COLOR)
            textSize = PANEL_TEXT_SP
        }
        panel.addView(panelLine)
        panel.addView(panelTotal)
        // One row per platform, built ONCE and then shown/hidden per emission. Building rows
        // on the fly would allocate views inside someone's doomscroll session for no reason —
        // there are five platforms at most, so the whole set is cheaper than the churn.
        rows = Platform.entries.map { BarRow(context).also { row -> panel.addView(row.view) } }
        addView(panel)
    }

    /**
     * Show today's numbers. Cheap by construction — called only on a distinct change of
     * [TodaySummary], and the art is guarded by [lastArt].
     *
     * @param headlineText what the compact line reads, or NULL to leave it exactly as it is.
     *   Null is how a count arriving mid-nudge updates the tint, the mascot and the panel
     *   without wiping the guilt line off the screen a few hundred ms after it appeared — which
     *   is precisely when it is most likely, since the tier flip happens *because* the count
     *   moved (D33). [OverlayController] owns that decision, so it is a parameter here.
     * @param guiltLine the app's current line for the panel, or NULL when the count is below the
     *   first tier and the app is deliberately silent (D41). Unlike [headlineText], null here
     *   means HIDE, not "leave alone": the panel is not competing with a timed nudge, so there
     *   is no state of it worth preserving.
     */
    fun render(
        summary: TodaySummary,
        state: BrainState,
        headlineText: CharSequence?,
        guiltLine: CharSequence?,
        reveal: BubbleMotion.Reveal? = null,
    ) {
        if (headlineText != null) headline.text = headlineText
        // Order matters: setAccent reads `lastArt` to decide whether this is a state CHANGE, so it
        // has to run before setMascot updates it. They are two halves of one transition and the
        // whole point of the pairing is that they start on the same frame.
        setAccent(state)
        setMascot(state)
        renderPanel(summary, guiltLine)
        // Ordering is load-bearing: the text is set above, so the reveal decorates content that is
        // ALREADY correct and can be cancelled at any frame without ever showing a stale line.
        // Null means "no ceremony" — an ordinary count tick, which is most emissions.
        if (reveal != null && headlineText != null) animator.revealLine(headline, reveal)
    }

    /**
     * Expand or collapse the breakdown panel. Idempotent, and a no-op relayout is skipped so
     * a repeated call doesn't re-measure the window.
     *
     * Collapsing when there is nothing to show is deliberate on the caller's side, not here:
     * [OverlayController] refuses to expand into an empty panel.
     */
    fun setExpanded(expanded: Boolean) {
        if (expanded == isExpanded) return
        isExpanded = expanded
        panel.visibility = if (expanded) VISIBLE else GONE
        // The visibility flip above is the ONE window resize (D37). The animation below moves only
        // the panel's contents inside a box that has already reached its final size — see
        // BubbleAnimator.openPanel for why animating the box itself would be D30's churn per frame.
        if (expanded) animator.openPanel(panel) else animator.settle(panel)
    }

    /** Acknowledge a tap on the pill, including the taps that correctly open nothing. */
    fun acknowledgeTap() {
        animator.acknowledgeTap(this)
    }

    /**
     * The headline's live scale/alpha, for [BubbleProbe]'s state dump ONLY.
     *
     * These read the two properties a missed `settle()` would leave wrong. Anything other than 1.0
     * here while nothing is animating means an animator was cancelled without its rest state being
     * restored — which on this window is permanent, and is the defect class D86 built `settle` to
     * make impossible. Exposed as named probe accessors rather than by widening `headline`'s
     * visibility, so it stays obvious that nothing in production reads them.
     */
    fun headlineScaleForProbe(): Float = headline.scaleX

    fun headlineAlphaForProbe(): Float = headline.alpha

    /**
     * Cancel every running animation and snap the tree back to rest.
     *
     * Called whenever the bubble's state changes out from under the motion — a nudge collapsing (the
     * common case at a high count, where the next line can fire while the last reveal is still in
     * flight), leaving the reel surface, the block coming up, the bubble being switched off, and
     * teardown.
     *
     * ⚑ **`this` is deliberately NOT in the list.** The root's alpha is
     * [OverlayController.setBubbleShown]'s hide-without-churn mechanism (D30), and settling the
     * root would fight it — snapping a hidden bubble back to fully visible over somebody's video.
     * Only children are settled, and a child's only correct opacity is 1.
     */
    fun settleMotion() {
        animator.settle(headline, mascot, panel)
        // The tint crossfade is a ValueAnimator, so `animator.settle` cannot see it — it only
        // cancels view-property animations. Cancelled and then SNAPPED to its target rather than
        // left wherever it stopped: an abandoned crossfade would leave the pill a colour that
        // belongs to no BrainState at all, which is worse than either endpoint.
        tintAnimator?.cancel()
        tintAnimator = null
        lastAccent?.let { background?.mutate()?.setTint(it) }
    }

    /** Cap the compact line's width so a guilt line wraps instead of spanning the screen. */
    fun setHeadlineMaxWidth(px: Int) {
        headline.maxWidth = px
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onDisplayConfigChanged?.invoke()
    }

    /** True when there is at least one platform to draw a bar for. */
    fun hasBreakdown(summary: TodaySummary): Boolean = BubbleBreakdown.bars(summary).isNotEmpty()

    /**
     * Fill the panel from [summary]: the total + estimated time line, then one visible bar per
     * platform with a count today, longest first. Rows beyond that are hidden, never removed.
     *
     * Rendered even while collapsed (the panel is GONE, so this costs a few text sets and no
     * layout). That keeps the panel correct the instant it is expanded, instead of showing one
     * stale frame until the next count arrives.
     */
    private fun renderPanel(summary: TodaySummary, guiltLine: CharSequence?) {
        val bars = BubbleBreakdown.bars(summary)
        panelLine.visibility = if (guiltLine.isNullOrBlank()) GONE else VISIBLE
        if (!guiltLine.isNullOrBlank()) panelLine.text = guiltLine
        panelTotal.text = context.getString(
            R.string.overlay_panel_total,
            summary.total,
            TimeEstimate.minutesLabel(summary.total),
        )
        rows.forEachIndexed { index, row ->
            val bar = bars.getOrNull(index)
            if (bar == null) row.hide() else row.bind(bar)
        }
    }

    /**
     * Swap the mascot art for [state], only when it actually changed.
     *
     * ## The FIRST art is set instantly; only CHANGES are animated
     * `lastArt == 0` means this bubble has never drawn a mascot — it is being populated for its
     * first frame, not reacting to anything. Animating that would mean the pill visibly assembles
     * itself every time the user walks into Reels, which is several times an hour and is not a
     * moment worth marking. A state CHANGE is: it happens at most twice a day (`BrainState` flips
     * at 50 and at 150), it is the app's own escalation becoming visible, and it is the only thing
     * on this surface that has genuinely earned a transition.
     */
    /**
     * Move the pill's accent to [state]'s — instantly on the first draw, animated on a change.
     *
     * ## The bug this fixes, because it is worth naming
     * This used to be `background?.mutate()?.setTint(...)` on every render: correct, and it SNAPPED.
     * Once D86 animated the mascot, the two halves of one transition disagreed — the colour flipped
     * on a single frame while the character was still mid-dip, so the eye caught the instant change,
     * concluded the state had already flipped, and then watched the mascot arrive a third of a
     * second late. **Animating one part of a composite and leaving the rest instant is worse than
     * animating none of it**, because the mismatch reads as a glitch rather than as a missing
     * feature. The crossfade runs for exactly [BubbleMotion.tintCrossfadeMs], which is derived from
     * the mascot swap so the two cannot drift apart.
     *
     * `mutate()` is called ONCE here rather than on every render: it is what stops this instance's
     * tint reaching the shared drawable constant, and calling it repeatedly allocates a fresh
     * constant state on a path that runs per scroll.
     */
    private fun setAccent(state: BrainState) {
        val target = state.accentArgb.toInt()
        if (target == lastAccent) return
        val previous = lastAccent
        lastAccent = target
        val drawable = background?.mutate() ?: return
        tintAnimator?.cancel()
        // First draw (or a bubble that has never been tinted): no previous colour to travel FROM,
        // so animating would mean inventing a starting point. Same rule as the mascot's first art —
        // a bubble being populated is not a bubble reacting.
        if (previous == null || lastArt == 0) {
            drawable.setTint(target)
            return
        }
        tintAnimator = animator.crossfadeTint(this, previous, target).also { it.start() }
    }

    private fun setMascot(state: BrainState) {
        val art = MascotArt.bubble(state)
        if (art == lastArt) return
        val isFirstDraw = lastArt == 0
        lastArt = art
        if (isFirstDraw) mascot.setImageResource(art) else animator.swapMascot(mascot, art)
    }

    /**
     * One `[icon] Instagram ▓▓▓░░ 30` row: a generic glyph, the platform's NAME, a proportional
     * bar, and the count.
     *
     * ## Icon + name, and why not a logo (D40, revised by D44)
     * D40 replaced the old "IG"/"YT" initials with a glyph, correctly: two characters at 11sp in
     * translucent white over a moving video were the least legible thing in the panel. But a
     * generic camera does not say *Instagram* — it says "some video app" — so the row lost the
     * one thing it was there to tell you.
     *
     * The obvious fix is the platform's real logo, and we are deliberately not doing that. The
     * panel draws INSIDE those apps' own UI, and reproducing a mark there is a trademark
     * question with no upside. Naming an app is nominative use and is not the same thing. So the
     * row carries [PlatformSpec.brandName] as TEXT beside the generic glyph, which is both
     * unambiguous and safe. [PlatformSpec.iconRes] keeps its slot for the day licensed marks
     * arrive — that is a drawable swap with no code change.
     *
     * The label column is a fixed width, so the bars still line up down the panel however long
     * a future platform's name is; the name ellipsises rather than pushing the bar around.
     *
     * The bar is drawn with LAYOUT WEIGHTS rather than a pixel width, because a pixel width
     * would need the track's measured size — which is not known when [bind] runs. Two weighted
     * children splitting a fixed weightSum express "this fraction of the track" directly and
     * survive the window resizing around them.
     */
    private inner class BarRow(context: Context) {
        val view: LinearLayout
        private val icon: ImageView
        private val name: TextView
        private val fill: View
        private val rest: View
        private val count: TextView

        /** Icon res currently set, so [bind] only touches the drawable when it changed. */
        private var lastIcon = 0

        init {
            icon = ImageView(context).apply {
                layoutParams = LayoutParams(dp(BAR_ICON_DP), dp(BAR_ICON_DP))
                    .apply { marginEnd = dp(BAR_ICON_GAP_DP) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                // Tinted rather than shipped coloured: the glyph then recedes behind the count
                // at exactly the same weight the "IG"/"YT" text did, and one drawable serves
                // whatever the panel's palette becomes.
                imageTintList = ColorStateList.valueOf(PANEL_TEXT_COLOR)
            }
            name = TextView(context).apply {
                // Fixed width, not WRAP_CONTENT: the bars must start at the same x on every row
                // or the panel reads as a ragged list rather than a chart. A longer name
                // ellipsises instead of shoving its own bar right.
                layoutParams = LayoutParams(dp(BAR_NAME_WIDTH_DP), LayoutParams.WRAP_CONTENT)
                setTextColor(PANEL_TEXT_COLOR)
                textSize = PANEL_TEXT_SP
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            fill = View(context).apply {
                setBackgroundResource(R.drawable.overlay_bar_fill)
                layoutParams = LayoutParams(0, dp(BAR_HEIGHT_DP))
            }
            rest = View(context).apply {
                layoutParams = LayoutParams(0, dp(BAR_HEIGHT_DP))
            }
            val track = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setBackgroundResource(R.drawable.overlay_bar_track)
                weightSum = 1f
                layoutParams = LayoutParams(0, dp(BAR_HEIGHT_DP), 1f).apply {
                    marginStart = dp(BAR_GAP_DP)
                    marginEnd = dp(BAR_GAP_DP)
                }
                addView(fill)
                addView(rest)
            }
            count = TextView(context).apply {
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                minWidth = dp(BAR_COUNT_WIDTH_DP)
                gravity = Gravity.END
                setTextColor(TEXT_COLOR)
                textSize = PANEL_TEXT_SP
            }
            view = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(BAR_ROW_MARGIN_DP) }
                addView(icon)
                addView(name)
                addView(track)
                addView(count)
            }
        }

        fun bind(bar: BreakdownBar) {
            setIcon(bar.platform)
            name.text = PlatformRegistry.specOrNull(bar.platform)
                ?.brandName
                ?.takeIf { it.isNotBlank() }
                ?: bar.platform.id
            count.text = bar.count.toString()
            // A bar at share 1.0 leaves `rest` at weight 0, i.e. zero width — correct, and the
            // reason the fill/rest split is expressed as a fraction of weightSum rather than
            // as two independent weights.
            (fill.layoutParams as LayoutParams).weight = bar.share
            (rest.layoutParams as LayoutParams).weight = 1f - bar.share
            fill.requestLayout()
            rest.requestLayout()
            view.visibility = VISIBLE
        }

        /**
         * Point the row's glyph at [platform], only when it actually changed — rows are reused
         * across emissions and the panel is re-rendered on every count, but a row's platform
         * only moves when the ordering does (the bars are sorted by count).
         *
         * [PlatformRegistry.specOrNull], not `specFor`: [Platform] has entries with no enabled
         * spec (Facebook), and while [BubbleBreakdown] can't produce a bar for one today, a
         * render path is the wrong place to depend on that staying true. Same for `iconRes == 0`
         * — a platform added without an icon gets the generic glyph, never a hole in the column.
         */
        private fun setIcon(platform: Platform) {
            val spec = PlatformRegistry.specOrNull(platform)
            val res = spec?.iconRes?.takeIf { it != 0 } ?: R.drawable.ic_platform_generic
            if (res == lastIcon) return
            lastIcon = res
            icon.setImageResource(res)
        }

        fun hide() {
            view.visibility = GONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Pill padding. Horizontal is wider so the mascot isn't cramped against the corner. */
        const val PAD_H_DP = 12
        const val PAD_V_DP = 8

        /** Gap between the mascot and the count. */
        const val GAP_DP = 8

        /** Compact count size. Large enough to read at a glance over a moving video. */
        const val HEADLINE_SP = 18f

        /** Panel text (the time line, bar labels and counts) — a subtitle, not the headline. */
        const val PANEL_TEXT_SP = 11f

        /** The guilt line in the panel. Between the count and the labels: it is the message,
         *  but it is still a line inside an overlay, not a headline. */
        const val PANEL_LINE_SP = 13f

        /** Gap under the guilt line, separating it from the numbers that justify it. */
        const val PANEL_LINE_MARGIN_DP = 6

        /** Minimum panel width, so the bars have room to be readable when expanded. Widened
         *  from 180 for D44's platform names — a 180dp panel with a name column left the bars
         *  too short to compare, which is the panel's whole job. */
        const val PANEL_MIN_WIDTH_DP = 240

        /** Gap between the compact line and the panel it expands into. */
        const val PANEL_TOP_MARGIN_DP = 8

        const val BAR_HEIGHT_DP = 6

        /** Glyph size. */
        const val BAR_ICON_DP = 13

        /** Gap between the glyph and the platform name beside it. */
        const val BAR_ICON_GAP_DP = 5

        /** Name column width. FIXED, so every bar in the panel starts at the same x — the
         *  longest shipped name ("Snapchat") fits at 11sp and anything longer ellipsises. */
        const val BAR_NAME_WIDTH_DP = 56
        const val BAR_COUNT_WIDTH_DP = 24
        const val BAR_GAP_DP = 6
        const val BAR_ROW_MARGIN_DP = 6

        /** Headline + count colour: white over the accent-tinted pill. From [Brand] (D58). */
        val TEXT_COLOR = Brand.ON_DARK.toInt()

        /** Panel label colour: white at ~76% so the labels recede behind the numbers. */
        val PANEL_TEXT_COLOR = Brand.ON_DARK_MUTED.toInt()
    }
}
