package com.scrollkiller.service

/**
 * The usable rectangle the overlay may occupy, in absolute screen pixels, with the system
 * insets ALREADY subtracted (status bar, navigation bar, display cutout).
 *
 * Absolute screen coordinates — not window-relative — because the bubble's window is laid out
 * with `Gravity.TOP or Gravity.START` and [android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS],
 * so `params.x/y` ARE the window's top-left on screen. [left]/[top] are therefore usually the
 * inset amounts (e.g. 0, 88) rather than 0,0.
 *
 * @see OverlayMetrics.usableBounds which resolves this from the platform.
 */
data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        /** Before the first resolve, and the fallback if the platform gives us nothing. */
        val EMPTY = ScreenBounds(0, 0, 0, 0)
    }
}

/** Where to put the overlay window: the values written straight to `params.x/y`. */
data class Placement(val x: Int, val y: Int)

/**
 * Decides where the overlay window sits, for both of its shapes — the compact draggable pill
 * and the expanded breakdown panel. Pure and Android-free, exactly like [BlockPolicy] and
 * [BubbleBreakdown], so the containment rules are provable off-device instead of by dragging
 * a bubble into all four corners by hand (they are still checked on-device, but the arithmetic
 * is not what we're checking there).
 *
 * ## Why this exists (D38)
 * The bubble's window is `WRAP_CONTENT` with `FLAG_LAYOUT_NO_LIMITS`, which is what lets the
 * user drag the compact pill flush to any edge. The same flag means the system will NOT pull
 * the window back on screen when it grows — so expanding a pill parked at the right edge grew
 * the panel straight off the display. The window has to do its own containment, and this is it.
 *
 * ## The two shapes
 *  - [compact]: honour the drag position, clamp it into [ScreenBounds] so the pill can sit
 *    flush against an edge but never past one.
 *  - [expanded]: horizontally CENTRED in the usable area — deliberately NOT anchored to the
 *    bubble. A panel anchored to a pill at the right edge is either half off-screen or shoved
 *    inward by a distance that varies with the panel's width, which reads as the panel sliding
 *    around at random. Centring makes the expanded state one predictable place regardless of
 *    where the pill was parked. Vertically it stays where the pill was (so the header does not
 *    jump under the finger that just tapped it), clamped so the bottom edge stays on screen.
 *
 * Both degrade the same way when the view is LARGER than the usable area (a huge font scale, a
 * tiny window on a foldable's cover display): pin to [ScreenBounds.left]/[ScreenBounds.top] and
 * let the far edge overflow, so the total and the first bars — the part worth reading — are the
 * part that stays visible.
 */
object OverlayPlacement {

    /**
     * Where the compact pill goes for a drag that ended at [x], [y]: the same position, clamped
     * so no part of a [width]×[height] view leaves [bounds].
     */
    fun compact(bounds: ScreenBounds, width: Int, height: Int, x: Int, y: Int): Placement =
        Placement(
            x = clamp(x, bounds.left, bounds.right - width),
            y = clamp(y, bounds.top, bounds.bottom - height),
        )

    /**
     * Where the expanded panel goes: horizontally centred in [bounds], vertically at [anchorY]
     * (the compact pill's current top) clamped so the whole [height] fits.
     *
     * @param anchorY the y the pill was at, so expanding does not teleport the header.
     */
    fun expanded(bounds: ScreenBounds, width: Int, height: Int, anchorY: Int): Placement =
        Placement(
            x = clamp(bounds.left + (bounds.width - width) / 2, bounds.left, bounds.right - width),
            y = clamp(anchorY, bounds.top, bounds.bottom - height),
        )

    /**
     * [value] clamped into `min..max`, and [min] when the range is INVERTED — which is not a
     * defensive nicety but the "view is bigger than the screen" case: `max` is
     * `bounds.right - width`, so a view wider than the usable area makes `max < min`. Pinning
     * to `min` there keeps the readable edge on screen. `coerceIn` would throw.
     */
    private fun clamp(value: Int, min: Int, max: Int): Int =
        if (max <= min) min else value.coerceIn(min, max)
}
