package com.scrollkiller

import com.scrollkiller.ui.onboarding.OnboardingRoute
import com.scrollkiller.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The launch route. Small surface, but it is the one decision that can put a screen in front of the
 * Play-mandated disclosure (invariant 5) or strand an upgrading user on an introduction they do not
 * need, and neither failure is visible without running the app on a fresh install.
 */
class OnboardingRouteTest {

    private fun step(
        welcomeSeen: Boolean = true,
        accessibilityEnabled: Boolean = true,
        canDrawOverlays: Boolean = true,
        overlayStepDismissed: Boolean = false,
    ) = OnboardingRoute.stepFor(welcomeSeen, accessibilityEnabled, canDrawOverlays, overlayStepDismissed)

    @Test
    fun `a fresh install opens on the welcome screen`() {
        assertEquals(
            OnboardingStep.WELCOME,
            step(welcomeSeen = false, accessibilityEnabled = false, canDrawOverlays = false),
        )
    }

    @Test
    fun `welcome hands off to the disclosure, never past it (invariant 5)`() {
        // The whole point of the welcome screen is that it asks for NOTHING. Once it has been seen,
        // the very next screen must be the disclosure — Play policy requires it before the user is
        // sent to enable an Accessibility Service. If this ever routes straight to the dashboard or
        // the overlay step, the app is shipping a policy violation.
        assertEquals(
            OnboardingStep.DISCLOSURE,
            step(welcomeSeen = true, accessibilityEnabled = false, canDrawOverlays = false),
        )
    }

    @Test
    fun `the disclosure is unreachable-past while accessibility is off, whatever else is true`() {
        // Exhaustive over the two flags that must not be able to skip it.
        listOf(true, false).forEach { overlays ->
            listOf(true, false).forEach { dismissed ->
                val s = step(
                    welcomeSeen = true,
                    accessibilityEnabled = false,
                    canDrawOverlays = overlays,
                    overlayStepDismissed = dismissed,
                )
                assertEquals(
                    "overlays=$overlays dismissed=$dismissed must still land on the disclosure",
                    OnboardingStep.DISCLOSURE,
                    s,
                )
            }
        }
    }

    @Test
    fun `an upgrading user who already granted accessibility never sees the welcome screen`() {
        // THE case the `welcomeSeen &&` gate exists for. Someone who has been using the app for
        // weeks installs a build that adds a welcome screen; their `welcome_seen` pref is false
        // because it did not exist before. Showing them "here's what this app does" would be
        // absurd, and worse, it would look like the app had reset itself.
        assertNotEquals(
            OnboardingStep.WELCOME,
            step(welcomeSeen = false, accessibilityEnabled = true, canDrawOverlays = true),
        )
        assertEquals(
            OnboardingStep.DASHBOARD,
            step(welcomeSeen = false, accessibilityEnabled = true, canDrawOverlays = true),
        )
    }

    @Test
    fun `the optional overlay step is offered once and then skipped forever`() {
        assertEquals(
            OnboardingStep.OVERLAY,
            step(accessibilityEnabled = true, canDrawOverlays = false, overlayStepDismissed = false),
        )
        assertEquals(
            "dismissing it must not re-offer it — that is the nag D16 forbids",
            OnboardingStep.DASHBOARD,
            step(accessibilityEnabled = true, canDrawOverlays = false, overlayStepDismissed = true),
        )
    }

    @Test
    fun `granting the overlay reaches the dashboard without needing the step dismissed`() {
        assertEquals(
            OnboardingStep.DASHBOARD,
            step(accessibilityEnabled = true, canDrawOverlays = true, overlayStepDismissed = false),
        )
    }

    @Test
    fun `the fully set-up user goes straight to the dashboard`() {
        assertEquals(OnboardingStep.DASHBOARD, step())
    }

    @Test
    fun `every combination resolves to exactly one step and never throws`() {
        // The route is total: there is no input the app can be launched in that leaves it with
        // nothing to show. Cheap to assert exhaustively at 16 combinations.
        var seen = 0
        listOf(true, false).forEach { w ->
            listOf(true, false).forEach { a ->
                listOf(true, false).forEach { o ->
                    listOf(true, false).forEach { d ->
                        OnboardingRoute.stepFor(w, a, o, d)
                        seen++
                    }
                }
            }
        }
        assertEquals(16, seen)
    }
}
