package com.scrollkiller

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.data.db.ScrollKillerDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Room DAO — verify the atomic upsert-increment and the
 * date/platform aggregation. Uses an in-memory database so nothing persists.
 *
 * NOTE: requires a connected device/emulator (Room needs real SQLite); does not run
 * in a plain JVM/CI-without-emulator environment.
 */
@RunWith(AndroidJUnit4::class)
class DailyCountDaoTest {

    private lateinit var db: ScrollKillerDatabase
    private lateinit var dao: DailyCountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScrollKillerDatabase::class.java).build()
        dao = db.dailyCountDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun increment_insertsRowAtOne_thenAccumulates() = runBlocking {
        dao.increment("2026-07-22", "instagram")
        assertEquals(1, dao.observeTotalForDate("2026-07-22").first())

        dao.increment("2026-07-22", "instagram")
        dao.increment("2026-07-22", "instagram")
        assertEquals(3, dao.observeTotalForDate("2026-07-22").first())
    }

    @Test
    fun total_sumsAcrossPlatforms_forSameDate() = runBlocking {
        dao.increment("2026-07-22", "instagram")
        dao.increment("2026-07-22", "instagram")
        dao.increment("2026-07-22", "youtube")
        assertEquals(3, dao.observeTotalForDate("2026-07-22").first())
    }

    @Test
    fun dates_areIsolated() = runBlocking {
        dao.increment("2026-07-22", "instagram")
        dao.increment("2026-07-23", "instagram")
        dao.increment("2026-07-23", "instagram")
        assertEquals(1, dao.observeTotalForDate("2026-07-22").first())
        assertEquals(2, dao.observeTotalForDate("2026-07-23").first())
    }

    @Test
    fun emptyDate_returnsZero() = runBlocking {
        assertEquals(0, dao.observeTotalForDate("2026-01-01").first())
    }
}
