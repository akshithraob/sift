package com.leakproof.app.data.local

import androidx.room.ColumnInfo
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
    val notes: String? = null,
    val maxAmount: Double? = null,          // Mandate limit ("up to"), when the source shows one
    val trialEndEpochMs: Long? = null,      // Set for free trials; drives the "trial ends" reminder
    @ColumnInfo(defaultValue = "0")
    val lastSeenAtMs: Long = 0,             // Last time an import still showed this mandate
    @ColumnInfo(defaultValue = "'SUBSCRIPTION'")
    val kind: String = "SUBSCRIPTION",      // SUBSCRIPTION, EMI, SIP, INSURANCE
    val installmentsTotal: Int? = null,     // EMIs: how many instalments in all
    val installmentsPaid: Int? = null,      // EMIs: how many are already paid
    val endEpochMs: Long? = null,           // EMIs/SIPs: when the last debit is due, if known
    val interestRatePct: Double? = null,    // EMIs: yearly interest, if the user knows it
    val outstanding: Double? = null         // EMIs: remaining principal, if the user knows it
)
