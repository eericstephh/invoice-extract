package com.invoiceextract.desktop.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hermetic tests for [AmountFormatter.formatUsd]: Western rendering is grouped,
 * two-decimal, dollar-prefixed and locale-proof.
 */
class AmountFormatterTest {

    @Test
    fun `whole dollars carry two decimals`() {
        assertEquals("$550.00", AmountFormatter.formatUsd(550.0))
        assertEquals("$0.00", AmountFormatter.formatUsd(0.0))
    }

    @Test
    fun `thousands group with a comma`() {
        assertEquals("$12,450.00", AmountFormatter.formatUsd(12_450.0))
        assertEquals("$1,250,000.75", AmountFormatter.formatUsd(1_250_000.75))
    }

    @Test
    fun `non-finite amounts degrade to zero dollars`() {
        assertEquals("$0.00", AmountFormatter.formatUsd(Double.NaN))
        assertEquals("$0.00", AmountFormatter.formatUsd(Double.POSITIVE_INFINITY))
    }
}
