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
     * The system says we MAY draw the block. Both conditions are required and that is the whole
     * point: the D51 failure had `canDetect` true and this false, which is precisely the state
     * that used to produce no signal at all.
     *
     * ## Why [overlayRuntimeDenied] is deliberately NOT part of this (D70)
     * It used to be, and that turned a diagnosis into a permanent sentence. The flag is a
     * PERSISTED observation of a past failure; this property gated whether the block was even
     * attempted; and the only code that cleared the flag lived *behind* that gate, inside the
     * success branch of the attempt. So one refusal — including the perfectly ordinary one you get
     * by hitting your limit while the permission is genuinely off — latched the flag true, the
     * gate then refused every future attempt, and the clearing code became unreachable. Granting
     * the permission did not help. Nothing did, short of clearing app data.
     *
     * A prediction must never stand in front of the attempt: D52's own finding was that on these
     * ROMs only the attempt knows. So this answers the queryable question and nothing more, and
     * whether the window ACTUALLY appears is settled by trying — see
     * [com.scrollkiller.service.BlockScreenController.show].
     *
     * The observation is not discarded; it moved to [blockObservedBroken], which drives what the
     * user is TOLD and never what the app attempts.
     */
    val canBlock: Boolean
        get() = accessibilityEnabled && canDrawOverlays

    /**
     * We have OBSERVED the system refuse or destroy our overlay window, whatever the permission
     * query claims (D52).
     *
     * Health/banner layer ONLY. Read this to decide what to show a user; never to decide whether
     * to try. It is stale by construction — it describes the last attempt, not this one — which is
     * exactly why gating on it produced the D70 deadlock.
     */
    val blockObservedBroken: Boolean get() = overlayRuntimeDenied

    /** We can tell the user about a problem while they are outside the app. */
    val canWarnOutOfApp: Boolean get() = canNotify

    /**
     * The app can do everything it promises.
     *
     * Deliberately ignores [canNotify]: notifications are how we report a failure, not part of the
     * job itself, and an app that called itself "not fully active" for a missing alert channel
     * would be crying wolf about the one banner that must always be believed.
     *
     * Unlike [canBlock] this DOES fold in [blockObservedBroken] — and that split is the point of
     * D70. "Should we try?" and "should we reassure the user?" are different questions: a stale
     * observation is a perfectly good reason to keep a warning on screen, and never a reason to
     * stop attempting. The banner behaviour here is unchanged from D52.
     */
    val isFullyActive: Boolean get() = canDetect && canBlock && !blockObservedBroken

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
