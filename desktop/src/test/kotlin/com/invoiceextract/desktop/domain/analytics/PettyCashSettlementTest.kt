package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hermetic tests for [computePettyCashSettlement].
 *
 * The computation is pure — one advance and one invoice list in, one
 * settlement out — so each case asserts the balance arithmetic, the status
 * verdict and the invoice count directly.
 */
class PettyCashSettlementTest {

    @Test
    fun `unspent advance is a surplus of the whole amount`() {
        val settlement = computePettyCashSettlement(
            advanceAmount = 2_500_000L,
            invoices = listOf(
                tomanInvoice(id = "a", total = 1_000_000.0),
                tomanInvoice(id = "b", total = 500_000.0),
            ),
        )

        assertEquals(2_500_000L, settlement.advanceAmount)
        assertEquals(1_500_000L, settlement.totalExpenses)
        assertEquals(1_000_000L, settlement.balance)
        assertEquals(SettlementStatus.SURPLUS, settlement.status)
        assertEquals(2, settlement.invoiceCount)
    }

    @Test
    fun `overspend is a deficit of the difference`() {
        val settlement = computePettyCashSettlement(
            advanceAmount = 1_000_000L,
            invoices = listOf(tomanInvoice(id = "a", total = 1_750_000.0)),
        )

        assertEquals(1_000_000L, settlement.advanceAmount)
        assertEquals(1_750_000L, settlement.totalExpenses)
        assertEquals(-750_000L, settlement.balance)
        assertEquals(SettlementStatus.DEFICIT, settlement.status)
        assertEquals(1, settlement.invoiceCount)
    }

    @Test
    fun `exact spend balances to zero`() {
        val settlement = computePettyCashSettlement(
            advanceAmount = 3_000_000L,
            invoices = listOf(
                tomanInvoice(id = "a", total = 2_000_000.0),
                tomanInvoice(id = "b", total = 1_000_000.0),
            ),
        )

        assertEquals(0L, settlement.balance)
        assertEquals(SettlementStatus.BALANCED, settlement.status)
        assertEquals(2, settlement.invoiceCount)
    }

    @Test
    fun `empty archive leaves the advance untouched`() {
        val settlement = computePettyCashSettlement(
            advanceAmount = 2_000_000L,
            invoices = emptyList(),
        )

        assertEquals(0L, settlement.totalExpenses)
        assertEquals(2_000_000L, settlement.balance)
        assertEquals(SettlementStatus.SURPLUS, settlement.status)
        assertEquals(0, settlement.invoiceCount)
    }

    @Test
    fun `foreign and rial invoices count at their toman equivalents`() {
        val settlement = computePettyCashSettlement(
            advanceAmount = 10_000_000L,
            invoices = listOf(
                Invoice(
                    id = "usd",
                    grandTotal = 100.0,
                    currency = CurrencyType.USD,
                    exchangeRate = 95_000.0,
                ),
                Invoice(
                    id = "rial",
                    grandTotal = 5_000_000.0,
                    currency = CurrencyType.RIAL,
                ),
            ),
        )

        // 9,500,000 + 500,000 = 10,000,000 — the advance to the Rial.
        assertEquals(10_000_000L, settlement.totalExpenses)
        assertEquals(0L, settlement.balance)
        assertEquals(SettlementStatus.BALANCED, settlement.status)
        assertEquals(2, settlement.invoiceCount)
    }

    private fun tomanInvoice(id: String, total: Double): Invoice = Invoice(
        id = id,
        grandTotal = total,
        currency = CurrencyType.TOMAN,
    )
}
