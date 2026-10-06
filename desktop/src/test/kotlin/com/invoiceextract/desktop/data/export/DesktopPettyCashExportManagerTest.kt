package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.analytics.computePettyCashSettlement
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopPettyCashExportManager].
 *
 * The manager is a pure function of files, names, a settlement and invoices —
 * no daemon, no store — so every case asserts on bytes: the RTL sheet shape,
 * the summary block figures, the per-invoice table, the verdict sentence and
 * the signature footer both parties sign.
 */
class DesktopPettyCashExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopPettyCashExportManager()

    @Test
    fun `worksheet is rtl with the settlement name and ordered columns`() {
        val target = tempFolder.newFile("petty-cash.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(2_500_000L, listOf(TOMAN_INVOICE)),
            listOf(TOMAN_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"صورتجلسه تسویه تنخواه\" ss:RightToLeft=\"1\">"))
        val headers = listOf(
            "ردیف",
            "تاریخ",
            "شماره فاکتور",
            "تأمین‌کننده / فروشنده",
            "شرح هزینه",
            "مبلغ (تومان)",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
    }

    @Test
    fun `header block names the project client count and settlement date`() {
        val target = tempFolder.newFile("petty-cash-header.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(2_500_000L, listOf(TOMAN_INVOICE, USD_INVOICE)),
            listOf(TOMAN_INVOICE, USD_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("صورتجلسه تسویه تنخواه‌گردان پروژه: کمپین تابستان"))
        assertTrue(raw.contains("کارفرما"))
        assertTrue(raw.contains("شرکت آریا"))
        assertTrue(raw.contains("تاریخ تسویه"))
        assertTrue(raw.contains("تعداد فاکتورهای پیوست"))
        assertTrue(raw.contains(Regex("\\d{4}/\\d{2}/\\d{2}")))
    }

    @Test
    fun `summary block prints advance expenses verdict and absolute balance`() {
        val target = tempFolder.newFile("petty-cash-summary.xls")

        // Advance 2,500,000 against 1,000,000 + 9,500,000 = 10,500,000:
        // a deficit of 8,000,000.
        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(2_500_000L, listOf(TOMAN_INVOICE, USD_INVOICE)),
            listOf(TOMAN_INVOICE, USD_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("مبلغ تنخواه دریافت شده"))
        assertTrue(raw.contains(numberCell("2500000")))
        assertTrue(raw.contains("جمع کل هزینه‌های انجام‌شده"))
        assertTrue(raw.contains(numberCell("10500000")))
        assertTrue(raw.contains("کسری تنخواه — طلب تنخواه‌دار"))
        assertTrue(raw.contains("مانده نهایی تسویه"))
        assertTrue(raw.contains(numberCell("8000000")))
        // The settlement figure rides the highlighted grand-total style.
        assertTrue(raw.contains("ss:StyleID=\"GrandTotal\""))
    }

    @Test
    fun `surplus settlement names the return verdict`() {
        val target = tempFolder.newFile("petty-cash-surplus.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(12_000_000L, listOf(TOMAN_INVOICE)),
            listOf(TOMAN_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("مازاد تنخواه — بازگشت به کارفرما"))
        assertTrue(raw.contains(numberCell("11000000")))
    }

    @Test
    fun `balanced settlement names the exact zero`() {
        val target = tempFolder.newFile("petty-cash-balanced.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(1_000_000L, listOf(TOMAN_INVOICE)),
            listOf(TOMAN_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("متعادل — تسویه کامل، بدون مانده"))
        assertTrue(raw.contains(numberCell("0")))
    }

    @Test
    fun `invoice rows carry converted amounts with fx annotation`() {
        val target = tempFolder.newFile("petty-cash-rows.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(20_000_000L, listOf(TOMAN_INVOICE, USD_INVOICE)),
            listOf(TOMAN_INVOICE, USD_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("1000000")))
        assertTrue(raw.contains(numberCell("9500000")))
        assertTrue(raw.contains("($100.00 @ 95,000)"))
        assertTrue(raw.contains(textCell("شرکت نمونه")))
        assertTrue(raw.contains(textCell("1001")))
    }

    @Test
    fun `signature footer closes the sheet`() {
        val target = tempFolder.newFile("petty-cash-sign.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(1_000_000L, listOf(TOMAN_INVOICE)),
            listOf(TOMAN_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("امضای تنخواه‌دار"))
        assertTrue(raw.contains("امضای مدیر مالی / کارفرما"))
    }

    @Test
    fun `export creates missing parent directories`() {
        val target = tempFolder.root.resolve("nested/dir/petty-cash.xls")

        manager.exportPettyCashSettlement(
            target,
            "شرکت آریا",
            "کمپین تابستان",
            computePettyCashSettlement(1_000_000L, listOf(TOMAN_INVOICE)),
            listOf(TOMAN_INVOICE),
        )

        assertTrue(target.isFile)
    }

    /** One typed numeric cell, exactly as the emitter writes it, style aside. */
    private fun numberCell(value: String): String =
        "<Data ss:Type=\"Number\">$value</Data></Cell>"

    /** One typed text cell, exactly as the emitter writes it, style aside. */
    private fun textCell(value: String): String =
        "<Data ss:Type=\"String\">$value</Data></Cell>"

    private companion object {
        val TOMAN_INVOICE = Invoice(
            id = "inv-toman",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            items = listOf(
                InvoiceItem(
                    id = "i1",
                    name = "هدفون بی‌سیم",
                    quantity = 2.0,
                    unitPrice = 500_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 1_000_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 1_000_000.0,
            currency = CurrencyType.TOMAN,
        )

        val USD_INVOICE = Invoice(
            id = "inv-usd",
            invoiceNumber = "ADS-77",
            date = "1403/05/20",
            sellerName = "Google",
            items = listOf(
                InvoiceItem(
                    id = "i2",
                    name = "Google Ads",
                    quantity = 1.0,
                    unitPrice = 100.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 100.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 100.0,
            currency = CurrencyType.USD,
            exchangeRate = 95_000.0,
        )
    }
}
