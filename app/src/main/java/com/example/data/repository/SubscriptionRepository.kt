package com.example.data.repository

import com.example.data.local.SubscriptionDao
import com.example.data.local.SubscriptionEntity
import com.example.parser.ParsedMandateCandidate
import kotlinx.coroutines.flow.Flow

class SubscriptionRepository(private val dao: SubscriptionDao) {

    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>> = dao.getAllSubscriptions()

    fun getSubscriptionsByStatus(status: String): Flow<List<SubscriptionEntity>> = dao.getSubscriptionsByStatus(status)

    fun getSubscriptionByIdFlow(id: Long): Flow<SubscriptionEntity?> = dao.getSubscriptionByIdFlow(id)

    suspend fun getSubscriptionById(id: Long): SubscriptionEntity? = dao.getSubscriptionById(id)

    suspend fun insertSubscription(entity: SubscriptionEntity): Long = dao.insertSubscription(entity)

    suspend fun updateSubscription(entity: SubscriptionEntity) = dao.updateSubscription(entity)

    suspend fun deleteSubscription(entity: SubscriptionEntity) = dao.deleteSubscription(entity)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun updateStatus(id: Long, status: String) = dao.updateStatus(id, status)

    suspend fun saveConfirmedCandidates(candidates: List<ParsedMandateCandidate>): List<Long> {
        val entities = candidates.map { candidate ->
            SubscriptionEntity(
                merchant = candidate.merchant,
                amount = candidate.amount,
                frequency = candidate.frequency.name,
                nextDate = candidate.nextDebitDate ?: "Upcoming",
                nextDateEpochMs = candidate.nextDebitDateEpochMs ?: (System.currentTimeMillis() + 7L * 24 * 3600 * 1000),
                rail = candidate.rail.name,
                source = candidate.source.name,
                last4 = candidate.last4?.takeLast(4),
                status = "ACTIVE",
                userConfirmed = true
            )
        }
        return dao.insertAll(entities)
    }

    suspend fun seedInitialDataIfEmpty() {
        // Sample starter subscriptions so user immediately tests the dashboard & cancellation UI
        val sampleEntities = listOf(
            SubscriptionEntity(
                merchant = "Netflix India",
                amount = 649.00,
                frequency = "MONTHLY",
                nextDate = "24 Oct 2026",
                nextDateEpochMs = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                rail = "UPI",
                source = "SCREENSHOT",
                last4 = "4821",
                status = "ACTIVE",
                userConfirmed = true,
                notes = "Premium 4K plan Autopay"
            ),
            SubscriptionEntity(
                merchant = "Spotify Premium",
                amount = 119.00,
                frequency = "MONTHLY",
                nextDate = "20 Oct 2026",
                nextDateEpochMs = System.currentTimeMillis() + (3L * 24 * 3600 * 1000),
                rail = "UPI",
                source = "SCREENSHOT",
                last4 = "9012",
                status = "ACTIVE",
                userConfirmed = true,
                notes = "Individual student/personal plan"
            ),
            SubscriptionEntity(
                merchant = "Zerodha Coin (SIP)",
                amount = 5000.00,
                frequency = "MONTHLY",
                nextDate = "10 Nov 2026",
                nextDateEpochMs = System.currentTimeMillis() + (24L * 24 * 3600 * 1000),
                rail = "NACH",
                source = "STATEMENT",
                last4 = "4821",
                status = "ACTIVE",
                userConfirmed = true,
                notes = "Nifty 50 Index Fund e-NACH mandate"
            ),
            SubscriptionEntity(
                merchant = "BESCOM Electricity",
                amount = 1850.00,
                frequency = "MONTHLY",
                nextDate = "02 Nov 2026",
                nextDateEpochMs = System.currentTimeMillis() + (16L * 24 * 3600 * 1000),
                rail = "SI",
                source = "STATEMENT",
                last4 = "4821",
                status = "ACTIVE",
                userConfirmed = true,
                notes = "Standing instruction for utility bill"
            ),
            SubscriptionEntity(
                merchant = "Cult.fit",
                amount = 1499.00,
                frequency = "MONTHLY",
                nextDate = "28 Oct 2026",
                nextDateEpochMs = System.currentTimeMillis() + (11L * 24 * 3600 * 1000),
                rail = "UPI",
                source = "MANUAL",
                last4 = "3310",
                status = "ACTIVE",
                userConfirmed = true,
                notes = "Cult elite membership"
            )
        )
        dao.insertAll(sampleEntities)
    }
}
