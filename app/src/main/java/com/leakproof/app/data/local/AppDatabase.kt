package com.leakproof.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leakproof.app.data.security.KeystoreHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [SubscriptionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        private const val DB_NAME = "leakproof_encrypted_vault.db"

        // v2: mandate limit, free-trial end and "last seen in an import" for the insights features
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN maxAmount REAL")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN trialEndEpochMs INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN lastSeenAtMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            val builder = Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)

            // Fail closed: never fall back to an unencrypted database
            System.loadLibrary("sqlcipher")
            val passphrase = KeystoreHelper.getOrCreateDatabasePassphrase(appContext)
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase))

            return builder.build()
        }

        @Synchronized
        fun wipeDatabase(context: Context) {
            INSTANCE?.close()
            INSTANCE = null
            context.deleteDatabase(DB_NAME)
            KeystoreHelper.wipeAllKeysAndVault(context)
        }
    }
}
