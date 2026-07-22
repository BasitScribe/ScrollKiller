package com.scrollkiller

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrollkiller.ui.home.HomeScreen
import com.scrollkiller.ui.home.HomeViewModel
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
 *  3. otherwise                            -> [HomeScreen] with the live count
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()  // draw behind the system bars (modern Android look)

        // Seed before first composition so the correct screen shows immediately.
        refreshPermissionState()
        overlayStepDismissed.value = isOverlayStepDismissed()

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
                        val homeViewModel: HomeViewModel = viewModel()
                        val count = homeViewModel.count.collectAsState().value
                        HomeScreen(count = count)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from Settings (or any resume) and recompose.
        refreshPermissionState()
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
