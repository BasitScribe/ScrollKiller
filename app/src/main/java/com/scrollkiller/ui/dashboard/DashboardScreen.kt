package com.scrollkiller.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.brain.MascotArt
import com.scrollkiller.stats.TimeEstimate

/** The three dashboard destinations. Emoji icons keep us off the material-icons dependency. */
private enum class DashboardTab(val labelRes: Int, val emoji: String) {
    TODAY(R.string.tab_today, "🧠"),
    APPS(R.string.tab_apps, "📱"),
    SETTINGS(R.string.tab_settings, "⚙️"),
}

/**
 * The main dashboard: a Material 3 bottom-nav shell over three tabs (Today / Apps /
 * Settings). All data comes live from [DashboardViewModel] (one Room source of truth).
 *
 * Permission deep-links are hoisted to the Activity (they need Activity context and the
 * onResume re-check), so this stays a pure state→UI function.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    accessibilityEnabled: Boolean,
    canDrawOverlays: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DashboardTab.TODAY) }

    val total by viewModel.total.collectAsState()
    val breakdown by viewModel.breakdown.collectAsState()
    val bubbleEnabled by viewModel.bubbleEnabled.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                DashboardTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.emoji, fontSize = 20.sp) },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            DashboardTab.TODAY -> TodayTab(total, breakdown, padding)
            DashboardTab.APPS -> AppsTab(breakdown, padding)
            DashboardTab.SETTINGS -> SettingsTab(
                accessibilityEnabled = accessibilityEnabled,
                canDrawOverlays = canDrawOverlays,
                bubbleEnabled = bubbleEnabled,
                onToggleBubble = viewModel::setBubbleEnabled,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onClearData = viewModel::clearData,
                contentPadding = padding,
            )
        }
    }
}

/* ----------------------------------------------------------------------------------- */
/* Today                                                                               */
/* ----------------------------------------------------------------------------------- */

@Composable
private fun TodayTab(total: Int, breakdown: List<PlatformCount>, padding: PaddingValues) {
    val brain = BrainState.forCount(total)
    val accent = Color(brain.accentArgb)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        // The mascot hero — the emotional core. Art comes from MascotArt (the single
        // state→drawable mapping) as a pre-scaled bitmap for the device's density, so this
        // is a straight blit rather than the runtime scale a single oversized PNG would cost.
        // HEIGHT-bounded, not size(): the art is trimmed to the character and so is taller
        // than it is wide (D37), and a square box would just reserve empty columns beside it.
        Image(
            painter = painterResource(MascotArt.hero(brain)),
            contentDescription = stringResource(MascotArt.contentDescription(brain)),
            modifier = Modifier.height(MascotArt.HERO_DP.dp),
        )
        Text(
            text = total.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.home_counter_label),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                R.string.today_time_estimate,
                TimeEstimate.minutesLabel(total),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(24.dp))
        // Per-platform breakdown ("Instagram Reels: 24" …).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.today_breakdown_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                breakdown.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.displayName, style = MaterialTheme.typography.bodyLarge)
                            if (row.isBeta) BetaBadge()
                        }
                        Text(
                            row.count.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/* ----------------------------------------------------------------------------------- */
/* Apps                                                                                 */
/* ----------------------------------------------------------------------------------- */

@Composable
private fun AppsTab(breakdown: List<PlatformCount>, padding: PaddingValues) {
    // Magnitude comparison across a few fixed, directly-labeled bars → one recessive
    // accent (identity is carried by the label, not the color); theme-aware for dark mode.
    val max = (breakdown.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.apps_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        breakdown.forEach { row ->
            ScrollBar(
                label = row.displayName,
                count = row.count,
                unitNoun = row.unitNoun,
                isBeta = row.isBeta,
                fraction = row.count.toFloat() / max,
            )
        }
    }
}

/** One labeled horizontal bar: name + count above, a rounded fill on a recessive track. */
@Composable
private fun ScrollBar(
    label: String,
    count: Int,
    unitNoun: String,
    isBeta: Boolean,
    fraction: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (isBeta) BetaBadge()
            }
            Text(
                text = if (count == 1) "1 $unitNoun" else "$count ${unitNoun}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        ) {
            // 4px rounded data-end anchored to the baseline (start); min sliver so a
            // non-zero count is always visible.
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(if (count > 0) 0.04f else 0f, 1f))
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }
    }
}

/* ----------------------------------------------------------------------------------- */
/* Settings                                                                             */
/* ----------------------------------------------------------------------------------- */

@Composable
private fun SettingsTab(
    accessibilityEnabled: Boolean,
    canDrawOverlays: Boolean,
    bubbleEnabled: Boolean,
    onToggleBubble: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onClearData: () -> Unit,
    contentPadding: PaddingValues,
) {
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        // Accessibility service — the detection permission.
        item {
            SettingRow(
                title = stringResource(R.string.settings_accessibility_title),
                subtitle = stringResource(
                    if (accessibilityEnabled) R.string.settings_status_on else R.string.settings_status_off,
                ),
            ) {
                OutlinedButton(onClick = onOpenAccessibilitySettings) {
                    Text(stringResource(R.string.settings_manage))
                }
            }
        }

        // Overlay permission — needed for the bubble to actually draw.
        item {
            SettingRow(
                title = stringResource(R.string.settings_overlay_title),
                subtitle = stringResource(
                    if (canDrawOverlays) R.string.settings_status_on else R.string.settings_status_off,
                ),
            ) {
                OutlinedButton(onClick = onOpenOverlaySettings) {
                    Text(stringResource(R.string.settings_manage))
                }
            }
        }

        // Floating bubble visibility toggle.
        item {
            SettingRow(
                title = stringResource(R.string.settings_bubble_title),
                subtitle = stringResource(R.string.settings_bubble_subtitle),
            ) {
                Switch(checked = bubbleEnabled, onCheckedChange = onToggleBubble)
            }
        }

        item { HorizontalDivider() }

        // Destructive: clear all data.
        item {
            SettingRow(
                title = stringResource(R.string.settings_clear_title),
                subtitle = stringResource(R.string.settings_clear_subtitle),
            ) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.settings_clear_button))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearData()
                    confirmClear = false
                }) { Text(stringResource(R.string.settings_clear_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * A small "BETA" pill shown next to a platform whose count we don't trust yet (D32).
 *
 * Deliberately recessive — `surfaceVariant` on `onSurfaceVariant`, not an alarm colour. It's
 * an honesty marker ("this number is approximate and can't lock your screen"), not a warning,
 * and it must not out-shout the count it sits beside.
 */
@Composable
private fun BetaBadge() {
    Text(
        text = stringResource(R.string.badge_beta),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A settings row: title + subtitle on the left, a control (button/switch) on the right. */
@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        control()
    }
}
