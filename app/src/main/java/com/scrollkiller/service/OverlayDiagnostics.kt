package com.scrollkiller.service

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.WindowManager

/**
 * One greppable state block describing WHY an overlay window did not appear.
 *
 * ## Why this exists as its own object
 * Three sessions have now been spent attributing a failed block to the wrong cause — D52 blamed
 * AppOps, D70 blamed a stale flag, and both were reasoning from a log line that said only "it did
 * not work". The expensive part was never the fix; it was that every hypothesis needed another
 * device run to test, because the log carried none of the state that would have separated them.
 * So the failure now prints everything a hypothesis could want, at once, in one line.
 *
 * ## The AppOps read is a DIAGNOSTIC and may never become a gate (D70)
 * `unsafeCheckOpNoThrow(OPSTR_SYSTEM_ALERT_WINDOW)` is exactly the query that would be tempting to
 * branch on — it is the op the window manager actually consults, so it looks more authoritative
 * than `Settings.canDrawOverlays`. It is read here to be PRINTED and nowhere else. D70's rule
 * stands whatever this returns: a prediction may never stand in front of the attempt, and every
 * permission query on these ROMs has now been observed to lie at least once. If a future edit
 * makes anything branch on this value, that edit is the bug.
 */
internal object OverlayDiagnostics {

    /**
     * Everything known about the device and the request, for a failed or pending attach.
     *
     * @param stage where in [BlockScreenController.show] this was taken.
     * @param params the window we asked for — the shape is a live hypothesis (a focusable,
     *   full-screen, opaque overlay is the tapjacking shape ROMs restrict hardest, and the bubble
     *   that works is none of those things), so it has to be in the log next to the outcome.
     * @param bubbleAttached whether our OTHER overlay window is up right now. THE discriminating
     *   bit: the bubble is the same window type over the same app, so bubble-up + block-refused
     *   means nothing is refusing our overlays wholesale and the difference is this window's shape
     *   or this app's `setHideOverlayWindows`. Bubble-down too means the refusal is global.
     */
    fun state(
        context: Context,
        stage: String,
        params: WindowManager.LayoutParams,
        bubbleAttached: Boolean,
        platform: Platform?,
    ): String = buildString {
        append("block: DIAG stage=$stage")
        append(" platform=$platform")
        append(" canDrawOverlays=${Settings.canDrawOverlays(context)}")
        append(" appOpSAW=${systemAlertWindowOp(context)}")
        append(" bubbleAttached=$bubbleAttached")
        append(" device=${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}")
        append(" sdk=${Build.VERSION.SDK_INT}")
        append(" params=[${describe(params)}]")
    }

    /** The exception, named rather than only stack-dumped, so one grep finds the type. */
    fun cause(e: Throwable): String = "${e.javaClass.name}: ${e.message}"

    /**
     * The AppOps mode for `SYSTEM_ALERT_WINDOW`, as a name rather than an int.
     *
     * Worth reading alongside `canDrawOverlays` precisely because they can DISAGREE: the settings
     * query can report granted while the op the window manager consults is `ignored`, which is the
     * shape D52 hypothesised and never actually confirmed. Printing both is how that gets settled.
     */
    private fun systemAlertWindowOp(context: Context): String {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return "no-appops-service"
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION") // the pre-29 spelling of the same query
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    Process.myUid(),
                    context.packageName,
                )
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> "allowed"
                AppOpsManager.MODE_IGNORED -> "IGNORED"
                AppOpsManager.MODE_ERRORED -> "ERRORED"
                AppOpsManager.MODE_DEFAULT -> "default"
                else -> "mode$mode"
            }
        } catch (e: Exception) {
            // Some ROMs restrict the query itself. That is data too, and it must not take the
            // diagnostic path down with it.
            "query-threw(${e.javaClass.simpleName})"
        }
    }

    /** The requested window, in the terms a WindowManager refusal would care about. */
    private fun describe(p: WindowManager.LayoutParams): String = buildString {
        append("type=${p.type}")
        append(" w=${size(p.width)} h=${size(p.height)}")
        append(" format=${p.format}")
        append(" flags=0x${Integer.toHexString(p.flags)}")
        append(" focusable=${p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0}")
        append(" touchable=${p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0}")
    }

    private fun size(value: Int): String = when (value) {
        WindowManager.LayoutParams.MATCH_PARENT -> "MATCH"
        WindowManager.LayoutParams.WRAP_CONTENT -> "WRAP"
        else -> value.toString()
    }
}
