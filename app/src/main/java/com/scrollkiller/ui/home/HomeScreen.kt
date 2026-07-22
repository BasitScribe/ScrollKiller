package com.scrollkiller.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.ui.theme.ScrollKillerTheme

/**
 * Home screen — today's reel count rendered as a degrading brain (the app's
 * emotional core). The brain glyph + accent colour come from [BrainState], the same
 * source the floating bubble uses, so the two never disagree.
 *
 * [count] ticks up live as the AccessibilityService detects reel advances.
 */
@Composable
fun HomeScreen(count: Int, modifier: Modifier = Modifier) {
    val brain = BrainState.forCount(count)
    val accent = Color(brain.accentArgb)

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The brain — the hero. Degrades 🧠 -> 🤯 -> 💀 as the count climbs.
            Text(
                text = brain.emoji,
                fontSize = 96.sp,
            )
            // The count stays visible; tinted to the state so number and brain agree.
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.home_counter_label),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            // State label ("Healthy" / "Cracking" / "Fried").
            Text(
                text = stringResource(brainLabelRes(brain)),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Map a [BrainState] to its localizable label. Presentation-only, so it lives here. */
private fun brainLabelRes(state: BrainState): Int = when (state) {
    BrainState.HEALTHY -> R.string.brain_state_healthy
    BrainState.CRACKING -> R.string.brain_state_cracking
    BrainState.FRIED -> R.string.brain_state_fried
}

@Preview(showBackground = true, name = "Healthy (0)")
@Composable
private fun HomeHealthyPreview() {
    ScrollKillerTheme { HomeScreen(count = 0) }
}

@Preview(showBackground = true, name = "Cracking (88)")
@Composable
private fun HomeCrackingPreview() {
    ScrollKillerTheme { HomeScreen(count = 88) }
}

@Preview(showBackground = true, name = "Fried (210)")
@Composable
private fun HomeFriedPreview() {
    ScrollKillerTheme { HomeScreen(count = 210) }
}
