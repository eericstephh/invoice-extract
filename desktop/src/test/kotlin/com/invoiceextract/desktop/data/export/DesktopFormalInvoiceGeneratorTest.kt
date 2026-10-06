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
 * Hermetic JVM tests for [DesktopFormalInvoiceGenerator].
 *
 * The generator is a pure function of one invoice into bytes, so every case
 * asserts on the document text: the official template sections, both identity
 * boxes, the line rows, the totals with the amount spelled out in words, and
 * the dual signature blocks.
 */
class DesktopFormalInvoiceGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val generator = DesktopFormalInvoiceGenerator()

    @Test
    fun `document is a standalone rtl a4 sheet with print triggers`() {
        val target = tempFolder.newFile("formal.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<!DOCTYPE html>"))
        assertTrue(raw.contains("dir=\"rtl\""))
        assertTrue(raw.contains("@page { size: A4 portrait; margin: 10mm; }"))
        assertTrue(raw.contains("window.print()"))
        assertTrue(raw.contains("صورتحساب فروش کالا و خدمات"))
        assertTrue(raw.contains("ماده ۱۶۹"))
    }

    @Test
    fun `header carries number date and attribution`() {
        val target = tempFolder.newFile("formal-header.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("1001"))
        assertTrue(raw.contains("1403/05/20"))
        assertTrue(raw.contains("شرکت آریا"))
        assertTrue(raw.contains("کمپین تابستان"))
    }

    @Test
    fun `seller and buyer boxes name every identity field`() {
        val target = tempFolder.newFile("formal-parties.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("مشخصات فروشنده"))
        assertTrue(raw.contains("مشخصات خریدار"))
        assertTrue(raw.contains("شرکت نمونه"))
        assertTrue(raw.contains("علی محمدی"))
        assertTrue(raw.contains("شناسه / کد ملی"))
        assertTrue(raw.contains("شماره اقتصادی"))
        assertTrue(raw.contains("کد پستی"))
        assertTrue(raw.contains("نشانی"))
    }

    @Test
    fun `items table lists every line with its amounts`() {
        val target = tempFolder.newFile("formal-items.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("شرح کالا یا خدمات"))
        assertTrue(raw.contains("مبلغ واحد (تومان)"))
        assertTrue(raw.contains("مالیات و عوارض"))
        assertTrue(raw.contains("هدفون بی‌سیم"))
        assertTrue(raw.contains("کابل شارژ"))
        assertTrue(raw.contains("3000000"))
    }

    @Test
    fun `summary spells the grand total in persian words`() {
        val target = tempFolder.newFile("formal-summary.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("جمع کل ناخالص"))
        assertTrue(raw.contains("مجموع تخفیفات"))
        assertTrue(raw.contains("مالیات بر ارزش افزوده"))
        assertTrue(raw.contains("مبلغ نهایی فاکتور"))
        assertTrue(raw.contains("مبلغ کل به حروف: سه میلیون و سیصد هزار تومان"))
    }

    @Test
    fun `footer carries both signature and stamp boxes`() {
        val target = tempFolder.newFile("formal-sign.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("مهر و امضای فروشنده"))
        assertTrue(raw.contains("مهر و امضای خریدار"))
    }

    @Test
    fun `sheets never carry an evaluation footer`() {
        val target = tempFolder.newFile("formal-clean.html")

        generator.generateFormalInvoiceHtml(target, SAMPLE_INVOICE).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertFalse(raw.contains("فاقد اعتبار تجاری"))
        assertFalse(raw.contains("demo-note"))
    }

    @Test
    fun `markup in names cannot break the document`() {
        val target = tempFolder.newFile("formal-escape.html")
        val tricky = SAMPLE_INVOICE.copy(
            sellerName = "الف <ب> & \"ج\"",
            items = listOf(
                InvoiceItem(
                    id = "i9",
                    name = "<img>",
                    quantity = 1.0,
                    unitPrice = 10.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 10.0,
                    confidence = 0.9f,
                ),
            ),
        )

        generator.generateFormalInvoiceHtml(target, tricky).getOrThrow()

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("الف &lt;ب&gt; &amp; &quot;ج&quot;"))
        assertTrue(raw.contains("&lt;img&gt;"))
    }

    private companion object {
        val SAMPLE_INVOICE = Invoice(
            id = "inv-1",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            sellerNationalId = "14008238774",
            buyerName = "علی محمدی",
            buyerNationalId = "1234567890",
            clientName = "شرکت آریا",
            projectName = "کمپین تابستان",
            items = listOf(
                InvoiceItem(
                    id = "i1",
                    name = "هدفون بی‌سیم",
                    quantity = 2.0,
                    unitPrice = 1_500_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 3_000_000.0,
                    confidence = 0.9f,
                ),
                InvoiceItem(
                    id = "i2",
                    name = "کابل شارژ",
                    quantity = 1.0,
                    unitPrice = 300_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 300_000.0,
                    confidence = 0.9f,
                ),
            ),
            subtotal = 3_300_000.0,
            totalTax = 0.0,
            totalDiscount = 0.0,
            grandTotal = 3_300_000.0,
            currency = CurrencyType.TOMAN,
        )
    }
}
