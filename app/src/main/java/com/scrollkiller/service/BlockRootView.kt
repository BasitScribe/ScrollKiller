package com.scrollkiller.service

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * The block window's root. Exists for exactly one reason: to make the hardware BACK key an escape
 * that cannot be broken by anything else on the screen (CLAUDE.md invariant 6).
 *
 * ## Why an OnKeyListener was not enough (D71)
 * Back used to be handled by `root.setOnKeyListener` in [BlockScreenController]. A ViewGroup's
 * OnKeyListener only runs while that ViewGroup is ITSELF the focused view, so the escape hatch was
 * conditional on a focus arrangement nothing enforced:
 *
 *  - it required `root.requestFocus()` to have been reached — and on the failure path that produced
 *    the trap, it was not (the method returned before it), so the block was on screen with Back
 *    doing nothing at all;
 *  - it required no child to ever take focus — true in touch mode by luck, false the moment a
 *    keyboard, a dpad, or TalkBack moves focus onto one of the Buttons.
 *
 * An unhandled Back on a WindowManager-added view does nothing whatsoever. So the previous design
 * made "can the user leave?" depend on a property no test asserted and no code maintained.
 *
 * [dispatchKeyEvent] is called by `ViewRootImpl` on the window's root view for EVERY key event
 * before any focus-based routing happens. Overriding it here means Back works no matter which view
 * holds focus, or whether anything holds focus at all. That is the whole point: the way out must not
 * be a consequence of the UI being in a good state.
 *
 * BACK is also CONSUMED (returns true) rather than passed on, and that half is unchanged: an
 * un-consumed Back would dismiss our window and hand Instagram straight back, which is a bypass
 * rather than an escape. Back means Exit — [onBack] does the same thing the Exit button does.
 */
class BlockRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * What Back does. Set by [BlockScreenController] at inflation, before the view is ever added to
     * the window, so there is no window on screen whose Back is not yet wired.
     *
     * Null-safe on purpose rather than lateinit: a NullPointerException on the escape path would be
     * the trap this class exists to prevent. If it is somehow unset, Back is still consumed and
     * logged, and [BlockScreenController]'s other exits remain.
     */
    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        // DOWN is consumed as well as UP so the framework never sees a half-handled Back; the
        // action fires on UP, which is where a press is committed.
        if (event.action == KeyEvent.ACTION_UP) {
            val wired = onBack != null
            Log.d(TAG, "block: TAP back(hardware) wired=$wired")
            onBack?.invoke()
            Log.d(TAG, "block: TAP back(hardware) → dispatched")
        }
        return true
    }

    private companion object {
        const val TAG = "ScrollKiller"
    }
}
