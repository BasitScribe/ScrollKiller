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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.scrollkiller.R
import com.scrollkiller.ui.theme.ScrollKillerTheme

/**
 * Disclosure screen shown when the Accessibility Service is NOT enabled.
 *
 * Play policy (and CLAUDE.md invariant #5) require an in-context, plain-language
 * disclosure of what the Accessibility Service does BEFORE we send the user to
 * Settings to enable it. This screen is that disclosure: what it does, what stays
 * private, and why it's needed — then a single button that deep-links to Settings.
 *
 * @param onEnableClick invoked when the user taps the enable button; the caller
 *   fires [AccessibilityStatus.openAccessibilitySettings].
 */
@Composable
fun DisclosureScreen(
    onEnableClick: () -> Unit,
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
                text = stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            DisclosureSection(
                title = stringResource(R.string.disclosure_what_title),
                body = stringResource(R.string.disclosure_what_body),
            )
            DisclosureSection(
                title = stringResource(R.string.disclosure_privacy_title),
                body = stringResource(R.string.disclosure_privacy_body),
            )
            DisclosureSection(
                title = stringResource(R.string.disclosure_why_title),
                body = stringResource(R.string.disclosure_why_body),
            )

            // What the user will see once they land in Settings.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.onboarding_instruction_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.onboarding_instruction_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onEnableClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.disclosure_enable_button))
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, body: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun DisclosureScreenPreview() {
    ScrollKillerTheme {
        DisclosureScreen(onEnableClick = {})
    }
}
