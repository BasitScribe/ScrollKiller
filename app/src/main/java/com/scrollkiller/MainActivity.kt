package com.scrollkiller

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrollkiller.guilt.GuiltLines
import com.scrollkiller.ui.dashboard.DashboardScreen
import com.scrollkiller.ui.dashboard.DashboardViewModel
import com.scrollkiller.ui.onboarding.AccessibilityStatus
import com.scrollkiller.ui.onboarding.DisclosureScreen
import com.scrollkiller.ui.onboarding.OverlayPermissionScreen
import com.scrollkiller.ui.onboarding.OverlayStatus
import com.scrollkiller.ui.theme.ScrollKillerTheme

/**
 * The single entry-point Activity (declared as LAUNCHER in the manifest).
 *
 * It routes between three states, in order:
 *  1. accessibility NOT enabled           -> [DisclosureScreen] (required permission)
 *  2. accessibility on, overlay missing
 *     and the step not yet dismissed       -> [OverlayPermissionScreen] (optional)
 *  3. otherwise                            -> [DashboardScreen] (Today / Apps / Settings)
 *
 * Both permission flags are re-checked in [onResume] so returning from the system
 * Settings screen immediately advances the UI without a restart. The overlay step is
 * optional: dismissing it (persisted in prefs) drops straight through to Home, and
 * the bubble simply stays off until the permission is granted later.
 */
class MainActivity : ComponentActivity() {

    // Compose state so flipping these in onResume recomposes the UI.
    private val accessibilityEnabled = mutableStateOf(false)
    private val canDrawOverlays = mutableStateOf(false)
    private val overlayStepDismissed = mutableStateOf(false)

    /** The dashboard's ViewModel once composed, so [onResume] can refresh its health (D51). */
    private var dashboard: DashboardViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()  // draw behind the system bars (modern Android look)

        // Seed before first composition so the correct screen shows immediately.
        refreshPermissionState()
        overlayStepDismissed.value = isOverlayStepDismissed()

        // App open = a fresh guilt line, even if the count hasn't moved a tier since last time
        // (D41). onCreate, NOT onResume: an open is a new look at your number, whereas a resume
        // is also a dismissed notification shade — and swapping the sentence someone is halfway
        // through reading is worse than repeating it.
        GuiltLines.onAppOpen()

        setContent {
            ScrollKillerTheme {
                when {
                    !accessibilityEnabled.value -> DisclosureScreen(
                        onEnableClick = { AccessibilityStatus.openAccessibilitySettings(this) },
                    )
                    !canDrawOverlays.value && !overlayStepDismissed.value -> OverlayPermissionScreen(
                        onEnableClick = { OverlayStatus.openOverlaySettings(this) },
                        onSkipClick = { dismissOverlayStep() },
                    )
                    else -> {
                        val dashboardViewModel: DashboardViewModel = viewModel()
                        // Held so onResume can refresh permission health on it (D51) — the same
                        // return-from-Settings mechanism the two flags above already use, extended
                        // to the banner so re-granting flips it without a restart.
                        dashboard = dashboardViewModel
                        DashboardScreen(
                            viewModel = dashboardViewModel,
                            accessibilityEnabled = accessibilityEnabled.value,
                            canDrawOverlays = canDrawOverlays.value,
                            onOpenAccessibilitySettings = {
                                AccessibilityStatus.openAccessibilitySettings(this)
                            },
                            onOpenOverlaySettings = { OverlayStatus.openOverlaySettings(this) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from Settings (or any resume) and recompose. Android gives no
        // callback for "a permission was revoked", so resume is the signal — which is exactly why
        // the app could run for a whole session with the block dead and never notice (D51).
        refreshPermissionState()
        dashboard?.refreshHealth()
    }

    private fun refreshPermissionState() {
        accessibilityEnabled.value = AccessibilityStatus.isServiceEnabled(this)
        canDrawOverlays.value = OverlayStatus.canDrawOverlays(this)
    }

    /** Persist that the user skipped the optional overlay step so we don't nag them. */
    private fun dismissOverlayStep() {
        prefs().edit().putBoolean(KEY_OVERLAY_STEP_DISMISSED, true).apply()
        overlayStepDismissed.value = true
    }

    private fun isOverlayStepDismissed(): Boolean =
        prefs().getBoolean(KEY_OVERLAY_STEP_DISMISSED, false)

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "scrollkiller_onboarding"
        const val KEY_OVERLAY_STEP_DISMISSED = "overlay_step_dismissed"
    }
}
