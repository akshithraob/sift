package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY nextDateEpochMs ASC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE status = :status ORDER BY nextDateEpochMs ASC")
    fun getSubscriptionsByStatus(status: String): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE status = 'ACTIVE' AND nextDateEpochMs >= :from AND nextDateEpochMs < :to")
    suspend fun getActiveRenewingBetween(from: Long, to: Long): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAllOnce(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE status = 'ACTIVE' AND trialEndEpochMs >= :from AND trialEndEpochMs < :to")
    suspend fun getActiveTrialsEndingBetween(from: Long, to: Long): List<SubscriptionEntity>

    @Query("UPDATE subscriptions SET status = 'CANCELLED' WHERE id IN (:ids)")
    suspend fun cancelByIds(ids: List<Long>)

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    fun getSubscriptionByIdFlow(id: Long): Flow<SubscriptionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subscriptions: List<SubscriptionEntity>): List<Long>

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Delete
    suspend fun deleteSubscription(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()

    @Query("UPDATE subscriptions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}
