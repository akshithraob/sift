package com.leakproof.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leakproof.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same upgrade a real user's phone goes through: an SQLCipher-encrypted database written by the
 * previous app version is opened by the current one. Guards against losing data on an update.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMigrationInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "encrypted_migration_test.db"
    private val passphrase = "test-passphrase-not-a-real-key".toByteArray()

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun encryptedVersion3DatabaseKeepsItsRowsWhenOpenedByTheCurrentVersion() = runBlocking {
        // 1. Write a version 3 database through SQLCipher, as the previous release did
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE subscriptions (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, merchant TEXT NOT NULL, amount REAL NOT NULL, " +
                            "frequency TEXT NOT NULL, nextDate TEXT NOT NULL, nextDateEpochMs INTEGER NOT NULL, " +
                            "rail TEXT NOT NULL, source TEXT NOT NULL, last4 TEXT, status TEXT NOT NULL, " +
                            "userConfirmed INTEGER NOT NULL, notes TEXT, maxAmount REAL, trialEndEpochMs INTEGER, " +
                            "lastSeenAtMs INTEGER NOT NULL DEFAULT 0, kind TEXT NOT NULL DEFAULT 'SUBSCRIPTION', " +
                            "installmentsTotal INTEGER, installmentsPaid INTEGER, endEpochMs INTEGER, " +
                            "interestRatePct REAL, outstanding REAL)"
                    )
                    db.execSQL(
                        "INSERT INTO subscriptions (merchant, amount, frequency, nextDate, nextDateEpochMs, rail, source, " +
                            "status, userConfirmed, kind, installmentsTotal, installmentsPaid, interestRatePct) " +
                            "VALUES ('Bajaj Finance', 8000.0, 'MONTHLY', '17 Nov 2026', 1792000000000, 'NACH', 'MANUAL', " +
                            "'ACTIVE', 1, 'EMI', 24, 7, 14.0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        SupportOpenHelperFactory(passphrase).create(config).writableDatabase.close()

        val header = context.getDatabasePath(name).inputStream().use { it.readNBytes(15) }
        assertNotEquals("the test database must really be encrypted", "SQLite format 3", String(header))

        // 2. Open it with the current app database, the way the app does
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        try {
            val row = room.subscriptionDao().getAllOnce().single()

            assertEquals("Bajaj Finance", row.merchant)
            assertEquals(8000.0, row.amount, 0.0)
            assertEquals("EMI", row.kind)
            assertEquals(24, row.installmentsTotal)
            assertEquals(7, row.installmentsPaid)
            assertEquals(14.0, row.interestRatePct!!, 0.0)
            assertNull(row.lastChargedAtMs) // the column added by the version 4 migration
        } finally {
            room.close()
        }
    }
}
