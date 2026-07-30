package com.scrollkiller

import com.scrollkiller.data.CountRepository
import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.data.db.DailyCountEntity
import com.scrollkiller.data.db.DailyMinutesDao
import com.scrollkiller.data.db.DailyMinutesEntity
import com.scrollkiller.data.db.DailyTotal
import com.scrollkiller.data.db.PlatformTotal
import com.scrollkiller.data.db.ScrollEvent
import com.scrollkiller.data.db.ScrollEventDao
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Insights range reads must never disagree with the Today tab about today (D82).
 *
 * ## What this is guarding, and why it is worth a test rather than a device run
 * Today's `daily_counts` row lags the in-memory pending counts by a DB round trip (D39/D65). A
 * range query that read the table directly would draw a final bar one or two LOWER than the hero
 * numeral sitting one tab-switch away — the D35 defect, in the most visible place it could occur.
 *
 * HANDOFF Run O checks this by hand ("switch Today ⇄ Insights while scrolling; they must never
 * disagree"). A human doing that has to catch a discrepancy that exists only for the few hundred
 * milliseconds between an optimistic update and a DB write.
 *
 * ## How this pins it without simulating the timing
 * Rather than race the real merge, the fake DAO answers the TWO READ PATHS DIFFERENTLY on purpose:
 * `observeCountsForDate(today)` — which is what `observeTodaySummary()` reads — returns one number,
 * while the range queries return another for the same day. Only one wiring can then be correct.
 * If the range read composes `observeTodaySummary()` as it must, today's entry is the FORMER; if it
 * ever regresses to trusting its own row, it is the latter, and the assertion names which happened.
 *
 * That is a sharper test than reproducing the lag, because it fails for the structural reason
 * rather than for a timing one — and it needs no clock, no `record()` (whose latency
 * instrumentation touches `SystemClock`, an unmocked stub here) and no new test dependency.
 */
class RangeMergeTest {

    private val today = "2026-07-29"
    private val yesterday = "2026-07-28"
    private val ig = PlatformRegistry.specFor(Platform.INSTAGRAM)
    private val yt = PlatformRegistry.specFor(Platform.YOUTUBE)

    /**
     * A DAO whose writes are invisible to its reads — modelling Room lagging the optimistic cache.
     * Reads are driven by [rows], which the test sets directly to mean "what the table currently
     * holds".
     */
    private class FakeCountDao(private val today: String) : DailyCountDao {
        /** What the RANGE queries see. */
        val rows = MutableStateFlow<List<DailyCountEntity>>(emptyList())

        /**
         * What `observeCountsForDate(today)` sees — i.e. what the Today tab shows, standing in for
         * the merged optimistic value. Deliberately allowed to DIFFER from [rows] so a test can
         * tell which source fed the range's today entry.
         */
        val todayRows = MutableStateFlow<List<DailyCountEntity>>(emptyList())

        override suspend fun increment(date: String, platform: String) = Unit

        override fun observeTotalForDate(date: String): Flow<Int> =
            rows.map { list -> list.filter { it.date == date }.sumOf { it.count } }

        override fun observeCountForDatePlatform(date: String, platform: String): Flow<Int> =
            rows.map { list -> list.filter { it.date == date && it.platform == platform }.sumOf { it.count } }

        override fun observeCountsForDate(date: String): Flow<List<DailyCountEntity>> =
            if (date == today) todayRows
            else rows.map { list -> list.filter { it.date == date }.sortedByDescending { it.count } }

        override fun observeDailyTotalsBetween(from: String, to: String): Flow<List<DailyTotal>> =
            rows.map { list ->
                list.filter { it.date in from..to }
                    .groupBy { it.date }
                    .map { (date, group) -> DailyTotal(date, group.sumOf { it.count }) }
                    .sortedBy { it.date }
            }

        override fun observeCountsBetween(from: String, to: String): Flow<List<DailyCountEntity>> =
            rows.map { list -> list.filter { it.date in from..to } }

        override fun observePlatformTotalsBetween(from: String, to: String): Flow<List<PlatformTotal>> =
            rows.map { list ->
                list.filter { it.date in from..to }
                    .groupBy { it.platform }
                    .map { (platform, group) -> PlatformTotal(platform, group.sumOf { it.count }) }
                    .sortedByDescending { it.total }
            }

        override suspend fun deleteAll() { rows.value = emptyList() }
    }

    private class FakeEventDao : ScrollEventDao {
        override suspend fun insertScroll(event: ScrollEvent) = Unit
        override suspend fun getScrollsSince(sinceMs: Long): List<ScrollEvent> = emptyList()
        override suspend fun getScrollsByPlatform(platform: String): List<ScrollEvent> = emptyList()
        override suspend fun deleteOlderThan(timestampMs: Long): Int = 0
        override suspend fun deleteAll() = Unit
    }

    private class FakeMinutesDao : DailyMinutesDao {
        override suspend fun insertIfAbsent(row: DailyMinutesEntity) = Unit
        override suspend fun upsert(row: DailyMinutesEntity) = Unit
        override fun observeBetween(from: String, to: String): Flow<List<DailyMinutesEntity>> =
            MutableStateFlow(emptyList())
        override fun observeTotalSecondsBetween(from: String, to: String): Flow<Long> =
            MutableStateFlow(0L)
        override fun observeEarliestRolledDate(): Flow<String?> = MutableStateFlow(null)
        override suspend fun deleteAll() = Unit
    }

    private fun repo(dao: FakeCountDao) = CountRepository(
        dao = dao,
        eventDao = FakeEventDao(),
        minutesDao = FakeMinutesDao(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        today = { today },
    )

    private fun daoWith(
        range: List<DailyCountEntity>,
        todayAsSeenByTodayTab: List<DailyCountEntity>,
    ) = FakeCountDao(today).apply {
        rows.value = range
        todayRows.value = todayAsSeenByTodayTab
    }

    @Test
    fun `the range's today entry comes from the Today summary, not from its own row`() {
        // The two paths are given DIFFERENT numbers for today. Only one can win, and which one
        // wins is exactly the wiring under test.
        val dao = daoWith(
            range = listOf(
                DailyCountEntity(yesterday, ig.platform.id, 11),
                DailyCountEntity(today, ig.platform.id, 5),   // stale, as Room would be
            ),
            todayAsSeenByTodayTab = listOf(DailyCountEntity(today, ig.platform.id, 7)),
        )
        val repository = repo(dao)

        runBlocking {
            val todayTotal = repository.observeTodaySummary().first().total
            val range = repository.observeDailyTotalsBetween(yesterday, today).first()

            assertEquals(7, todayTotal)
            assertEquals(
                "today's bar must equal the Today tab's number, not the range query's stale row " +
                    "— this is the D35 defect in its most visible place",
                todayTotal,
                range[today],
            )
        }
    }

    @Test
    fun `days other than today come straight from the range query`() {
        val dao = daoWith(
            range = listOf(
                DailyCountEntity(yesterday, ig.platform.id, 11),
                DailyCountEntity(today, ig.platform.id, 5),
            ),
            todayAsSeenByTodayTab = listOf(DailyCountEntity(today, ig.platform.id, 7)),
        )
        runBlocking {
            val range = repo(dao).observeDailyTotalsBetween(yesterday, today).first()
            assertEquals("yesterday must be untouched by the today merge", 11, range[yesterday])
        }
    }

    @Test
    fun `per-platform range totals count today exactly once`() {
        // The bug this nearly shipped with. The per-platform query collapses the date dimension,
        // so today's contribution is not separable afterwards — adding merged-today on top of a
        // range that ALREADY spanned today would double it. Correct: 11 (yesterday) + 7 (merged
        // today) = 18. A double count would read 23.
        val dao = daoWith(
            range = listOf(
                DailyCountEntity(yesterday, ig.platform.id, 11),
                DailyCountEntity(today, ig.platform.id, 5),
            ),
            todayAsSeenByTodayTab = listOf(DailyCountEntity(today, ig.platform.id, 7)),
        )
        runBlocking {
            val totals = repo(dao).observePlatformTotalsBetween(yesterday, today).first()
            assertEquals(18, totals.single { it.platform == Platform.INSTAGRAM }.total)
        }
    }

    @Test
    fun `per-platform keeps platforms separate under the merge`() {
        val dao = daoWith(
            range = listOf(
                DailyCountEntity(yesterday, ig.platform.id, 6),
                DailyCountEntity(yesterday, yt.platform.id, 2),
                DailyCountEntity(today, ig.platform.id, 1),
            ),
            todayAsSeenByTodayTab = listOf(
                DailyCountEntity(today, ig.platform.id, 1),
                DailyCountEntity(today, yt.platform.id, 1),
            ),
        )
        runBlocking {
            val totals = repo(dao).observePlatformTotalsBetween(yesterday, today).first()
                .associate { it.platform to it.total }
            assertEquals(7, totals[Platform.INSTAGRAM])
            assertEquals("YT's today advance must not land on IG", 3, totals[Platform.YOUTUBE])
        }
    }

    @Test
    fun `a range that excludes today needs no merge and gets none`() {
        val dao = daoWith(
            range = listOf(DailyCountEntity(yesterday, ig.platform.id, 9)),
            todayAsSeenByTodayTab = listOf(DailyCountEntity(today, ig.platform.id, 99)),
        )
        val repository = repo(dao)
        runBlocking {
            assertEquals(
                "today's counts must not leak into a window that excludes today",
                mapOf(yesterday to 9),
                repository.observeDailyTotalsBetween(yesterday, yesterday).first(),
            )
            assertEquals(
                9,
                repository.observePlatformTotalsBetween(yesterday, yesterday).first().single().total,
            )
        }
    }
}
