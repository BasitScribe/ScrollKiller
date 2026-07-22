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
}
