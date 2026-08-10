package com.scrollkiller.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.PlatformRangeTotal
import com.scrollkiller.data.SettingsPrefs
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.stats.Milestone
import com.scrollkiller.stats.Milestones
import com.scrollkiller.stats.StreakCalculator
import com.scrollkiller.stats.Streaks
import com.scrollkiller.stats.TrendBucket
import com.scrollkiller.stats.TrendBuckets
import com.scrollkiller.stats.TrendGrouping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Which window the Insights screen is showing. */
enum class InsightsRange(val grouping: TrendGrouping) {
    /** The last seven days, one bar each. */
    WEEK(TrendGrouping.DAILY),

    /** Four complete calendar weeks plus the current partial one. See [TrendBuckets.weekly]. */
    MONTH(TrendGrouping.WEEKLY),
}

/**
 * Everything the Insights screen draws, as ONE immutable object.
 *
 * Composition reads a finished value — every sum, gap-fill, bucket and streak is computed once per
 * data emission here, never during layout or draw. That is the whole reason this type exists rather
 * than the screen collecting five Flows and deriving as it renders.
 *
 * @param bars the trend series, oldest first. Empty means nothing to draw.
 * @param total short videos across the whole range.
 * @param dailyAverage total over ELAPSED days, so a partial current period does not drag it down.
 * @param streaks current and best, recomputed every emission — never cached (D81).
 * @param perPlatform range totals, biggest first.
 * @param measuredSeconds session-derived time (D80). Only covers days that have a rollup.
 * @param measuredFrom earliest day with a rollup, or null when none has run yet. When this is after
 *   [rangeStart] the time figure covers only PART of the range and the UI must say so.
 * @param limit the user's daily limit, for the reference line.
 */
data class InsightsUiState(
    val range: InsightsRange = InsightsRange.WEEK,
    val bars: List<TrendBucket> = emptyList(),
    val total: Int = 0,
    val dailyAverage: Int = 0,
    val streaks: Streaks = Streaks.NONE,
    val perPlatform: List<PlatformDisplay> = emptyList(),
    val measuredSeconds: Long = 0,
    val measuredFrom: LocalDate? = null,
    val rangeStart: LocalDate = LocalDate.now(),
    val rangeEnd: LocalDate = LocalDate.now(),
    val limit: Int = 100,
    /** Derived every emission, never stored — see [com.scrollkiller.stats.Milestones] and D81. */
    val milestones: List<Milestone> = emptyList(),
) {
    /** True when there is genuinely nothing to show — drives the empty state, not an error. */
    val isEmpty: Boolean get() = total == 0

    /** True when the time figure covers less of the range than the counts do (D80's ramp). */
    val timeIsPartial: Boolean
        get() = measuredFrom == null || measuredFrom.isAfter(rangeStart)

    /** Largest bar, for scaling. At least 1 so an all-zero range cannot divide by zero. */
    val maxBar: Int get() = (bars.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
}

/** One row of the per-app breakdown, already resolved to display text. */
data class PlatformDisplay(
    val displayName: String,
    val total: Int,
    val isBeta: Boolean,
)

/**
 * Feeds the Insights screen from the D80/D81 data layer (D82).
 *
 * Separate from [DashboardViewModel] deliberately: that class already owns today's counts, the
 * guilt line, permission health and every Settings toggle, and folding a second screen's state into
 * it would make it the God-class CLAUDE.md bars. Nothing is shared but the repository.
 *
 * Read-only. Nothing here writes to Room, touches the service, or can affect detection.
 */
class InsightsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as ScrollKillerApp).countRepository

    private val _range = MutableStateFlow(InsightsRange.WEEK)
    val range: StateFlow<InsightsRange> = _range

    fun setRange(range: InsightsRange) {
        _range.value = range
    }

    /**
     * The screen's whole state.
     *
     * `flatMapLatest` on the range: switching windows tears down the old queries and starts the new
     * ones, so exactly one range is ever being observed. Without it both would stay collected and
     * the screen would keep doing work for a window nobody is looking at.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<InsightsUiState> = _range
        .flatMapLatest { range -> stateFor(range) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    private fun stateFor(range: InsightsRange) = run {
        val today = LocalDate.now()
        val start = when (range) {
            InsightsRange.WEEK -> today.minusDays((TrendBuckets.DAILY_WINDOW_DAYS - 1).toLong())
            InsightsRange.MONTH -> TrendBuckets.weeklyRangeStart(today)
        }
        val limit = SettingsPrefs.dailyLimit(getApplication())

        combine(
            repository.observeDailyTotalsBetween(start.toString(), today.toString()),
            repository.observePlatformTotalsBetween(start.toString(), today.toString()),
            repository.observeSecondsBetween(start.toString(), today.toString()),
            repository.observeEarliestRolledDate(),
            // ALL-TIME, not the visible window: a milestone asks "have you ever", which a windowed
            // query cannot answer (D83). The trend above stays windowed so a 7-day chart can never
            // report a 30-day best (D81).
            repository.observeAllDailyTotals(),
        ) { dailyTotals, platformTotals, seconds, earliestRolled, allTime ->
            build(
                range, dailyTotals, platformTotals, seconds, earliestRolled,
                allTime, start, today, limit,
            )
        }
    }

    /**
     * Assemble the immutable model. Pure apart from reading the registry for display names, so the
     * expensive half of this screen is a single pass over at most ~35 map lookups per emission.
     */
    private fun build(
        range: InsightsRange,
        dailyTotals: Map<String, Int>,
        platformTotals: List<PlatformRangeTotal>,
        seconds: Long,
        earliestRolled: String?,
        allTimeTotals: Map<String, Int>,
        start: LocalDate,
        today: LocalDate,
        limit: Int,
    ): InsightsUiState {
        val byDate = dailyTotals.mapKeys { (date, _) -> LocalDate.parse(date) }

        val bars = when (range.grouping) {
            TrendGrouping.DAILY -> TrendBuckets.daily(byDate, start, today)
            TrendGrouping.WEEKLY -> TrendBuckets.weekly(byDate, today)
        }

        val total = bars.sumOf { it.count }
        // Averaged over days that have HAPPENED. Dividing by the nominal window would drag the
        // number down every time the current period is young, which reads as improvement that has
        // not occurred — the same dishonesty the partial-bucket label exists to prevent.
        val elapsedDays = (today.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(1)

        return InsightsUiState(
            range = range,
            bars = bars,
            total = total,
            dailyAverage = total / elapsedDays,
            // Recomputed here every emission. D81 forbids caching it: the day boundary is interim
            // (D14/invariant 2) and a stored streak can become arithmetically false in Phase 3.
            streaks = StreakCalculator.of(byDate, limit, today, start),
            perPlatform = platformTotals.map { row ->
                val spec = PlatformRegistry.specOrNull(row.platform)
                PlatformDisplay(
                    displayName = spec?.displayName ?: row.platform.id,
                    total = row.total,
                    isBeta = spec?.isBeta ?: false,
                )
            },
            measuredSeconds = seconds,
            measuredFrom = earliestRolled?.let(LocalDate::parse),
            rangeStart = start,
            rangeEnd = today,
            limit = limit,
            // Derived, never cached. A stored badge can become arithmetically false when Phase 3
            // moves the day boundary (D81), and the app contradicting its own history is worse
            // than showing no badge at all.
            milestones = Milestones.of(
                allTimeTotals.mapKeys { (date, _) -> LocalDate.parse(date) },
                limit,
                today,
            ),
        )
    }
}
