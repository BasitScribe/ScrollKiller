package com.scrollkiller.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * Resolves the [ScreenBounds] the overlay is allowed to occupy — the display, minus the status
 * bar, navigation bar and display cutout. The one Android-dependent half of the D38 placement
 * work; the arithmetic that uses it is the pure [OverlayPlacement].
 *
 * ## Why "ignoring visibility"
 * We ask for `getInsetsIgnoringVisibility` rather than the live insets, because the overlay sits
 * on top of a full-screen video player that hides the system bars while it plays. Live insets
 * there are zero, so a panel placed against them would be positioned for a chrome-less screen
 * and then be sitting under the status bar the moment the user taps and the bars come back. The
 * insets a bar WOULD have are the stable quantity, and the few dp we give up are cheaper than a
 * panel that moves when the system bars animate in.
 *
 * ## Two paths, because minSdk is 26
 * API 30+ has [WindowManager.getCurrentWindowMetrics], which is exact and cutout-aware. Below
 * that there is no equivalent, so we take the real display size and subtract the framework's own
 * `status_bar_height` / `navigation_bar_height` resources. That is the standard pre-R approach
 * and it is approximate — it assumes the nav bar is at the bottom (it can be on the side in
 * landscape on some 26–29 devices, where we will simply reserve the height at the bottom instead
 * of the width at the side). Approximate is fine here: it is a containment margin, and erring
 * toward reserving a little too much only pulls the panel slightly further inside the screen.
 *
 * Deliberately NOT read from the overlay view's own `rootWindowInsets`: the bubble's window
 * carries [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS], and a no-limits window is laid out
 * against the whole display, so the insets dispatched to it are not the ones we need to avoid.
 */
object OverlayMetrics {

    /**
     * The usable rectangle in absolute screen pixels, insets already subtracted. Recomputed on
     * every expand and on every configuration change — never cached, because it changes on
     * rotation, on a foldable unfolding, and on multi-window entry.
     */
    fun usableBounds(context: Context, windowManager: WindowManager): ScreenBounds =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boundsFromWindowMetrics(windowManager)
        } else {
            boundsFromRealDisplay(context, windowManager)
        }

    /** API 30+: exact bounds and exact insets, cutout included. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun boundsFromWindowMetrics(windowManager: WindowManager): ScreenBounds {
        val metrics = windowManager.currentWindowMetrics
        val frame = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return ScreenBounds(
            left = frame.left + insets.left,
            top = frame.top + insets.top,
            right = frame.right - insets.right,
            bottom = frame.bottom - insets.bottom,
        )
    }

    /** API 26–29: real display size minus the framework's declared bar heights. */
    @Suppress("DEPRECATION") // getRealSize/defaultDisplay: no API-29-or-below alternative exists.
    private fun boundsFromRealDisplay(context: Context, windowManager: WindowManager): ScreenBounds {
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        val top = systemDimen(context, "status_bar_height")
        val bottom = systemDimen(context, "navigation_bar_height")
        return ScreenBounds(left = 0, top = top, right = size.x, bottom = size.y - bottom)
    }

    /**
     * A framework dimension by name, or 0 when this device doesn't declare it. Resolving by
     * name is the only way to reach these — they are not in `android.R.dimen` — and a missing
     * one (no nav bar at all) legitimately means zero.
     */
    @SuppressLint("DiscouragedApi") // no public constant exists for these framework dimens.
    private fun systemDimen(context: Context, name: String): Int {
        val res = context.resources
        val id = res.getIdentifier(name, "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }
}
