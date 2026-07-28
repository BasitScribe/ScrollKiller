package com.scrollkiller.challenge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.scrollkiller.ui.theme.Brand
import kotlin.math.min

/**
 * The challenge's live progress: an arc sweeping clockwise from twelve o'clock, with "7 / 20" in
 * the middle.
 *
 * ## Why a custom view and not a ProgressBar
 * The app's first hand-drawn view, and the exception is earned. A horizontal bar reads as "loading"
 * — a thing being done TO you that you wait out. A ring filling as you walk reads as effort you are
 * putting in, which is the entire psychology of the mechanic: the block is asking for something,
 * and the feedback has to look like the something is being given. It is also ~40 lines of
 * [Canvas.drawArc] against a styled `ProgressBar` that would still be the wrong shape.
 *
 * Fixed colours rather than theme attributes, exactly like `overlay_block.xml` and
 * `overlay_bubble_bg`: this draws over another app, outside the Compose theme, so there is nothing
 * to inherit from.
 *
 * Square by construction — [onMeasure] takes the smaller axis — so the arc is a circle at every
 * size and the caller can size it with a plain height.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var progress = 0
    private var target = 1

    /**
     * The centre text. Supplied by the caller rather than formatted here: what the number MEANS is
     * the spec's business ([ProgressUnit]), and a view that knew the difference between steps and
     * seconds would be a second place for that rule to live.
     */
    private var label = ""

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TRACK_COLOR
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ARC_COLOR
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bounds = RectF()

    /**
     * Push the current progress and its centre [label].
     *
     * Cheap and idempotent: an unchanged triple skips the invalidate entirely, which matters more for
     * the holds than it did for the counters — a hold source samples ~5×/second for 30 seconds, and
     * only one in five of those samples changes the displayed second.
     */
    fun setProgress(progress: Int, target: Int, label: String) {
        if (progress == this.progress && target == this.target && label == this.label) return
        this.progress = progress
        this.target = target.coerceAtLeast(1)   // never divide by zero, however a spec is edited
        this.label = label
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width * STROKE_FRACTION
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        textPaint.textSize = width * TEXT_FRACTION

        // Inset by half the stroke: an arc is drawn CENTRED on its path, so a full-bleed rect
        // would clip the outer half of the line against the view bounds.
        val inset = stroke / 2f
        bounds.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        val sweep = 360f * (progress.toFloat() / target).coerceIn(0f, 1f)
        // -90 starts the sweep at twelve o'clock rather than three, which is where every progress
        // ring anyone has ever seen starts.
        if (sweep > 0f) canvas.drawArc(bounds, -90f, sweep, false, arcPaint)

        // Baseline maths: centre the CAP HEIGHT, not the font box, or the text sits visibly low.
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }

    private companion object {
        /** Ring thickness as a fraction of the view's width, so it scales with the size. */
        const val STROKE_FRACTION = 0.08f

        /** Centre label size as a fraction of width. */
        const val TEXT_FRACTION = 0.20f

        /*
         * From [Brand] (D58), not local literals — this view draws over another app and cannot read
         * the Compose theme, which is exactly the case Brand exists for.
         */

        /** Unfilled track: white at 20%, so the full circle is implied without competing with the arc. */
        val TRACK_COLOR = Brand.ON_DARK_FAINT.toInt()

        /**
         * The filled arc. The mascot's HEALTHY mint rather than the old Material `#4CAF50`: effort
         * being put in is a healthy act, and it ties the ring to the same colour Home uses for a brain
         * that is doing fine.
         */
        val ARC_COLOR = Brand.STATE_HEALTHY.toInt()

        val TEXT_COLOR = Brand.ON_DARK.toInt()
    }
}
