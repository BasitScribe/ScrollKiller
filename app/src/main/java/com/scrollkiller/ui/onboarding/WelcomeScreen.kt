package com.scrollkiller.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.brain.MascotArt
import com.scrollkiller.ui.theme.Brand
import com.scrollkiller.ui.theme.ScrollKillerTheme

/**
 * The first screen on a fresh install, shown BEFORE [DisclosureScreen] (D78).
 *
 * ## Why this exists
 * The app used to open on the disclosure, which meant the very first thing a new user saw was a
 * request to switch on an Accessibility Service — the most invasive permission Android offers —
 * before they had been shown a single thing of value. That is the anti-pattern the digital-wellbeing
 * category has spent years unlearning, and it was almost certainly this app's largest first-run
 * drop-off.
 *
 * ## Why adding it does not touch invariant 5
 * This screen REQUESTS NOTHING. It explains what the app does and hands off to the disclosure,
 * which still runs in full before the user is sent to Settings to enable anything. Play policy
 * requires the disclosure to precede *enabling*; it does not require it to be the first pixel the
 * app ever draws. [OnboardingRoute] pins that ordering with a test, because "we added a screen in
 * front of the policy screen" is the kind of change that must not rely on a reviewer noticing.
 *
 * ## The dead counter
 * The preview card shows a real zero, not a fake number ticking up. A staged demo would be the
 * beginning of the app's relationship with the user, and this app's entire pitch is that its
 * numbers are honest — see STORE_COPY.md. A zero that says "counting starts when you turn it on"
 * makes the same point and is true.
 *
 * @param onContinue user tapped through; the caller records that the welcome has been seen and
 *   re-routes to the disclosure.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // HEALTHY, deliberately: this is the state the user is being invited toward, and it is
            // the pose the whole palette was sampled from (D58). Opening on the fried mascot would
            // be a judgement about someone we have not measured yet.
            Image(
                painter = painterResource(MascotArt.hero(BrainState.HEALTHY)),
                contentDescription = stringResource(MascotArt.contentDescription(BrainState.HEALTHY)),
                modifier = Modifier.height(MascotArt.HERO_DP.dp),
            )

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // A real zero. See the class doc for why this is not an animated demo.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.welcome_preview_label),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color(BrainState.HEALTHY.accentArgb),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.welcome_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(Brand.ON_LIGHT_MUTED),
                    )
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.welcome_cta))
            }

            // The privacy line sits UNDER the CTA on purpose: it is the reassurance someone reaches
            // for at the moment they are deciding, not a header they skim past on the way in.
            Text(
                text = stringResource(R.string.welcome_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    ScrollKillerTheme {
        WelcomeScreen(onContinue = {})
    }
}
