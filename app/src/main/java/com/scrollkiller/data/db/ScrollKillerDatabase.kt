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
 */
@Database(
    entities = [DailyCountEntity::class, ScrollEvent::class, GuiltShownEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class ScrollKillerDatabase : RoomDatabase() {

    abstract fun dailyCountDao(): DailyCountDao

    abstract fun scrollEventDao(): ScrollEventDao

    abstract fun guiltShownDao(): GuiltShownDao

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

        fun build(context: Context): ScrollKillerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScrollKillerDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
