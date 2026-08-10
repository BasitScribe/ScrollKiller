package com.scrollkiller.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.stats.Milestone
import com.scrollkiller.stats.MilestoneId
import com.scrollkiller.stats.TimeEstimate
import com.scrollkiller.stats.TrendBucket
import com.scrollkiller.ui.theme.Brand
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Insights tab (D82): a trend chart, three stat tiles and a per-app breakdown, over a window
 * the user picks.
 *
 * ## Replaces the Apps tab
 * Apps showed per-platform bars for TODAY only — data the Today tab's "By app" card already
 * carries. This screen adds the dimension Apps never had (a range) and so supersedes it; keeping
 * both would have meant two tabs of near-identical bars differing only in time window.
 *
 * ## Chart decisions, and why each one
 * - **ONE series, one hue.** The bars are the daily/weekly total, nothing more. A single series
 *   needs no legend (the heading names it) and, crucially, no categorical palette — which is how
 *   this screen adds a chart without inventing colours D58 forbids.
 * - **Status colours are the only exception**: over-limit bars take [Brand.STATE_CRACKING], the
 *   in-progress bucket takes [Brand.CORAL]. Those encode state, not identity, which is the one
 *   sanctioned reuse of a reserved colour.
 * - **A limit line**, because a bar without the threshold it is judged against is a number with no
 *   verdict — and the limit is the single thing this whole app is about.
 * - **Gridlines and axis rules in the quiet tier** ([Brand.ON_LIGHT_FAINT], D78) — recessive by
 *   construction, never competing with the data.
 * - **Three stat tiles, not three charts.** A single number should not be plotted.
 * - **Per-app rows are directly labelled**, so identity is never carried by colour alone.
 *
 * ## Performance
 * Everything drawn here is precomputed in [InsightsViewModel] into one immutable [InsightsUiState].
 * The bars are weighted [Box]es inside a [Row] — no `Canvas`, no custom measure, nothing derived
 * during layout or draw. Recomposition happens on data change only.
 */
@Composable
fun InsightsTab(
    state: InsightsUiState,
    onRangeChange: (InsightsRange) -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        RangePicker(state.range, onRangeChange)

        if (state.isEmpty) {
            InsightsEmpty()
            return@Column
        }

        HeadlineRow(state)
        TrendChart(state)
        StatTiles(state)
        TimeCaption(state)
        PerAppCard(state)
        MilestonesCard(state)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RangePicker(range: InsightsRange, onChange: (InsightsRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = range == InsightsRange.WEEK,
            onClick = { onChange(InsightsRange.WEEK) },
            label = { Text(stringResource(R.string.insights_range_week)) },
        )
        FilterChip(
            selected = range == InsightsRange.MONTH,
            onClick = { onChange(InsightsRange.MONTH) },
            label = { Text(stringResource(R.string.insights_range_month)) },
        )
    }
}

@Composable
private fun HeadlineRow(state: InsightsUiState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.insights_total, state.total),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.insights_daily_average, state.dailyAverage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The trend. Bars are weighted boxes; the limit line and gridlines are thin [Box]es positioned by
 * weight in a stacked column, so the whole chart is layout rather than drawing.
 */
@Composable
private fun TrendChart(state: InsightsUiState) {
    val faint = Color(Brand.ON_LIGHT_FAINT)
    // The plot is scaled to whichever is taller — the biggest bar or the limit — so the limit line
    // is always ON the chart. Scaling to the bars alone would push it off the top the moment the
    // user was comfortably under, which is exactly when they most want to see the headroom.
    val ceiling = maxOf(state.maxBar, state.limit).coerceAtLeast(1)

    Column {
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
            // Gridlines behind everything, at thirds. Dashed would need a Canvas; a 1dp faint rule
            // reads the same at this size and stays pure layout.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                repeat(GRIDLINES) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(faint))
                }
            }

            // The limit reference, positioned by weight from the top.
            val aboveLimit = (ceiling - state.limit).toFloat().coerceAtLeast(0f)
            if (state.limit in 1..ceiling) {
                Column(Modifier.fillMaxSize()) {
                    if (aboveLimit > 0f) Spacer(Modifier.weight(aboveLimit))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(Brand.STATE_CRACKING).copy(alpha = LIMIT_LINE_ALPHA)),
                    )
                    Spacer(Modifier.weight(state.limit.toFloat()))
                }
            }

            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(BAR_GAP_DP.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                state.bars.forEach { bar -> Bar(bar, ceiling, state.limit, Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(6.dp))
        AxisLabels(state)
    }
}

