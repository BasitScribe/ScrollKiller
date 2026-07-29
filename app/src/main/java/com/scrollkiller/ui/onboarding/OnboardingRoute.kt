package com.scrollkiller.ui.onboarding

/** Which screen the app should be showing at launch. */
enum class OnboardingStep { WELCOME, DISCLOSURE, OVERLAY, DASHBOARD }

/**
 * Pure decision for what the app opens on. No Android imports, so the one piece of logic that can
 * strand a user on the wrong screen — or put a permission request in front of them before the
 * disclosure Play policy requires — is unit-testable off-device.
 *
 * It used to be a `when` block inline in `MainActivity` with nothing asserting its ordering. That
 * was survivable while it had three arms; adding [OnboardingStep.WELCOME] in front of a
 * policy-mandated screen (invariant 5) is not the change to make in an untested expression.
 *
 * @see OnboardingRoute.stepFor
 */
object OnboardingRoute {

    /**
     * The screen to show, in strict priority order.
     *
     * @param welcomeSeen has the user been past the welcome screen once? Persisted, so it shows
     *   exactly once per install.
     * @param accessibilityEnabled is the detection service on? This is the REQUIRED permission —
     *   nothing works without it.
     * @param canDrawOverlays is the overlay permission granted? OPTIONAL; counting works without it.
     * @param overlayStepDismissed did the user tap "Not now" on the optional overlay step? Persisted
     *   so we ask once and never nag (D16).
     */
    fun stepFor(
        welcomeSeen: Boolean,
        accessibilityEnabled: Boolean,
        canDrawOverlays: Boolean,
        overlayStepDismissed: Boolean,
    ): OnboardingStep = when {
        // WELCOME is gated on BOTH flags, and the second one is the important half. Gating on
        // `!welcomeSeen` alone would shove a "here's what this app does" screen at an existing user
        // on their first launch after upgrading — someone who has already granted the permission and
        // been using the app for weeks. If the service is already on, the introduction has demonstrably
        // already happened, whatever the flag says.
        !welcomeSeen && !accessibilityEnabled -> OnboardingStep.WELCOME

        // INVARIANT 5. The disclosure is what Play policy requires BEFORE the user is sent to enable
        // an Accessibility Service, and WELCOME above deliberately asks for nothing — it explains and
        // hands off here. Adding a step in front of the disclosure is allowed; putting anything that
        // REQUESTS the permission in front of it is not.
        !accessibilityEnabled -> OnboardingStep.DISCLOSURE

        // Optional, skippable, asked once (D16).
        !canDrawOverlays && !overlayStepDismissed -> OnboardingStep.OVERLAY

        else -> OnboardingStep.DASHBOARD
    }
}
