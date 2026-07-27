package com.scrollkiller.challenge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
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
     * Push the current progress. Cheap and idempotent: an unchanged pair skips the invalidate
     * entirely, which matters because the sensor can deliver several events in quick succession
     * and each one would otherwise schedule a redraw of a view that looks identical.
     */
    fun setProgress(progress: Int, target: Int) {
        if (progress == this.progress && target == this.target) return
        this.progress = progress
        this.target = target.coerceAtLeast(1)   // never divide by zero, however a spec is edited
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
        canvas.drawText("$progress / $target", width / 2f, baseline, textPaint)
    }

    private companion object {
        /** Ring thickness as a fraction of the view's width, so it scales with the size. */
        const val STROKE_FRACTION = 0.08f

        /** Centre label size as a fraction of width. */
        const val TEXT_FRACTION = 0.20f

        const val TRACK_COLOR = 0x33FFFFFF
        const val ARC_COLOR = 0xFF4CAF50.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
    }
}
