package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopCommercialInvoiceGenerator].
 *
 * The generator is a pure function of one invoice into bytes, so every case
 * asserts on the document text: the LTR commercial template, both identity
 * boxes, the line rows, the totals with the amount spelled out in English
 * words, and the signature block.
 */
class DesktopCommercialInvoiceGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val generator = DesktopCommercialInvoiceGenerator()

    @Test
    fun `document is a standalone ltr sheet with print triggers`() {
        val target = tempFolder.newFile("commercial.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<!DOCTYPE html>"))
        assertTrue(raw.contains("dir=\"ltr\""))
        assertTrue(raw.contains("@page { size: auto; margin: 15mm; }"))
        assertTrue(raw.contains("window.print()"))
        assertTrue(raw.contains("COMMERCIAL INVOICE"))
        assertTrue(raw.contains("Inter, Helvetica"))
    }

    @Test
    fun `header carries number dates currency and reference`() {
        val target = tempFolder.newFile("commercial-header.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("INV-2001"))
        assertTrue(raw.contains("2024-08-01"))
        assertTrue(raw.contains("2024-08-31"))
        assertTrue(raw.contains("USD"))
        assertTrue(raw.contains("Website redesign"))
    }

    @Test
    fun `vendor and bill-to boxes name every identity field`() {
        val target = tempFolder.newFile("commercial-parties.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("Vendor / Seller"))
        assertTrue(raw.contains("Bill To / Client"))
        assertTrue(raw.contains("Acme Corp"))
        assertTrue(raw.contains("Client Co"))
        assertTrue(raw.contains("12-3456789"))
        assertTrue(raw.contains("GB123456789"))
        assertTrue(raw.contains("Tax ID / EIN / VAT"))
    }

    @Test
    fun `line rows totals and words read in english`() {
        val target = tempFolder.newFile("commercial-lines.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("Design services"))
        assertTrue(raw.contains("Grand Total Due"))
        assertTrue(raw.contains("Amount in Words:"))
        assertTrue(
            raw.contains("Five Hundred Fifty US Dollars and Zero Cents"),
        )
    }

    @Test
    fun `signature block closes the sheet`() {
        val target = tempFolder.newFile("commercial-sign.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("Authorized Signature"))
    }

    @Test
    fun `sheets never carry an evaluation footer`() {
        val target = tempFolder.newFile("commercial-clean.html")

        generator.generateCommercialInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertFalse(raw.contains("Evaluation copy"))
        assertFalse(raw.contains("demo-note"))
    }

    @Test
    fun `markup in names cannot break the document`() {
        val target = tempFolder.newFile("commercial-escape.html")
        val tricky = SAMPLE_INVOICE.copy(sellerName = "Acme <b> & Sons")

        generator.generateCommercialInvoiceHtml(target, tricky).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("Acme &lt;b&gt; &amp; Sons"))
    }

    private companion object {
        val SAMPLE_INVOICE = Invoice(
            id = "sample-commercial",
            invoiceNumber = "INV-2001",
            date = "2024-08-01",
            dueDate = "2024-08-31",
            sellerName = "Acme Corp",
            sellerTaxId = "12-3456789",
            buyerName = "Client Co",
            buyerTaxId = "GB123456789",
            clientName = "Website redesign",
            items = listOf(
                InvoiceItem(
                    id = "item-1",
                    name = "Design services",
                    quantity = 10.0,
                    unitPrice = 50.0,
                    discount = 0.0,
                    tax = 50.0,
                    totalPrice = 550.0,
                ),
            ),
            subtotal = 500.0,
            totalTax = 50.0,
            totalDiscount = 0.0,
            grandTotal = 550.0,
            currency = CurrencyType.USD,
        )
    }
}
