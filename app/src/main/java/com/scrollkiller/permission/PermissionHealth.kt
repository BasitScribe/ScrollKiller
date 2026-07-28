package com.scrollkiller.permission

/**
 * What is missing, worst first.
 *
 * The ORDER is load-bearing, not cosmetic: [PermissionHealth.firstMissing] returns the earliest
 * entry that is absent, and the Home banner offers exactly ONE Fix button routed from it. Three
 * buttons on a warning banner asks the user to prioritise a system they cannot see, so the app
 * does the prioritising and points at the one thing most worth fixing.
 *
 * - [ACCESSIBILITY]: nothing works. No counting, no bubble, no block — and no notification either,
 *   because the service that would post it is not running.
 * - [OVERLAY]: counting works perfectly and the block is dead. THE gap that caused D51: the app
 *   counted to 108, decided to block a hundred times, and was refused every time.
 * - [OVERLAY_BLOCKED_BY_SYSTEM]: the permission is GRANTED as far as Android will admit, and the
 *   window still does not appear (D52). Same severity as [OVERLAY] — the block is equally dead —
 *   but it needs its own entry because the user-facing advice is completely different: telling
 *   someone to grant a permission their settings screen already shows as granted is a dead end.
 * - [NOTIFICATIONS]: everything works; the app just cannot warn you about the ones above while you
 *   are outside it. Degraded, not broken — see [PermissionHealth.isFullyActive].
 */
enum class PermissionGap { ACCESSIBILITY, OVERLAY, OVERLAY_BLOCKED_BY_SYSTEM, NOTIFICATIONS }

/**
 * Can the app actually do its job right now? ONE answer, read by both the block path and the UI.
 *
 * ## Why this exists (D51)
 * The permission checks were scattered — `Settings.canDrawOverlays` in two controllers,
 * `AccessibilityStatus` in the Activity — and each one decided for itself what to do when the
 * answer was no. The overlay controller's answer (log at debug, carry on) was right for the bubble
 * and was inherited by the block screen, where it meant the core promise of the product silently
 * stopped existing while detection kept working perfectly. A single model makes "we cannot block"
 * a fact the whole app agrees on rather than a branch each caller improvises.
 *
 * Pure Kotlin, no Android imports: [of] does the reading, this does the deciding, and the deciding
 * is what a unit test can pin.
 *
 * @param accessibilityEnabled our AccessibilityService is on. Without it nothing runs at all.
 * @param canDrawOverlays SYSTEM_ALERT_WINDOW is granted. Without it Android refuses the window
 *   with `AppOps: Operation not started op=SYSTEM_ALERT_WINDOW` and the block never appears.
 * @param canNotify POST_NOTIFICATIONS is granted (or the OS is old enough not to need it).
 * @param overlayRuntimeDenied we OBSERVED the system refuse or destroy our overlay window (D52).
 *   The odd one out: the other three are answers to questions we asked Android, this is something
 *   that happened to us. It exists because on MediaTek/Chinese ROMs [canDrawOverlays] returns TRUE
 *   while AppOps refuses `SYSTEM_ALERT_WINDOW` at runtime — the permission query lies, and the only
 *   honest signal left is whether the window actually appeared.
 */
data class PermissionHealth(
    val accessibilityEnabled: Boolean,
    val canDrawOverlays: Boolean,
    val canNotify: Boolean,
    val overlayRuntimeDenied: Boolean = false,
) {

    /** Counting works. */
    val canDetect: Boolean get() = accessibilityEnabled

    /**
     * The block can actually appear. All three conditions are required and that is the whole
     * point: the D51 failure had `canDetect` true and this false, which is precisely the state
     * that used to produce no signal at all.
     *
     * [overlayRuntimeDenied] is the D52 addition and it OVERRIDES a granted-looking permission.
     * "Android says we may draw overlays" and "our overlay window exists" turned out to be
     * different facts, and only the second one blocks anyone.
     */
    val canBlock: Boolean
        get() = accessibilityEnabled && canDrawOverlays && !overlayRuntimeDenied

    /** We can tell the user about a problem while they are outside the app. */
    val canWarnOutOfApp: Boolean get() = canNotify

    /**
     * The app can do everything it promises.
     *
     * Deliberately ignores [canNotify]: notifications are how we report a failure, not part of the
     * job itself, and an app that called itself "not fully active" for a missing alert channel
     * would be crying wolf about the one banner that must always be believed.
     */
    val isFullyActive: Boolean get() = canDetect && canBlock

    /**
     * True when the app works but cannot warn you about it going wrong. The banner says so in a
     * quieter tone — honest that the safety net has a hole, without implying the app is broken.
     */
    val isDegraded: Boolean get() = isFullyActive && !canWarnOutOfApp

    /** Everything is granted; the banner is hidden entirely. */
    val isHealthy: Boolean get() = isFullyActive && canWarnOutOfApp

    /** The worst thing missing, or null when nothing is. Drives the banner's single Fix action. */
    val firstMissing: PermissionGap?
        get() = when {
            !accessibilityEnabled -> PermissionGap.ACCESSIBILITY
            // Checked BEFORE the runtime denial: if the permission is genuinely missing, that is
            // the thing to fix, and the observed refusal is just its consequence.
            !canDrawOverlays -> PermissionGap.OVERLAY
            overlayRuntimeDenied -> PermissionGap.OVERLAY_BLOCKED_BY_SYSTEM
            !canNotify -> PermissionGap.NOTIFICATIONS
            else -> null
        }

    companion object {
        /** Everything granted. The state the app should be in, and the test's baseline. */
        val HEALTHY = PermissionHealth(
            accessibilityEnabled = true,
            canDrawOverlays = true,
            canNotify = true,
            overlayRuntimeDenied = false,
        )
    }
}
