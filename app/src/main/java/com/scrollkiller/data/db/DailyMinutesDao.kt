package com.scrollkiller.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes the per-day scrolling-time aggregate (D80). */
@Dao
interface DailyMinutesDao {

    /**
     * Write a COMPLETED day's rollup, and never overwrite one.
     *
     * IGNORE, not REPLACE, and this is the correctness core of the rollup. The prune deletes events
     * older than a ROLLING seven-day cutoff, so the oldest day loses its events gradually through
     * the day rather than all at once. If a later pass re-rolled that day it would see only the
     * events that had not yet been deleted and would overwrite a complete figure with a partial
     * one — the number would silently shrink days after it was correct.
     *
     * First write wins, therefore, and the first write for a past day is the complete one: a day
     * that has ended still has all of its events for roughly six more days.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: DailyMinutesEntity)

    /**
     * Write TODAY's rollup, overwriting the previous one.
     *
     * The opposite rule, for the opposite reason: today is still accumulating, so each pass has
     * strictly more evidence than the last and must be allowed to replace it. Today's events cannot
     * be pruned — they are hours old, not days — so a partial read is impossible here.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyMinutesEntity)

    /** Seconds per day across an inclusive date range, oldest first. Powers the Insights trend. */
    @Query("SELECT * FROM daily_minutes WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeBetween(from: String, to: String): Flow<List<DailyMinutesEntity>>

    /** Total seconds across an inclusive range. 0 when no day in it has been rolled up yet. */
    @Query("SELECT COALESCE(SUM(seconds), 0) FROM daily_minutes WHERE date BETWEEN :from AND :to")
    fun observeTotalSecondsBetween(from: String, to: String): Flow<Long>

    /**
     * The earliest day that has a rollup, or null when none do.
     *
     * This is what lets B2 draw the ramp honestly: rollups only exist from the day D80 shipped, so
     * every day before this one can show a flat estimate at best. A chart that cannot name that
     * boundary would present two different measurements as one series.
     */
    @Query("SELECT MIN(date) FROM daily_minutes")
    fun observeEarliestRolledDate(): Flow<String?>

    @Query("DELETE FROM daily_minutes")
    suspend fun deleteAll()
}