@Composable
private fun Bar(bar: TrendBucket, ceiling: Int, limit: Int, modifier: Modifier) {
    // Status encodings, not series identity: over-limit is the state the app exists to flag, and
    // the in-progress bucket is called out so an incomplete week never reads as a collapse.
    val colour = when {
        bar.isPartial -> Color(Brand.CORAL)
        bar.count >= limit -> Color(Brand.STATE_CRACKING)
        else -> MaterialTheme.colorScheme.primary
    }
    val fraction = (bar.count.toFloat() / ceiling).coerceIn(0f, 1f)

    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                // A zero day still shows a hairline, so the axis reads as "a day with none"
                // rather than as a gap in the data.
                .fillMaxHeight(fraction.coerceAtLeast(MIN_BAR_FRACTION))
                .clip(RoundedCornerShape(topStart = BAR_RADIUS_DP.dp, topEnd = BAR_RADIUS_DP.dp))
                .background(colour),
        )
    }
}

@Composable
private fun AxisLabels(state: InsightsUiState) {
    val first = state.bars.firstOrNull() ?: return
    val last = state.bars.lastOrNull() ?: return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AxisLabel(formatBucket(first))
        // The in-progress bucket names itself. This is the whole reason a partial week does not
        // read as a drop: the bar is shorter AND the label says why.
        AxisLabel(
            if (last.isPartial && state.range == InsightsRange.MONTH) {
                stringResource(R.string.insights_partial_week)
            } else {
                formatBucket(last)
            },
        )
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatTiles(state: InsightsUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            value = state.streaks.current.toString(),
            label = stringResource(R.string.insights_streak_current),
            accent = Color(BrainState.HEALTHY.accentArgb),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = state.streaks.best.toString(),
            label = stringResource(R.string.insights_streak_best),
            accent = null,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = TimeEstimate.hoursLabel(state.measuredSeconds),
            label = stringResource(R.string.insights_time_measured),
            accent = null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, accent: Color?, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Brand.RADIUS_CARD_DP.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * D80's ramp, said out loud.
 *
 * Rollups only exist from the day D80 shipped, so the time figure can cover less of the range than
 * the counts do. Rather than sum a partial window and present it as the range total — which would
 * blend a measured number with a missing one — the caption names the date it measures from. It
 * disappears once the rollup has caught up with the window.
 */
@Composable
private fun TimeCaption(state: InsightsUiState) {
    if (!state.timeIsPartial) return
    val from = state.measuredFrom
    Text(
        text = if (from == null) {
            stringResource(R.string.insights_time_none)
        } else {
            stringResource(R.string.insights_time_from, DAY_MONTH.format(from))
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PerAppCard(state: InsightsUiState) {
    if (state.perPlatform.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Brand.RADIUS_CARD_DP.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.insights_by_app),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val max = state.perPlatform.maxOf { it.total }.coerceAtLeast(1)
            state.perPlatform.forEach { row ->
                PerAppRow(row, row.total.toFloat() / max)
            }
        }
    }
}

@Composable
private fun PerAppRow(row: PlatformDisplay, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.displayName, style = MaterialTheme.typography.bodyMedium)
                if (row.isBeta) BetaBadge()
            }
            Text(
                row.total.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        // One recessive accent; the NAME above carries identity. Same call AppsTab documented and
        // the bubble's panel already ships, so the three surfaces agree.
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAR_TRACK_DP.dp)
                .clip(RoundedCornerShape(BAR_RADIUS_DP.dp))
                .background(Color(Brand.ON_LIGHT_FAINT)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(BAR_TRACK_DP.dp)
                    .clip(RoundedCornerShape(BAR_RADIUS_DP.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Nothing charted yet. Same tone rule as D78's other empty states: an empty Insights screen means
 * the user has not doomscrolled, which is this app's best outcome — so no error colour, no
 * apology, and copy that explains what will fill it.
 */
@Composable
private fun InsightsEmpty() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.insights_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.insights_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * A bucket's axis label: the day it starts on, in both groupings.
 *
 * Identical for daily and weekly on purpose — a daily bucket starts on the day it IS, and a weekly
 * one is most usefully identified by the Monday it opens. Only the first and last buckets are
 * labelled, so a range reads as "23 Jun … Today" rather than a row of unreadable ticks.
 */
private fun formatBucket(bucket: TrendBucket): String = DAY_MONTH.format(bucket.start)

private const val CHART_HEIGHT_DP = 120
private const val GRIDLINES = 4
private const val BAR_GAP_DP = 4
private const val BAR_RADIUS_DP = 3
private const val BAR_TRACK_DP = 8
private const val MIN_BAR_FRACTION = 0.012f
private const val LIMIT_LINE_ALPHA = 0.65f

/**
 * Milestones (D83) — the warmth, deliberately placed at the BOTTOM of Insights.
 *
 * The block screen keeps the guilt (D33/D49); this is its counterweight. It sits here rather than
 * on its own tab because it answers the same question the rest of this screen does — "how am I
 * doing over time" — and a fourth nav item for seven rows would repeat the mistake the Apps tab
 * was retired for.
 *
 * ## Locked rows show PROGRESS, never a reproach
 * "4 of 7", not "you failed to reach 7". An app that only ever scolds gets uninstalled out of
 * shame, which is what D9 anticipated and D78's research confirmed. An unearned milestone is a
 * thing to walk toward, not a mark against the user.
 */
@Composable
private fun MilestonesCard(state: InsightsUiState) {
    if (state.milestones.isEmpty()) return
    val earned = state.milestones.count { it.achieved }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Brand.RADIUS_CARD_DP.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.milestones_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.milestones_count, earned, state.milestones.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (earned == 0) {
                Text(
                    stringResource(R.string.milestones_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.milestones.forEach { milestone -> MilestoneRow(milestone) }
        }
    }
}

@Composable
private fun MilestoneRow(milestone: Milestone) {
    // Earned rows carry the healthy mint; locked ones stay in the quiet tier so the card reads as
    // a set of things to reach rather than a wall of failures.
    val dot = if (milestone.achieved) Color(BrainState.HEALTHY.accentArgb) else Color(Brand.ON_LIGHT_FAINT)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(MILESTONE_DOT_DP.dp)
                    .height(MILESTONE_DOT_DP.dp)
                    .clip(RoundedCornerShape(MILESTONE_DOT_DP.dp))
                    .background(dot),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(milestoneLabel(milestone.id)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (milestone.achieved) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = if (milestone.achieved) {
                EARNED_MARK
            } else {
                stringResource(R.string.milestones_progress, milestone.progress, milestone.target)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun milestoneLabel(id: MilestoneId): Int = when (id) {
    MilestoneId.FIRST_DAY_UNDER -> R.string.milestone_first_day_under
    MilestoneId.STREAK_3 -> R.string.milestone_streak_3
    MilestoneId.STREAK_7 -> R.string.milestone_streak_7
    MilestoneId.STREAK_14 -> R.string.milestone_streak_14
    MilestoneId.STREAK_30 -> R.string.milestone_streak_30
    MilestoneId.TOTAL_10 -> R.string.milestone_total_10
    MilestoneId.TOTAL_50 -> R.string.milestone_total_50
}

private const val MILESTONE_DOT_DP = 8
private const val EARNED_MARK = "✓"
