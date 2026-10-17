package com.example.util

import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {

    private val indianFormat: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = if (maximumFractionDigits == 0) 0 else 0
        }
    }

    private val indianFormatNoDecimals: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    }

    fun format(amount: Double, includeDecimals: Boolean = false): String {
        return try {
            if (includeDecimals || amount % 1.0 != 0.0) {
                indianFormat.format(amount).replace("INR", "₹").trim()
            } else {
                indianFormatNoDecimals.format(amount).replace("INR", "₹").trim()
            }
        } catch (_: Exception) {
            "₹${String.format(Locale.ENGLISH, "%.2f", amount)}"
        }
    }
}
