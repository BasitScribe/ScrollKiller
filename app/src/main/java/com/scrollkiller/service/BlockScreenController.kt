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
 *  - Optional: a no-op if the overlay permission isn't granted, so blocking never
 *    hard-depends on it (matches the bubble).
 *
 * All methods run on the service main thread.
 *
 * @param onExit user chose to leave (Exit button or Back).
 * @param onUnlock user chose to unlock (opens a challenge next checkbox; a temporary
 *   bypass for now).
 */
class BlockScreenController(
    private val context: Context,
    private val onExit: () -> Unit,
    private val onUnlock: () -> Unit,
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
        root.findViewById<Button>(R.id.block_unlock).setOnClickListener { onUnlock() }
        root.findViewById<Button>(R.id.block_exit).setOnClickListener { onExit() }

        // Intercept Back so it can't dismiss the block to reveal Instagram — Back = Exit.
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onExit()
                true
            } else {
                false
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
