package com.scrollkiller.ui.dashboard

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.brain.MascotArt
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.guilt.GuiltLocaleCatalog
import com.scrollkiller.permission.PermissionGap
import com.scrollkiller.permission.PermissionHealth
import com.scrollkiller.permission.PermissionHealthReader
import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformSpec
import com.scrollkiller.stats.TimeEstimate
import com.scrollkiller.ui.onboarding.MotionStatus

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
    val guiltLine by viewModel.guiltLine.collectAsState()
    val guiltLocale by viewModel.guiltLocale.collectAsState()
    val dailyLimits by viewModel.dailyLimits.collectAsState()
    val health by viewModel.health.collectAsState()

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
            DashboardTab.TODAY -> TodayTab(total, guiltLine, breakdown, health, padding)
            DashboardTab.APPS -> AppsTab(breakdown, padding)
            DashboardTab.SETTINGS -> SettingsTab(
                accessibilityEnabled = accessibilityEnabled,
                canDrawOverlays = canDrawOverlays,
                bubbleEnabled = bubbleEnabled,
                onToggleBubble = viewModel::setBubbleEnabled,
                blockingPlatforms = viewModel.blockingPlatforms,
                dailyLimits = dailyLimits,
                onSetDailyLimit = viewModel::setDailyLimit,
                guiltLocale = guiltLocale,
                onPickGuiltLocale = viewModel::setGuiltLocale,
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
private fun TodayTab(
    total: Int,
    guiltLine: String?,
    breakdown: List<PlatformCount>,
    health: PermissionHealth,
    padding: PaddingValues,
) {
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
        // ABOVE the mascot, deliberately. If the app cannot do its job, that outranks the number
        // it is showing you — a healthy-looking counter over a dead block is exactly the lie D51
        // was about.
        PermissionBanner(health)
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

        // The app's current line, escalating with the count (D41). Absent — not blank, not a
        // placeholder — below the first threshold: under 50 short videos the app has nothing to
        // say, and saying something anyway is how it stops being believed by the time it does.
        // Same pinned line the bubble is showing at this moment; Home does not draw its own.
        if (guiltLine != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = guiltLine,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
        }

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

/**
 * The guaranteed signal that ScrollKiller cannot do its job (D51).
 *
 * ## Why this layer is the one that matters
 * The out-of-app warning is a notification, and a notification can be denied, disabled, or never
 * posted at all because the service that would post it is off. This banner needs no permission and
 * no running service — it is drawn by the app the user is looking at. So it, not the notification,
 * is the thing that must never be wrong, and it covers the case the notification cannot: "we could
 * not even warn you".
 *
 * ONE Fix button, routed from [PermissionHealth.firstMissing]. Three buttons would ask the user to
 * prioritise a system they cannot see; the app knows the severity order and points at the worst
 * thing.
 *
 * Two tones, because two different things are true. A missing accessibility or overlay permission
 * is an ERROR — the app is not doing what it says. Missing notifications is a WARNING — everything
 * works, we just cannot tell you if it stops. Rendering the second as an error would be crying
 * wolf about the banner that has to be believed the first time.
 */
@Composable
private fun PermissionBanner(health: PermissionHealth) {
    val gap = health.firstMissing ?: return          // healthy: no banner at all
    val context = LocalContext.current
    val degraded = health.isDegraded

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (degraded) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    if (degraded) R.string.health_banner_degraded_title else R.string.health_banner_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            // Names the actual permission. "Something is wrong, tap to fix" with no noun is what
            // people learn to ignore, and this is the one message that has to land first time.
            Text(
                stringResource(
                    when (gap) {
                        PermissionGap.ACCESSIBILITY -> R.string.health_banner_accessibility
                        PermissionGap.OVERLAY -> R.string.health_banner_overlay
                        PermissionGap.NOTIFICATIONS -> R.string.health_banner_notifications
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { PermissionHealthReader.openSettingsFor(context, gap) }) {
                Text(stringResource(R.string.health_banner_fix))
            }
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
    blockingPlatforms: List<PlatformSpec>,
    dailyLimits: Map<Platform, Int>,
    onSetDailyLimit: (Platform, Int) -> Unit,
    guiltLocale: GuiltLocale,
    onPickGuiltLocale: (GuiltLocale) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onClearData: () -> Unit,
    contentPadding: PaddingValues,
) {
    var confirmClear by remember { mutableStateOf(false) }

    // Hoisted out of the LazyColumn: its body is a LazyListScope lambda, not a composable one, so
    // LocalContext cannot be read inside it. Remembered because the hardware cannot change while
    // the screen is open, unlike the permission below it.
    val context = LocalContext.current
    val hasStepSensor = remember { MotionStatus.hasStepSensor(context) }

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

        // Motion access for the physical unlock challenges (D50). Shown ONLY on a device that has
        // a step sensor — asking for a permission that would unlock nothing is worse than staying
        // quiet — and it is the one place the request can happen at all, since an
        // AccessibilityService has no Activity to request a runtime permission from.
        if (hasStepSensor) {
            item {
                var motionGranted by remember { mutableStateOf(MotionStatus.hasPermission(context)) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> motionGranted = granted }

                SettingRow(
                    title = stringResource(R.string.settings_motion_title),
                    subtitle = if (motionGranted) {
                        stringResource(R.string.settings_status_on)
                    } else {
                        stringResource(R.string.settings_motion_subtitle)
                    },
                ) {
                    if (motionGranted) {
                        Text(stringResource(R.string.settings_status_on))
                    } else {
                        OutlinedButton(onClick = { launcher.launch(MotionStatus.PERMISSION) }) {
                            Text(stringResource(R.string.settings_motion_grant))
                        }
                    }
                }
            }
        }

        // Permission alerts (D51) — the out-of-app half of "never fail silently". Requested here
        // rather than in onboarding so the app does not ask for three permissions before it has
        // shown the user anything, and API 33+ only: below that notifications need no grant, the
        // same way MotionStatus handles API 26–28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                var canNotify by remember { mutableStateOf(PermissionHealthReader.canNotify(context)) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { canNotify = PermissionHealthReader.canNotify(context) }

                SettingRow(
                    title = stringResource(R.string.settings_alerts_title),
                    subtitle = if (canNotify) {
                        stringResource(R.string.settings_status_on)
                    } else {
                        stringResource(R.string.settings_alerts_subtitle)
                    },
                ) {
                    if (canNotify) {
                        Text(stringResource(R.string.settings_status_on))
                    } else {
                        OutlinedButton(
                            onClick = { launcher.launch(PermissionHealthReader.NOTIFICATION_PERMISSION) },
                        ) {
                            Text(stringResource(R.string.settings_motion_grant))
                        }
                    }
                }
            }
        }

        // Daily limit, one slider per platform cleared to block (D49). Today that is Instagram
        // and nothing else — driven off blocksAtLimit rather than a hardcoded row, so a BETA
        // platform can never show a limit control it would not honour, and promoting one later
        // needs no change here.
        items(blockingPlatforms, key = { it.platform }) { spec ->
            DailyLimitRow(
                spec = spec,
                limit = dailyLimits[spec.platform] ?: spec.dailyLimit,
                onChange = { onSetDailyLimit(spec.platform, it) },
            )
        }

        // Which guilt pack the lines come from (D43). One option today — the row still ships,
        // because it tells the user whose voice they are hearing and because a control that
        // appears from nowhere the day a second pack lands is a worse introduction than one
        // that was always there.
        item {
            GuiltPackRow(selected = guiltLocale, onPick = onPickGuiltLocale)
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
 * The daily limit for one platform: how many reels before the block screen goes up.
 *
 * A full-width [Slider] under the label rather than beside it — this is the only setting in the
 * app whose value is a NUMBER the user is choosing, and it needs the width to be draggable.
 * Stepped by [BlockLimits.LIMIT_STEP] so the choice stays meaningful (137 does not differ from
 * 140 in any way the user can feel), and the live value is echoed in the subtitle because a
 * slider with no readout is a slider nobody can set deliberately.
 *
 * The write goes through on every change, not on release: [DashboardViewModel.setDailyLimit]
 * persists immediately and the overlay re-reads the pref per emission, so the limit is live on
 * the next reel.
 */
@Composable
private fun DailyLimitRow(spec: PlatformSpec, limit: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_limit_title, spec.displayName),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.settings_limit_subtitle, limit, spec.unitNoun),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = limit.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = BlockLimits.MIN_DAILY_LIMIT.toFloat()..BlockLimits.MAX_DAILY_LIMIT.toFloat(),
            // Slider counts the gaps BETWEEN stops, and both endpoints are stops of their own —
            // hence the -1. Off by one here would silently shift every step off the constant.
            steps = (BlockLimits.MAX_DAILY_LIMIT - BlockLimits.MIN_DAILY_LIMIT) / BlockLimits.LIMIT_STEP - 1,
        )
    }
}

/**
 * Guilt-pack picker: a settings row whose control is a dropdown over [GuiltLocaleCatalog].
 *
 * A plain [DropdownMenu] anchored to a button rather than Material 3's `ExposedDropdownMenuBox`
 * — that one is still experimental API, and this is a one-of-N picker in a settings list, which
 * is the least exotic control there is.
 */
@Composable
private fun GuiltPackRow(selected: GuiltLocale, onPick: (GuiltLocale) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = GuiltLocaleCatalog.optionFor(selected)

    SettingRow(
        title = stringResource(R.string.settings_pack_title),
        subtitle = stringResource(R.string.settings_pack_subtitle),
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(current.labelRes))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GuiltLocaleCatalog.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            expanded = false
                            onPick(option.locale)
                        },
                    )
                }
            }
        }
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
