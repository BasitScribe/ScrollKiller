package com.scrollkiller.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access to the local `daily_counts` mirror. */
@Dao
interface DailyCountDao {

    /**
     * Atomically add one to the count for `(date, platform)`, inserting the row at
     * 1 if it doesn't exist yet.
     *
     * Raw SQLite UPSERT rather than Room's `@Upsert` because `@Upsert` REPLACES the
     * row — it can't express `count = count + 1`. Doing it in one statement keeps
     * the increment atomic under concurrent detections.
     */
    @Query(
        "INSERT INTO daily_counts(date, platform, count) VALUES(:date, :platform, 1) " +
            "ON CONFLICT(date, platform) DO UPDATE SET count = count + 1",
    )
    suspend fun increment(date: String, platform: String)

    /** Live total across all platforms for one date. COALESCE so an empty day emits 0. */
    @Query("SELECT COALESCE(SUM(count), 0) FROM daily_counts WHERE date = :date")
    fun observeTotalForDate(date: String): Flow<Int>

    /**
     * Live count for one platform on one date. SUM (not a bare column) so a not-yet-seen
     * platform still yields exactly one row of 0 rather than an empty result set.
     */
    @Query("SELECT COALESCE(SUM(count), 0) FROM daily_counts WHERE date = :date AND platform = :platform")
    fun observeCountForDatePlatform(date: String, platform: String): Flow<Int>

    /**
     * Live per-platform rows for one date, highest count first. Powers the Apps/Today
     * dashboard breakdown. Only platforms with at least one advance today appear (a row
     * is created lazily on first increment), so the UI fills in the rest from the registry.
     */
    @Query("SELECT * FROM daily_counts WHERE date = :date ORDER BY count DESC")
    fun observeCountsForDate(date: String): Flow<List<DailyCountEntity>>

    /* --- RANGE queries (D80) ------------------------------------------------------------
     *
     * Everything above answers about ONE date. The rows have been kept forever since D4, but
     * nothing could read further back than today — the history was stored and unreachable, which
     * is the single blocker that stood between this app and an Insights screen.
     *
     * All three take an INCLUSIVE `from`..`to` rather than a day count. `BETWEEN` on the ISO-8601
     * `yyyy-MM-dd` key is safe because that format sorts lexicographically in the same order it
     * sorts chronologically — the reason D14 chose it. Passing a range instead of "7" or "30" means
     * one query serves both windows and there is no second SQL statement to drift; the windows are
     * the repository's business, not SQLite's.
     */

    /**
     * One row per day in range with its total across every platform, oldest first — the Insights
     * trend series.
     *
     * Days with NO activity are simply absent (a `daily_counts` row is created lazily on first
     * increment). The caller fills the gaps with zero; SQL cannot generate missing dates without a
     * recursive CTE, and doing it in Kotlin keeps this query readable and testable.
     */
    @Query(
        "SELECT date AS date, COALESCE(SUM(count), 0) AS total FROM daily_counts " +
            "WHERE date BETWEEN :from AND :to GROUP BY date ORDER BY date ASC",
    )
    fun observeDailyTotalsBetween(from: String, to: String): Flow<List<DailyTotal>>

    /**
     * Every `(date, platform)` row in range — the detail behind [observeDailyTotalsBetween], for a
     * per-day per-app view. Ordered so a consumer can group by date without re-sorting.
     */
    @Query(
        "SELECT * FROM daily_counts WHERE date BETWEEN :from AND :to " +
            "ORDER BY date ASC, count DESC",
    )
    fun observeCountsBetween(from: String, to: String): Flow<List<DailyCountEntity>>

    /**
     * Totals per platform ACROSS the whole range, biggest first — the Insights per-app breakdown.
     * Collapses the date dimension entirely, which is what makes it a different query rather than
     * something the caller could fold from [observeCountsBetween] without walking every row.
     */
    @Query(
        "SELECT platform AS platform, COALESCE(SUM(count), 0) AS total FROM daily_counts " +
            "WHERE date BETWEEN :from AND :to GROUP BY platform ORDER BY total DESC",
    )
    fun observePlatformTotalsBetween(from: String, to: String): Flow<List<PlatformTotal>>

    /** Wipe all aggregate counts (Settings → Clear data). */
    @Query("DELETE FROM daily_counts")
    suspend fun deleteAll()
}

/** One day's grand total across platforms. Projection for [DailyCountDao.observeDailyTotalsBetween]. */
data class DailyTotal(val date: String, val total: Int)

/** One platform's total over a range. Projection for [DailyCountDao.observePlatformTotalsBetween]. */
data class PlatformTotal(val platform: String, val total: Int)
