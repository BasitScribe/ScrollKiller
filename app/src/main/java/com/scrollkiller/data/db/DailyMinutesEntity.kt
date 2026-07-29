package com.scrollkiller.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One day's scrolling TIME, distilled from that day's raw events before they are pruned (D80).
 *
 * ## Why this table exists
 * `scroll_events` carries the timestamps sessions are computed from, and it is deleted after seven
 * days (invariant 4). `daily_counts` is kept forever but has no timestamps — only a count. So a
 * session-accurate history of *time* had nowhere to live: after a week the evidence was gone, and
 * anything older could only ever be a flat count × 6s guess.
 *
 * This is the missing half. The rollup runs while the raw events still exist, writes the answer
 * here, and lets the prune proceed untouched. Invariant 4 is fully intact — the raw behavioural log
 * still disappears on schedule; what survives is one integer per day, which is the same shape and
 * the same privacy posture `daily_counts` already has.
 *
 * ## Seconds, not minutes
 * The feature is spoken about in minutes and the UI will show minutes, but this stores SECONDS as
 * an integer. Minutes as a REAL would accumulate float error when a screen sums thirty rows, and
 * it would make the "did the rollup change?" comparison in tests an epsilon question. Integer
 * seconds is exact, sums exactly, and converts at the edge.
 *
 * ## Not a column on daily_counts
 * `daily_counts` is PK `(date, platform)` and deliberately mirrors the server table shape (D14), so
 * adding a column would either break that mirror or force the server to grow one. Time is also a
 * per-DAY quantity here, not per-platform: sessions are the user scrolling, and a sitting that
 * moves from Reels to Shorts is one sitting, not two.
 *
 * @param date ISO-8601 `yyyy-MM-dd`, the same INTERIM device-local day key `daily_counts` uses
 *   (D14 / invariant 2 — the server timezone boundary replaces it in Phase 3).
 * @param seconds session-derived seconds spent scrolling that day. See [com.scrollkiller.stats.SessionRoller].
 * @param computedAt when the rollup ran, epoch millis. Diagnostic: it answers "is this row from
 *   before or after the day was complete", which is the one question a suspicious number raises.
 */
@Entity(tableName = "daily_minutes")
data class DailyMinutesEntity(
    @PrimaryKey val date: String,
    val seconds: Long,
    val computedAt: Long,
)
