package com.scrollkiller.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.scrollkiller.R
import com.scrollkiller.ui.theme.ScrollKillerTheme

/**
 * Second onboarding step: the OPTIONAL overlay permission that powers the floating
 * live-counter bubble. Shown after accessibility is enabled, only while the overlay
 * permission is missing and the user hasn't dismissed it.
 *
 * Unlike [DisclosureScreen] this is skippable — the bubble is a convenience, not a
 * requirement (detection and the in-app counter work without it), so there is a
 * plain "Not now" alongside the enable button.
 *
 * @param onEnableClick deep-links to the overlay settings ([OverlayStatus.openOverlaySettings]).
 * @param onSkipClick dismisses the step for this and future launches.
 */
@Composable
fun OverlayPermissionScreen(
    onEnableClick: () -> Unit,
    onSkipClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.overlay_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Text(
                text = stringResource(R.string.overlay_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            // What the user will see once they land in Settings.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.overlay_instruction_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.overlay_instruction_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onEnableClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.overlay_enable_button))
            }
            TextButton(
                onClick = onSkipClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.overlay_skip_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OverlayPermissionScreenPreview() {
    ScrollKillerTheme {
        OverlayPermissionScreen(onEnableClick = {}, onSkipClick = {})
    }
}
