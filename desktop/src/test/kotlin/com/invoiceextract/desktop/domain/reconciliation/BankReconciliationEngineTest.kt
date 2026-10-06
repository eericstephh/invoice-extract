package com.invoiceextract.desktop.domain.reconciliation

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [matchStatement]: exact-amount linking, the
 * no-double-spend guard, and foreign-money matching through Toman.
 */
class BankReconciliationEngineTest {

    @Test
    fun `an exact deposit settles its invoice at full confidence`() {
        val invoice = invoice(id = "a", total = 1_250_000.0)

        val result = matchStatement(
            transactions = listOf(deposit(1_250_000L)),
            pendingInvoices = listOf(invoice),
        )

        assertEquals(1, result.matched.size)
        assertEquals(invoice, result.matched.single().invoice)
        assertEquals(1.0f, result.matched.single().confidence, 0.0f)
        assertTrue(result.unmatchedInvoices.isEmpty())
        assertTrue(result.unmatchedTransactions.isEmpty())
        assertEquals(1_250_000L, result.totalMatchedAmount)
    }

    @Test
    fun `several invoices each find their own deposit`() {
        val result = matchStatement(
            transactions = listOf(deposit(1_000L), deposit(2_000L), deposit(3_000L)),
            pendingInvoices = listOf(
                invoice(id = "a", total = 1_000.0),
                invoice(id = "b", total = 2_000.0),
                invoice(id = "c", total = 3_000.0),
            ),
        )

        assertEquals(3, result.matched.size)
        assertEquals(6_000L, result.totalMatchedAmount)
        assertTrue(result.unmatchedInvoices.isEmpty())
        assertTrue(result.unmatchedTransactions.isEmpty())
    }

    @Test
    fun `one deposit never settles two identical invoices`() {
        val result = matchStatement(
            transactions = listOf(deposit(5_000L)),
            pendingInvoices = listOf(
                invoice(id = "a", total = 5_000.0),
                invoice(id = "b", total = 5_000.0),
            ),
        )

        assertEquals(1, result.matched.size)
        assertEquals("a", result.matched.single().invoice.id)
        assertEquals(listOf("b"), result.unmatchedInvoices.map { it.id })
        assertTrue(result.unmatchedTransactions.isEmpty())
    }

    @Test
    fun `paid invoices and withdrawals never participate`() {
        val result = matchStatement(
            transactions = listOf(
                BankTransaction("1403/05/20", 9_000L, "واریز"),
                BankTransaction("1403/05/21", -9_000L, "برداشت"),
            ),
            pendingInvoices = listOf(
                invoice(id = "settled", total = 9_000.0, status = PaymentStatus.PAID),
                invoice(id = "open", total = 7_000.0),
            ),
        )

        assertTrue(result.matched.isEmpty())
        assertEquals(listOf("open"), result.unmatchedInvoices.map { it.id })
        assertEquals(2, result.unmatchedTransactions.size)
    }

    @Test
    fun `a dollar invoice matches its rial deposit line in toman`() {
        val invoice = Invoice(
            id = "fx",
            grandTotal = 100.0,
            currency = CurrencyType.USD,
            exchangeRate = 90_000.0,
            paymentStatus = PaymentStatus.OVERDUE,
        )

        val result = matchStatement(
            transactions = listOf(deposit(9_000_000L)),
            pendingInvoices = listOf(invoice),
        )

        assertEquals(1, result.matched.size)
        assertEquals(9_000_000L, result.totalMatchedAmount)
    }

    @Test
    fun `zero and empty inputs match nothing`() {
        val empty = matchStatement(emptyList(), emptyList())
        assertTrue(empty.matched.isEmpty())
        assertEquals(0L, empty.totalMatchedAmount)

        val zero = matchStatement(
            transactions = listOf(deposit(0L)),
            pendingInvoices = listOf(invoice(id = "z", total = 0.0)),
        )
        assertTrue(zero.matched.isEmpty())
        assertEquals(listOf("z"), zero.unmatchedInvoices.map { it.id })
    }

    private fun invoice(
        id: String,
        total: Double,
        status: PaymentStatus = PaymentStatus.PENDING,
    ): Invoice = Invoice(id = id, grandTotal = total, paymentStatus = status)

    private fun deposit(amount: Long): BankTransaction =
        BankTransaction(rawDate = "1403/05/20", amount = amount, description = "واریز")
}
