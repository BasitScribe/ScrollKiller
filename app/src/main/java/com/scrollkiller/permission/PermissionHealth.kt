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
 * - [NOTIFICATIONS]: everything works; the app just cannot warn you about the two above while you
 *   are outside it. Degraded, not broken — see [PermissionHealth.isFullyActive].
 */
enum class PermissionGap { ACCESSIBILITY, OVERLAY, NOTIFICATIONS }

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
 */
data class PermissionHealth(
    val accessibilityEnabled: Boolean,
    val canDrawOverlays: Boolean,
    val canNotify: Boolean,
) {

    /** Counting works. */
    val canDetect: Boolean get() = accessibilityEnabled

    /**
     * The block can actually appear. BOTH halves are required and that is the whole point: the
     * D51 failure had `canDetect` true and this false, which is precisely the state that used to
     * produce no signal at all.
     */
    val canBlock: Boolean get() = accessibilityEnabled && canDrawOverlays

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
            !canDrawOverlays -> PermissionGap.OVERLAY
            !canNotify -> PermissionGap.NOTIFICATIONS
            else -> null
        }

    companion object {
        /** Everything granted. The state the app should be in, and the test's baseline. */
        val HEALTHY = PermissionHealth(
            accessibilityEnabled = true,
            canDrawOverlays = true,
            canNotify = true,
        )
    }
}
