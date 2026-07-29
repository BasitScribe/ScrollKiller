package com.scrollkiller.service

import android.util.Log
import com.scrollkiller.BuildConfig

/**
 * DEBUG-only: force the block window to fail, so the TRAP can be reproduced on demand (D71).
 *
 * ## Why this ships (in debug builds) rather than being a one-off patch
 * The bug this exists for was a full-screen, opaque, focusable overlay left in the WindowManager
 * with no reference to it: Exit did nothing, the other buttons did nothing, Back did nothing, and
 * because it outranked the launcher the user could not even reach their home screen. It reached a
 * real device and was found by a person, not a test.
 *
 * The path that produces it — `addView` returning without the view ever attaching — cannot be
 * triggered by hand on a healthy phone. Without a way to force it, the only available evidence that
 * the fix works is the ABSENCE of a bug that was already rare, which is not evidence at all. So the
 * failure is injectable, and "trigger a block with the injector armed, confirm Back still escapes
 * and no window is left behind" becomes a repeatable device check instead of a hope.
 *
 * Gated on [BuildConfig.DEBUG] at every entry point, so release builds compile the behaviour out and
 * a shipped app has no way to reach it — the same posture as [SurfaceDiagnostics].
 *
 * Arm it over adb, in the same shape as the `DIAG_LABEL` tour stamp:
 * ```
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BLOCK_FAIL --es mode no_attach
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BLOCK_FAIL --es mode throw
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BLOCK_FAIL --es mode off
 * ```
 * In-memory and NOT persisted, deliberately: an injected failure must not outlive the process and
 * follow someone into a session they did not arm it for.
 */
internal object BlockFailureInjector {

    /** How the next [BlockScreenController.show] should fail, if at all. */
    enum class Mode {
        /** Normal behaviour. The only mode a release build can ever be in. */
        OFF,

        /**
         * `addView` succeeds and the window is then treated as never having attached — THE trap
         * path. The view really is attached, so a correct implementation will really remove it,
         * which is exactly what the check is proving.
         */
        NO_ATTACH,

        /** `addView` throws, as it does when AppOps refuses the op outright. */
        THROW,
    }

    /** Current arming. Main thread only, like the rest of the overlay. */
    private var mode = Mode.OFF

    /** Arm from the debug broadcast. Unknown or missing values disarm rather than guess. */
    fun arm(raw: String?) {
        if (!BuildConfig.DEBUG) return
        mode = when (raw?.lowercase()) {
            "no_attach", "noattach", "attach" -> Mode.NO_ATTACH
            "throw" -> Mode.THROW
            else -> Mode.OFF
        }
        Log.w(TAG, "block: FAILURE INJECTOR armed=$mode (DEBUG only) from \"$raw\"")
    }

    /** Should `addView` be made to throw? */
    fun shouldThrow(): Boolean = BuildConfig.DEBUG && mode == Mode.THROW

    /** Should a genuinely-attached window be reported as never attached? */
    fun shouldFakeNoAttach(): Boolean = BuildConfig.DEBUG && mode == Mode.NO_ATTACH

    /** For the log line on the attempt, so a capture says whether it was rigged. */
    fun describe(): String = if (!BuildConfig.DEBUG || mode == Mode.OFF) "off" else mode.name

    private const val TAG = "ScrollKiller"
}
