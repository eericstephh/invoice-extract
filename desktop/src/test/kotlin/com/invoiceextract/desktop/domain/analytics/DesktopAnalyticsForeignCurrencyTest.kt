package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uniform-Toman aggregation for [computeAnalytics]: foreign and Rial invoices
 * join local ones through [Invoice.effectiveTomanTotal], so no currency ever
 * mixes into the spend, vendor shares, monthly buckets or item ranks.
 */
class DesktopAnalyticsForeignCurrencyTest {

    @Test
    fun `mixed currencies aggregate as toman spend`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                Invoice(
                    id = "usd",
                    sellerName = "Google",
                    date = "1403/05/20",
                    grandTotal = 100.0,
                    currency = CurrencyType.USD,
                    exchangeRate = 95_000.0,
                ),
                Invoice(
                    id = "toman",
                    sellerName = "الف",
                    date = "1403/05/21",
                    grandTotal = 500_000.0,
                    currency = CurrencyType.RIAL,
                ),
                Invoice(
                    id = "local",
                    sellerName = "ب",
                    date = "1403/05/22",
                    grandTotal = 450_000.0,
                    currency = CurrencyType.TOMAN,
                ),
            ),
        )

        // 9,500,000 + 50,000 + 450,000.
        assertEquals(10_000_000L, analytics.totalSpend)
        assertEquals(3_333_333L, analytics.averageInvoice)
        assertEquals("Google", analytics.topVendors[0].name)
        assertEquals(9_500_000L, analytics.topVendors[0].totalAmount)
        assertEquals(95f, analytics.topVendors[0].percentage, 0.001f)
        assertEquals(1, analytics.monthlyExpenses.size)
        assertEquals(10_000_000L, analytics.monthlyExpenses[0].totalAmount)
    }

    @Test
    fun `foreign line items rank in toman`() = runBlocking {
        val analytics = computeAnalytics(
            listOf(
                Invoice(
                    id = "usd",
                    sellerName = "Google",
                    grandTotal = 100.0,
                    currency = CurrencyType.USD,
                    exchangeRate = 95_000.0,
                    items = listOf(
                        InvoiceItem(
                            id = "i1",
                            name = "Google Ads",
                            quantity = 1.0,
                            unitPrice = 100.0,
                            discount = 0.0,
                            tax = 0.0,
                            totalPrice = 100.0,
                            confidence = 0.9f,
                        ),
                    ),
                ),
                Invoice(
                    id = "local",
                    sellerName = "ب",
                    grandTotal = 1_000_000.0,
                    currency = CurrencyType.TOMAN,
                    items = listOf(
                        InvoiceItem(
                            id = "i2",
                            name = "هدفون بی‌سیم",
                            quantity = 1.0,
                            unitPrice = 1_000_000.0,
                            discount = 0.0,
                            tax = 0.0,
                            totalPrice = 1_000_000.0,
                            confidence = 0.9f,
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Google Ads", analytics.topItems[0].name)
        assertEquals(9_500_000L, analytics.topItems[0].totalAmount)
        assertEquals("هدفون بی‌سیم", analytics.topItems[1].name)
        assertEquals(1_000_000L, analytics.topItems[1].totalAmount)
    }
}
