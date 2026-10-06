package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.model.PaymentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [computeAnalytics].
 *
 * The function is pure apart from its dispatcher hop, so each case is one archive in
 * and one snapshot out, run on the calling thread via `runBlocking`. The contracts
 * that matter most to a financial dashboard get their own cases: money is never split
 * across buckets by an unparseable date, shares always cover the whole spend, and an
 * empty archive renders the empty state instead of dividing by zero.
 */
class DesktopAnalyticsModelsTest {

    @Test
    fun `empty archive yields the empty snapshot`() = runBlocking {
        assertEquals(DashboardAnalytics(), computeAnalytics(emptyList()))
    }

    @Test
    fun `totals count and average the archive`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
                invoice(seller = "ب", total = 2000.0, date = "1403/06/11"),
                invoice(seller = "الف", total = 3000.0, date = "1403/05/02"),
            ),
        )

        assertEquals(6000L, analytics.totalSpend)
        assertEquals(3, analytics.invoiceCount)
        assertEquals(2000L, analytics.averageInvoice)
        assertEquals(2, analytics.vendorCount)
    }

    @Test
    fun `vendors aggregate with shares of the whole spend`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 7500.0),
                invoice(seller = "ب", total = 2500.0),
            ),
        )

        assertEquals(2, analytics.topVendors.size)
        assertEquals("الف", analytics.topVendors[0].name)
        assertEquals(7500L, analytics.topVendors[0].totalAmount)
        assertEquals(75f, analytics.topVendors[0].percentage, 0.001f)
        assertEquals("ب", analytics.topVendors[1].name)
        assertEquals(25f, analytics.topVendors[1].percentage, 0.001f)
    }

    @Test
    fun `blank sellers group apart from the named count`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = null, total = 1000.0),
                invoice(seller = "  ", total = 1000.0),
                invoice(seller = "الف", total = 1000.0),
            ),
        )

        assertEquals(1, analytics.vendorCount)
        assertTrue(analytics.topVendors.any { it.name == "فروشنده نامشخص" })
    }

    @Test
    fun `months bucket by jalali month with persian digits tolerated`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
                invoice(seller = "الف", total = 2000.0, date = "۱۴۰۳/۰۵/۰۲"),
                invoice(seller = "ب", total = 4000.0, date = "1403-06-11"),
            ),
        )

        assertEquals(2, analytics.monthlyExpenses.size)
        assertEquals("مرداد 1403", analytics.monthlyExpenses[0].monthName)
        assertEquals(3000L, analytics.monthlyExpenses[0].totalAmount)
        assertEquals("شهریور 1403", analytics.monthlyExpenses[1].monthName)
        assertEquals(4000L, analytics.monthlyExpenses[1].totalAmount)
    }

    @Test
    fun `unparseable dates drop the bucket but keep the money`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "not a date"),
                invoice(seller = "الف", total = 2000.0, date = null),
            ),
        )

        assertTrue(analytics.monthlyExpenses.isEmpty())
        assertEquals(3000L, analytics.totalSpend)
        assertEquals(1, analytics.topVendors.size)
    }

    @Test
    fun `top items rank by landed amount with quantities summed`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(
                    seller = "الف",
                    total = 5000.0,
                    items = listOf(
                        item(name = "قلم", quantity = 2.0, total = 2000.0),
                        item(name = "دفتر", quantity = 1.0, total = 3000.0),
                    ),
                ),
                invoice(
                    seller = "ب",
                    total = 4000.0,
                    items = listOf(item(name = "قلم", quantity = 3.0, total = 3000.0)),
                ),
            ),
        )

        assertEquals(2, analytics.topItems.size)
        assertEquals("قلم", analytics.topItems[0].name)
        assertEquals(5.0, analytics.topItems[0].quantity, 0.0)
        assertEquals(5000L, analytics.topItems[0].totalAmount)
        assertEquals("دفتر", analytics.topItems[1].name)
    }

    @Test
    fun `out of range years leave the monthly buckets`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
                invoice(seller = "ب", total = 2000.0, date = "1443/01/01"),
            ),
        )

        // The typo still counts as spend — only its bucket is dropped.
        assertEquals(3000L, analytics.totalSpend)
        assertEquals(1, analytics.monthlyExpenses.size)
        assertTrue(analytics.monthlyExpenses[0].monthName.contains("مرداد"))
    }

    @Test
    fun `available years list valid years newest first`() {
        val years = availableYears(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1401/03/10"),
                invoice(seller = "ب", total = 2000.0, date = "1403/05/20"),
                invoice(seller = "ج", total = 3000.0, date = "1402/11/02"),
                invoice(seller = "د", total = 4000.0, date = "1443/01/01"),
                invoice(seller = "هـ", total = 5000.0, date = null),
            ),
        )

        assertEquals(listOf(1403, 1402, 1401), years)
    }

    @Test
    fun `receivables aggregate the unpaid and skip the paid`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0).copy(paymentStatus = PaymentStatus.PENDING),
                invoice(seller = "ب", total = 2000.0).copy(paymentStatus = PaymentStatus.OVERDUE),
                invoice(seller = "ج", total = 4000.0).copy(paymentStatus = PaymentStatus.PAID),
            ),
        )

        assertEquals(3000L, analytics.totalUnpaidAmount)
        assertEquals(2, analytics.unpaidCount)
        assertEquals(1, analytics.overdueCount)
        // The paid record still counts as spend — collection state is not spend.
        assertEquals(7000L, analytics.totalSpend)
    }

    @Test
    fun `empty archive owes nothing`() = runBlocking {
        val analytics = computeAnalytics(emptyList())

        assertEquals(0L, analytics.totalUnpaidAmount)
        assertEquals(0, analytics.unpaidCount)
        assertEquals(0, analytics.overdueCount)
    }

    @Test
    fun `foreign unpaid money joins the receivables in toman`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                Invoice(
                    id = "fx",
                    grandTotal = 100.0,
                    currency = CurrencyType.USD,
                    exchangeRate = 90_000.0,
                    paymentStatus = PaymentStatus.PENDING,
                ),
            ),
        )

        assertEquals(9_000_000L, analytics.totalUnpaidAmount)
        assertEquals(1, analytics.unpaidCount)
    }

    @Test
    fun `english mode groups gregorian dates into western buckets`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "Acme", total = 550.0, date = "2026-10-14"),
                invoice(seller = "Acme", total = 450.0, date = "2026/10/02"),
                invoice(seller = "Beta", total = 1_000.0, date = "2026-11-05"),
            ),
            isEnglish = true,
        )

        assertEquals(2, analytics.monthlyExpenses.size)
        assertEquals("Oct 2026", analytics.monthlyExpenses[0].monthName)
        assertEquals(1_000L, analytics.monthlyExpenses[0].totalAmount)
        assertEquals("Nov 2026", analytics.monthlyExpenses[1].monthName)
        assertEquals(1_000L, analytics.monthlyExpenses[1].totalAmount)
        assertEquals(2_000L, analytics.totalSpend)
    }

    @Test
    fun `english buckets sort chronologically not alphabetically`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "A", total = 100.0, date = "2027-01-10"),
                invoice(seller = "B", total = 100.0, date = "2026-10-10"),
                invoice(seller = "C", total = 100.0, date = "2026-02-10"),
            ),
            isEnglish = true,
        )

        assertEquals(
            listOf("Feb 2026", "Oct 2026", "Jan 2027"),
            analytics.monthlyExpenses.map { it.monthName },
        )
    }

    @Test
    fun `jalali dates drop only their bucket in english mode`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
                invoice(seller = "Acme", total = 500.0, date = "2026-10-14"),
            ),
            isEnglish = true,
        )

        // Totals still count everything; only the monthly vocabulary changes.
        assertEquals(1500L, analytics.totalSpend)
        assertEquals(2, analytics.invoiceCount)
        assertEquals(1, analytics.monthlyExpenses.size)
        assertEquals("Oct 2026", analytics.monthlyExpenses.single().monthName)
    }

    @Test
    fun `gregorian dates drop only their bucket in persian mode`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
                invoice(seller = "Acme", total = 500.0, date = "2026-10-14"),
            ),
        )

        assertEquals(1500L, analytics.totalSpend)
        assertEquals(1, analytics.monthlyExpenses.size)
        assertTrue(analytics.monthlyExpenses.single().monthName.contains("مرداد"))
    }

    @Test
    fun `available years reads the requested calendar`() {
        val invoices = listOf(
            invoice(seller = "الف", total = 1000.0, date = "1403/05/20"),
            invoice(seller = "Acme", total = 500.0, date = "2026-10-14"),
            invoice(seller = "Beta", total = 500.0, date = "2025-01-02"),
        )

        assertEquals(listOf(1403), availableYears(invoices))
        assertEquals(listOf(2026, 2025), availableYears(invoices, isEnglish = true))
    }

    @Test
    fun `out of range gregorian years never bucket`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                invoice(seller = "A", total = 100.0, date = "2143-01-01"),
                invoice(seller = "B", total = 100.0, date = "2026-10-10"),
            ),
            isEnglish = true,
        )

        assertEquals(200L, analytics.totalSpend)
        assertEquals(1, analytics.monthlyExpenses.size)
        assertEquals(listOf(2026), availableYears(
            listOf(invoice(seller = "B", total = 100.0, date = "2026-10-10")),
            isEnglish = true,
        ))
    }

    private fun invoice(
        seller: String?,
        total: Double,
        date: String? = null,
        items: List<InvoiceItem> = emptyList(),
    ): Invoice = Invoice(
        id = "$seller-$total-${items.size}",
        date = date,
        sellerName = seller,
        items = items,
        grandTotal = total,
    )

    private fun item(name: String, quantity: Double, total: Double): InvoiceItem =
        InvoiceItem(
            id = "$name-$quantity",
            name = name,
            quantity = quantity,
            unitPrice = 0.0,
            totalPrice = total,
        )
}
