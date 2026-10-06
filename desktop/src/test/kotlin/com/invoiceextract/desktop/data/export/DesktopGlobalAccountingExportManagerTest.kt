package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopGlobalAccountingExportManager].
 *
 * The manager is a pure function of files and invoices, so every case asserts
 * on bytes: header order per template, decimal money, field escaping, the
 * batch consolidation, and the empty-batch shape.
 */
class DesktopGlobalAccountingExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopGlobalAccountingExportManager()

    @Test
    fun `quickbooks carries the template headers in order`() {
        val target = tempFolder.newFile("qb.csv")

        manager.exportQuickBooks(target, USD_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        val headers = listOf(
            "*Vendor",
            "*InvoiceNo",
            "*InvoiceDate",
            "*DueDate",
            "*ItemName",
            "ItemDescription",
            "ItemQuantity",
            "ItemRate",
            "*ItemAmount",
            "TaxCode",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
    }

    @Test
    fun `quickbooks formats decimals dates and counterparty`() {
        val target = tempFolder.newFile("qb.csv")

        manager.exportQuickBooks(target, USD_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        // $120.50 rate, $241.00 line total, two decimals, locale-proof.
        assertTrue(raw.contains("120.50"))
        assertTrue(raw.contains("241.00"))
        // Seller-led counterparty, ISO-ish dates, due date from the invoice.
        assertTrue(raw.contains("Acme Corp"))
        assertTrue(raw.contains("08/01/2024"))
        assertTrue(raw.contains("08/31/2024"))
        assertTrue(raw.contains("INV-1001"))
        // CRLF records per RFC 4180.
        assertTrue(raw.contains("\r\n"))
    }

    @Test
    fun `quickbooks escapes commas and quotes`() {
        val target = tempFolder.newFile("qb-escape.csv")
        val tricky = USD_INVOICE.copy(
            sellerName = "Acme, \"Global\" Corp",
            items = listOf(
                InvoiceItem(
                    id = "i-1",
                    name = "Widget, large",
                    quantity = 1.0,
                    unitPrice = 10.0,
                    totalPrice = 10.0,
                ),
            ),
        )

        manager.exportQuickBooks(target, tricky)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("\"Acme, \"\"Global\"\" Corp\""))
        assertTrue(raw.contains("\"Widget, large\""))
    }

    @Test
    fun `xero carries its headers line items and currency`() {
        val target = tempFolder.newFile("xero.csv")

        manager.exportXero(target, USD_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        val headers = listOf(
            "*ContactName",
            "*InvoiceNumber",
            "*InvoiceDate",
            "*DueDate",
            "*Description",
            "*Quantity",
            "*UnitAmount",
            "*AccountCode",
            "*TaxType",
            "TaxAmount",
            "Currency",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
        // Taxed line reads Tax on Purchases with its tax amount; currency rides along.
        assertTrue(raw.contains("Tax on Purchases"))
        assertTrue(raw.contains("120.50"))
        assertTrue(raw.contains("USD"))
        assertTrue(raw.contains("INV-1001"))
    }

    @Test
    fun `xero marks untaxed lines zero rated`() {
        val target = tempFolder.newFile("xero-zero.csv")
        val untaxed = USD_INVOICE.copy(
            items = listOf(
                InvoiceItem(
                    id = "i-1",
                    name = "Consulting",
                    quantity = 3.0,
                    unitPrice = 50.0,
                    totalPrice = 150.0,
                ),
            ),
        )

        manager.exportXero(target, untaxed)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("Zero Rated"))
        assertFalse(raw.contains("Tax on Purchases"))
    }

    @Test
    fun `batch consolidates lines across invoices per template`() {
        val quickbooks = tempFolder.newFile("batch-qb.csv")
        val xero = tempFolder.newFile("batch-xero.csv")
        val invoices = listOf(
            USD_INVOICE,
            USD_INVOICE.copy(id = "inv-2", invoiceNumber = "INV-1002", sellerName = "Beta LLC"),
        )

        manager.exportBatchGlobalAccounting(quickbooks, invoices, GlobalAccountingTemplate.QUICKBOOKS)
        manager.exportBatchGlobalAccounting(xero, invoices, GlobalAccountingTemplate.XERO)

        val qb = quickbooks.readText(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        assertTrue(qb.contains("INV-1001"))
        assertTrue(qb.contains("INV-1002"))
        assertTrue(qb.contains("Acme Corp"))
        assertTrue(qb.contains("Beta LLC"))

        val xe = xero.readText(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        assertTrue(xe.contains("INV-1001"))
        assertTrue(xe.contains("INV-1002"))
        // One header row each, no matter the invoice count.
        assertEquals(1, qb.lines().count { it.startsWith("*Vendor") })
        assertEquals(1, xe.lines().count { it.startsWith("*ContactName") })
    }

    @Test
    fun `jalali dates pass through in normalized shape`() {
        val target = tempFolder.newFile("qb-jalali.csv")
        val jalali = USD_INVOICE.copy(date = "1403/05/20", dueDate = "1403/06/31")

        manager.exportQuickBooks(target, jalali)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("05/20/1403"))
        assertTrue(raw.contains("06/31/1403"))
    }

    @Test
    fun `unknown currency stays blank for the base-currency fallback`() {
        val target = tempFolder.newFile("xero-unknown.csv")
        val unknown = USD_INVOICE.copy(currency = CurrencyType.UNKNOWN)

        manager.exportXero(target, unknown)

        val raw = target.readText(StandardCharsets.UTF_8)
        // No fabricated USD: the row ends on the empty currency cell.
        assertTrue(raw.lines().any { it.endsWith(",") })
    }

    private companion object {
        val USD_INVOICE = Invoice(
            id = "inv-1",
            invoiceNumber = "INV-1001",
            date = "2024-08-01",
            dueDate = "2024-08-31",
            sellerName = "Acme Corp",
            buyerName = "Client Co",
            items = listOf(
                InvoiceItem(
                    id = "i-1",
                    name = "Widget",
                    quantity = 2.0,
                    unitPrice = 120.50,
                    tax = 5.0,
                    totalPrice = 241.00,
                ),
            ),
            grandTotal = 241.00,
            currency = CurrencyType.USD,
            paymentStatus = PaymentStatus.PENDING,
        )
    }
}
