package com.leakproof.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leakproof.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a database exactly as version 2 of the app wrote it, then lets Room migrate it to the
 * current version. Room itself validates the migrated schema, so a wrong ALTER TABLE fails here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration_test.db"

    @Before
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version2DataSurvivesTheMigrationToTheCurrentVersion() = runBlocking {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE subscriptions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, merchant TEXT NOT NULL, amount REAL NOT NULL, " +
                    "frequency TEXT NOT NULL, nextDate TEXT NOT NULL, nextDateEpochMs INTEGER NOT NULL, " +
                    "rail TEXT NOT NULL, source TEXT NOT NULL, last4 TEXT, status TEXT NOT NULL, " +
                    "userConfirmed INTEGER NOT NULL, notes TEXT, maxAmount REAL, trialEndEpochMs INTEGER, " +
                    "lastSeenAtMs INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "INSERT INTO subscriptions (merchant, amount, frequency, nextDate, nextDateEpochMs, rail, source, " +
                    "status, userConfirmed, maxAmount, lastSeenAtMs) " +
                    "VALUES ('Netflix', 649.0, 'MONTHLY', '24 Oct 2026', 1792000000000, 'UPI', 'SCREENSHOT', 'ACTIVE', 1, 1000.0, 5)"
            )
            db.version = 2
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        try {
            val rows = room.subscriptionDao().getAllOnce()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals("Netflix", row.merchant)
            assertEquals(649.0, row.amount, 0.0)
            assertEquals(1000.0, row.maxAmount!!, 0.0)
            // New in version 3: existing rows become plain subscriptions with no loan details
            assertEquals("SUBSCRIPTION", row.kind)
            assertNull(row.installmentsTotal)
            assertNull(row.endEpochMs)
            assertNull(row.interestRatePct)
            // New in version 4: nothing has been matched to a statement yet
            assertNull(row.lastChargedAtMs)
        } finally {
            room.close()
        }
    }
}
