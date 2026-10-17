package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    val frequency: String, // WEEKLY, MONTHLY, QUARTERLY, YEARLY, AS_PRESENTED
    val nextDate: String,  // Formatted date string (e.g. "24 Oct 2026")
    val nextDateEpochMs: Long,
    val rail: String,      // UPI, NACH, SI, CARD, OTHER
    val source: String,    // SCREENSHOT, STATEMENT, MANUAL
    val last4: String?,    // Maximum 4 digits only, never full account/VPA
    val status: String,    // ACTIVE, PAUSED, CANCELLED
    val userConfirmed: Boolean = true,
    val notes: String? = null
)
