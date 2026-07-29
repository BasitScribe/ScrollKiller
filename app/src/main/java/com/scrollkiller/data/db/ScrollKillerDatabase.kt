package com.scrollkiller.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database.
 *
 * v1 — daily_counts aggregate only.
 * v2 — added the raw scroll_events log (invariant #4 / D4): every advance dual-writes
 *      a raw row here and increments the aggregate. See [MIGRATION_1_2].
 * v3 — added guilt_shown, the rolling 7-day guilt-line history (D47). See [MIGRATION_2_3].
 * v4 — added daily_minutes, the per-day scrolling-time rollup (D80). See [MIGRATION_3_4].
 */
@Database(
    entities = [
        DailyCountEntity::class,
        ScrollEvent::class,
        GuiltShownEntity::class,
        DailyMinutesEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ScrollKillerDatabase : RoomDatabase() {

    abstract fun dailyCountDao(): DailyCountDao

    abstract fun scrollEventDao(): ScrollEventDao

    abstract fun guiltShownDao(): GuiltShownDao

    abstract fun dailyMinutesDao(): DailyMinutesDao

    companion object {
        private const val DB_NAME = "scrollkiller.db"

        /**
         * Adds scroll_events. The CREATE statement is written to match Room's own
         * generated schema exactly (column order, `AUTOINCREMENT`, NOT NULL), so Room's
         * on-open identity check passes — a mismatch here throws at runtime.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scroll_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`appPackage` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`countedAs` TEXT NOT NULL)",
                )
            }
        }

        /**
         * Adds guilt_shown (D47). Same rule as [MIGRATION_1_2]: the CREATE statement matches
         * Room's generated schema exactly — column order, NOT NULL, the PK clause — because
         * Room's on-open identity check throws at runtime on any mismatch.
         *
         * Nothing is back-filled. An upgrading user starts with an empty history, which means
         * their first week has no 7-day exclusions — strictly the pre-D47 behaviour they already
         * had, converging as they use the app.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `guilt_shown` (" +
                        "`lineId` TEXT NOT NULL, " +
                        "`shownAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`lineId`))",
                )
            }
        }

        /**
         * Adds daily_minutes (D80): one row per day holding the session-derived seconds spent
         * scrolling, so that number survives the seven-day prune of `scroll_events` that invariant
         * 4 mandates.
         *
         * Same rule as the two migrations above — the CREATE statement matches Room's generated
         * schema exactly (column order, NOT NULL, the PK clause), because Room's on-open identity
         * check throws at runtime on any mismatch. Verified against
         * `app/schemas/.../4.json` rather than by eye.
         *
         * NOTHING IS BACK-FILLED, and it cannot be. The evidence a rollup needs — raw timestamps —
         * has already been pruned for every day older than a week, and for days inside that window
         * the migration runs before the first rollup pass anyway. So an upgrading user starts with
         * an empty time history that fills forward one day at a time. B2 must render that ramp
         * rather than pretend it is not there; `observeEarliestRolledDate` exists to name the
         * boundary. It self-heals in about a month and is permanent only in the sense that the past
         * cannot be recovered — which was already true before this table existed.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_minutes` (" +
                        "`date` TEXT NOT NULL, " +
                        "`seconds` INTEGER NOT NULL, " +
                        "`computedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`date`))",
                )
            }
        }

        fun build(context: Context): ScrollKillerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScrollKillerDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
