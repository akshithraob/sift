package com.leakproof.app

import com.leakproof.app.util.CurrencyFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatterTest {

    @Test
    fun wholeRupeesShowNoDecimals() {
        assertEquals("₹149", CurrencyFormatter.format(149.0))
        assertEquals("₹2,500", CurrencyFormatter.format(2500.0))
    }

    @Test
    fun paiseAlwaysShowTwoDigits() {
        assertEquals("₹5,027.10", CurrencyFormatter.format(5027.1))
        assertEquals("₹1,460.67", CurrencyFormatter.format(1460.67))
    }

    @Test
    fun forcedDecimalsShowTwoDigits() {
        assertEquals("₹149.00", CurrencyFormatter.format(149.0, includeDecimals = true))
    }
}
