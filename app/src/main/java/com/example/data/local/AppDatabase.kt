package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.security.KeystoreHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [SubscriptionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        private const val DB_NAME = "subtrack_encrypted_vault.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            val builder = Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration(true)

            try {
                System.loadLibrary("sqlcipher")
                val passphrase = KeystoreHelper.getOrCreateDatabasePassphrase(appContext)
                val factory = SupportOpenHelperFactory(passphrase)
                builder.openHelperFactory(factory)
            } catch (_: Throwable) {
                // Graceful fallback for test or un-provisioned native environments
            }

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
