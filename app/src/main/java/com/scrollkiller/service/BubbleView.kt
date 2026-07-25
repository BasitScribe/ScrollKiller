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
        panelTotal = TextView(context).apply {
            setTextColor(PANEL_TEXT_COLOR)
            textSize = PANEL_TEXT_SP
        }
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
     *   is precisely when it is most likely, since the state flip happens *because* the count
     *   moved (D33). [OverlayController] owns that decision, so it is a parameter here.
     */
    fun render(summary: TodaySummary, state: BrainState, headlineText: CharSequence?) {
        if (headlineText != null) headline.text = headlineText
        setMascot(state)
        // mutate() so tinting this instance doesn't affect the shared drawable constant.
        background?.mutate()?.setTint(state.accentArgb.toInt())
        renderPanel(summary)
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
    private fun renderPanel(summary: TodaySummary) {
        val bars = BubbleBreakdown.bars(summary)
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

    /** Swap the mascot art for [state], only when it actually changed. */
    private fun setMascot(state: BrainState) {
        val art = MascotArt.bubble(state)
        if (art == lastArt) return
        lastArt = art
        mascot.setImageResource(art)
    }

    /**
     * One `[icon] ▓▓▓░░ 30` row: a platform glyph, a proportional bar, and the count.
     *
     * ## Why an icon and not the "IG"/"YT" text it replaced (D40)
     * Two initials in 11sp, in a translucent white, at the far left of a bar in a pill floating
     * over a moving video, were the least legible thing in the panel — and they carried no more
     * information than a shape does. The glyphs are GENERIC (a camera, a video player, a music
     * note), never the platforms' brand marks: this panel draws inside someone else's app, and
     * a reproduced logo is a trademark question with nothing to gain. They come from
     * [PlatformSpec.iconRes] so adding a platform stays data, not code.
     *
     * The label column keeps its exact width, so the bars still line up down the panel and the
     * change is legibility only, not layout.
     *
     * The bar is drawn with LAYOUT WEIGHTS rather than a pixel width, because a pixel width
     * would need the track's measured size — which is not known when [bind] runs. Two weighted
     * children splitting a fixed weightSum express "this fraction of the track" directly and
     * survive the window resizing around them.
     */
    private inner class BarRow(context: Context) {
        val view: LinearLayout
        private val label: ImageView
        private val fill: View
        private val rest: View
        private val count: TextView

        /** Icon res currently set, so [bind] only touches the drawable when it changed. */
        private var lastIcon = 0

        init {
            label = ImageView(context).apply {
                layoutParams = LayoutParams(dp(BAR_ICON_DP), dp(BAR_ICON_DP))
                    .apply { marginEnd = dp(BAR_LABEL_WIDTH_DP) - dp(BAR_ICON_DP) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                // Tinted rather than shipped coloured: the glyph then recedes behind the count
                // at exactly the same weight the "IG"/"YT" text did, and one drawable serves
                // whatever the panel's palette becomes.
                imageTintList = ColorStateList.valueOf(PANEL_TEXT_COLOR)
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
                addView(label)
                addView(track)
                addView(count)
            }
        }

        fun bind(bar: BreakdownBar) {
            setIcon(bar.platform)
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
            val icon = spec?.iconRes?.takeIf { it != 0 } ?: R.drawable.ic_platform_generic
            if (icon == lastIcon) return
            lastIcon = icon
            label.setImageResource(icon)
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

        /** Minimum panel width, so the bars have room to be readable when expanded. */
        const val PANEL_MIN_WIDTH_DP = 180

        /** Gap between the compact line and the panel it expands into. */
        const val PANEL_TOP_MARGIN_DP = 8

        const val BAR_HEIGHT_DP = 6

        /** Label column width. Unchanged from the "IG"/"YT" text it replaced, so the bars in
         *  the panel still start at the same x and nothing else in the row had to move. */
        const val BAR_LABEL_WIDTH_DP = 22

        /** Glyph size inside that column; the remainder is the gap before the bar track. */
        const val BAR_ICON_DP = 13
        const val BAR_COUNT_WIDTH_DP = 24
        const val BAR_GAP_DP = 6
        const val BAR_ROW_MARGIN_DP = 6

        /** Headline + count colour: white over the accent-tinted pill. */
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** Panel label colour: white at ~76% so the labels recede behind the numbers. */
        const val PANEL_TEXT_COLOR = 0xC2FFFFFF.toInt()
    }
}
