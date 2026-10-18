package com.leakproof.app.parser

/** What a recurring payment is for. Drives how it is shown and which insights apply. */
enum class RecurringKind(val label: String, val plural: String) {
    SUBSCRIPTION("Subscription", "Subscriptions"),
    EMI("EMI / loan", "EMIs & loans"),
    SIP("Investment (SIP)", "Investments"),
    INSURANCE("Insurance", "Insurance");

    companion object {
        fun fromName(name: String?): RecurringKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SUBSCRIPTION
    }
}

/**
 * Guesses the kind from a merchant name or a bank narration. Pure keyword matching on purpose:
 * it is explainable, works offline, and the user can always change the type when adding or editing.
 */
object KindClassifier {

    private fun words(vararg w: String) =
        Regex("(?i)\\b(" + w.joinToString("|") { Regex.escape(it) } + ")\\b")

    private val insurance = words(
        "insurance", "assurance", "lic", "hdfc life", "sbi life", "max life", "icici lombard", "star health",
        "care health", "niva bupa", "acko", "bajaj allianz", "tata aig", "policybazaar", "term plan", "health plan"
    )

    private val sip = words(
        "sip", "mutual fund", "mf", "zerodha coin", "coin", "groww", "kuvera", "et money", "paytm money",
        "nippon", "nps", "ppf", "recurring deposit", "rd", "smallcase", "indmoney", "ach d-?\\s*bse", "bse star"
    )

    private val emi = words(
        "emi", "loan", "bajaj fin", "bajaj finance", "bajaj finserv", "hdfc ltd", "tata capital", "fullerton",
        "poonawalla", "hero fincorp", "idfc first", "l&t finance", "lt finance", "muthoot", "kreditbee", "moneyview",
        "home credit", "tvs credit", "chola", "mahindra finance", "aditya birla finance", "piramal", "navi",
        "personal loan", "home loan", "car loan", "two wheeler", "cred emi", "no cost emi", "no-cost emi"
    )

    fun classify(text: String): RecurringKind {
        val t = text.trim()
        return when {
            t.isBlank() -> RecurringKind.SUBSCRIPTION
            insurance.containsMatchIn(t) -> RecurringKind.INSURANCE
            emi.containsMatchIn(t) -> RecurringKind.EMI
            sip.containsMatchIn(t) -> RecurringKind.SIP
            else -> RecurringKind.SUBSCRIPTION
        }
    }
}
