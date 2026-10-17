package com.example.parser

enum class PaymentRail(val displayName: String) {
    UPI("UPI Autopay"),
    NACH("e-NACH / ACH"),
    SI("Standing Instruction"),
    CARD("Card Recurring"),
    OTHER("Other Mandate")
}

enum class Frequency(val displayName: String) {
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly"),
    AS_PRESENTED("As Presented")
}

enum class Confidence(val label: String) {
    HIGH("High Confidence"),
    MEDIUM("Medium Confidence"),
    LOW("Low Confidence")
}

enum class SourceType(val displayName: String) {
    SCREENSHOT("Screenshot OCR"),
    STATEMENT("Bank Statement"),
    MANUAL("Manual Entry")
}

enum class SubscriptionStatus(val displayName: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    CANCELLED("Cancelled")
}

/**
 * In-memory candidate extracted from OCR or Statement.
 * Transient only; only approved and minimal fields are saved to Room.
 */
data class ParsedMandateCandidate(
    val id: String,
    val merchant: String,
    val amount: Double,
    val maxAmount: Double? = null,
    val frequency: Frequency,
    val startDate: String? = null,
    val endDate: String? = null,
    val nextDebitDate: String? = null,
    val nextDebitDateEpochMs: Long? = null,
    val rail: PaymentRail = PaymentRail.UPI,
    val source: SourceType = SourceType.SCREENSHOT,
    val sourceApp: String? = null,
    val last4: String? = null,
    val confidence: Confidence = Confidence.HIGH,
    val confidenceReason: String = "",
    val rawNarration: String? = null
)

data class RawTransactionLine(
    val date: String,
    val description: String,
    val debitAmount: Double = 0.0,
    val creditAmount: Double = 0.0,
    val isDebit: Boolean = debitAmount > 0.0,
    val reference: String? = null,
    val dateEpochMs: Long = 0L
) {
    val amount: Double get() = if (isDebit) debitAmount else creditAmount
}


data class OcrBoundingBox(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
}

data class OcrTextLine(
    val text: String,
    val boundingBox: OcrBoundingBox? = null
)

data class OcrTextBlock(
    val text: String,
    val boundingBox: OcrBoundingBox? = null,
    val lines: List<OcrTextLine> = emptyList()
)

data class OcrDocument(
    val blocks: List<OcrTextBlock> = emptyList()
) {
    val allLines: List<OcrTextLine> get() = blocks.flatMap { it.lines.ifEmpty { listOf(OcrTextLine(it.text, it.boundingBox)) } }
}

