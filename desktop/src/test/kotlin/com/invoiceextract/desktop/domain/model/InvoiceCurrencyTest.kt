package com.invoiceextract.desktop.domain.model

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.defaultCurrencyFor
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.model.symbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for the multi-currency model: [Invoice.effectiveTomanTotal],
 * the [CurrencyType.symbol] helpers, and the FX field defaults that keep old
 * payloads decoding cleanly.
 */
class InvoiceCurrencyTest {

    @Test
    fun `toman total passes through untouched`() {
        val invoice = base().copy(currency = CurrencyType.TOMAN, grandTotal = 1_250_000.0)

        assertEquals(1_250_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `rial total converts at ten to one`() {
        val invoice = base().copy(currency = CurrencyType.RIAL, grandTotal = 12_500_000.0)

        assertEquals(1_250_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `usd converts by the day rate`() {
        val invoice = base().copy(
            currency = CurrencyType.USD,
            grandTotal = 100.0,
            exchangeRate = 95_000.0,
        )

        assertEquals(9_500_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `eur converts by the day rate`() {
        val invoice = base().copy(
            currency = CurrencyType.EUR,
            grandTotal = 50.0,
            exchangeRate = 102_000.0,
        )

        assertEquals(5_100_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `usdt converts by the day rate`() {
        val invoice = base().copy(
            currency = CurrencyType.USDT,
            grandTotal = 250.0,
            exchangeRate = 96_500.0,
        )

        assertEquals(24_125_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `original foreign amount wins over grand total`() {
        val invoice = base().copy(
            currency = CurrencyType.USD,
            grandTotal = 90.0,
            originalForeignAmount = 100.0,
            exchangeRate = 95_000.0,
        )

        assertEquals(9_500_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `missing rate falls back to one to one`() {
        val invoice = base().copy(
            currency = CurrencyType.EUR,
            grandTotal = 20.0,
            exchangeRate = null,
        )

        assertEquals(20L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `unknown currency reads as local money`() {
        val invoice = base().copy(currency = CurrencyType.UNKNOWN, grandTotal = 7_000.0)

        assertEquals(7_000L, invoice.effectiveTomanTotal)
    }

    @Test
    fun `manual entries default to dollars in english mode`() {
        assertEquals(CurrencyType.USD, defaultCurrencyFor(isEnglish = true))
        assertEquals(CurrencyType.UNKNOWN, defaultCurrencyFor(isEnglish = false))
    }

    @Test
    fun `non-finite inputs degrade to zero`() {
        assertEquals(
            0L,
            base().copy(currency = CurrencyType.USD, grandTotal = Double.NaN, exchangeRate = 95_000.0)
                .effectiveTomanTotal,
        )
        assertEquals(
            0L,
            base().copy(currency = CurrencyType.TOMAN, grandTotal = Double.POSITIVE_INFINITY)
                .effectiveTomanTotal,
        )
        assertEquals(
            0L,
            base().copy(currency = CurrencyType.RIAL, grandTotal = Double.NaN).effectiveTomanTotal,
        )
    }

    @Test
    fun `fx fields default to null for backward compatibility`() {
        val invoice = base()

        assertNull(invoice.exchangeRate)
        assertNull(invoice.originalForeignAmount)
    }

    @Test
    fun `symbols match the spec`() {
        assertEquals("$", CurrencyType.USD.symbol)
        assertEquals("€", CurrencyType.EUR.symbol)
        assertEquals("₮", CurrencyType.USDT.symbol)
        assertEquals("تومان", CurrencyType.TOMAN.symbol)
        assertEquals("ریال", CurrencyType.RIAL.symbol)
    }

    @Test
    fun `only usd eur and usdt read as foreign`() {
        assertTrue(CurrencyType.USD.isForeignCurrency)
        assertTrue(CurrencyType.EUR.isForeignCurrency)
        assertTrue(CurrencyType.USDT.isForeignCurrency)
        assertFalse(CurrencyType.TOMAN.isForeignCurrency)
        assertFalse(CurrencyType.RIAL.isForeignCurrency)
        assertFalse(CurrencyType.UNKNOWN.isForeignCurrency)
    }

    private fun base(): Invoice = Invoice(id = "inv-fx")
}
