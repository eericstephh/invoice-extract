package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopClientStatementExportManager].
 *
 * The manager is a pure function of files, config and invoices — no daemon,
 * no store — so every case asserts on bytes or on [ClientStatementTotals]:
 * markup math, the zero-fee pass-through, and the RTL SpreadsheetML shape
 * (worksheet name, direction, column order, header block, summary rows).
 */
class DesktopClientStatementExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopClientStatementExportManager()

    @Test
    fun `markup math applies the fee percent on the direct subtotal`() {
        val totals = manager.computeTotals(
            listOf(INVOICE_ONE, INVOICE_TWO),
            agencyFeePercent = 15.0,
        )

        assertEquals(3_000_000.0, totals.subtotal, 0.0)
        assertEquals(450_000.0, totals.feeAmount, 0.0)
        assertEquals(3_450_000.0, totals.grandTotal, 0.0)
    }

    @Test
    fun `fractional fee percent keeps its decimals`() {
        val totals = manager.computeTotals(listOf(INVOICE_ONE), agencyFeePercent = 7.5)

        assertEquals(1_000_000.0, totals.subtotal, 0.0)
        assertEquals(75_000.0, totals.feeAmount, 0.0)
        assertEquals(1_075_000.0, totals.grandTotal, 0.0)
    }

    @Test
    fun `zero markup produces a zero fee and an unchanged grand total`() {
        val totals = manager.computeTotals(
            listOf(INVOICE_ONE, INVOICE_TWO),
            agencyFeePercent = 0.0,
        )

        assertEquals(3_000_000.0, totals.subtotal, 0.0)
        assertEquals(0.0, totals.feeAmount, 0.0)
        assertEquals(3_000_000.0, totals.grandTotal, 0.0)

        val target = tempFolder.newFile("statement-zero.xls")
        manager.exportClientStatement(target, CONFIG_FIFTEEN.copy(agencyFeePercent = 0.0), listOf(INVOICE_ONE))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("کارمزد مدیریت و اجرای آژانس (0٪)"))
        assertTrue(raw.contains(numberCell("0")))
        assertTrue(raw.contains(numberCell("1000000")))
    }

    @Test
    fun `sheet prints the computed totals as numeric cells`() {
        val target = tempFolder.newFile("statement.xls")

        manager.exportClientStatement(
            target,
            CONFIG_FIFTEEN,
            listOf(INVOICE_ONE, INVOICE_TWO),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("3000000")))
        assertTrue(raw.contains(numberCell("450000")))
        assertTrue(raw.contains(numberCell("3450000")))
        assertTrue(raw.contains("جمع کل هزینه‌های مستقیم پروژه"))
        assertTrue(raw.contains("کارمزد مدیریت و اجرای آژانس (15٪)"))
        assertTrue(raw.contains("مبلغ نهایی قابل تسویه توسط کارفرما"))
        // The settlement figure rides the highlighted grand-total style.
        assertTrue(raw.contains("ss:StyleID=\"GrandTotal\""))
    }

    @Test
    fun `worksheet is rtl with the statement name and ordered columns`() {
        val target = tempFolder.newFile("statement-rtl.xls")

        manager.exportClientStatement(target, CONFIG_FIFTEEN, listOf(INVOICE_ONE))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"صورت‌وضعیت پروژه\" ss:RightToLeft=\"1\">"))
        val headers = listOf(
            "ردیف",
            "تاریخ",
            "شماره فاکتور",
            "تأمین‌کننده / فروشنده",
            "شرح هزینه یا اقلام",
            "مبلغ (تومان)",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
        // Amounts are numeric, identity text stays text.
        assertTrue(raw.contains(numberCell("1000000")))
        assertTrue(raw.contains(textCell("شرکت نمونه")))
        assertTrue(raw.contains(textCell("1001")))
    }

    @Test
    fun `header block names the project client count and issue date`() {
        val target = tempFolder.newFile("statement-header.xls")

        manager.exportClientStatement(
            target,
            CONFIG_FIFTEEN,
            listOf(INVOICE_ONE, INVOICE_TWO),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("صورت‌وضعیت هزینه‌های پروژه: کمپین تابستان"))
        assertTrue(raw.contains("کارفرما"))
        assertTrue(raw.contains("شرکت آریا"))
        assertTrue(raw.contains("تاریخ صدور"))
        assertTrue(raw.contains("تعداد فاکتورهای پیوست"))
        // Jalali issue date, e.g. 1404/07/06 — four-digit year, slashed.
        assertTrue(raw.contains(Regex("\\d{4}/\\d{2}/\\d{2}")))
        // Both invoices land as rows with their descriptions.
        assertTrue(raw.contains("هدفون بی‌سیم"))
        assertTrue(raw.contains("کابل شارژ"))
    }

    @Test
    fun `negative and non-finite fees degrade to zero`() {
        assertEquals(
            0.0,
            manager.computeTotals(listOf(INVOICE_ONE), Double.NaN).feeAmount,
            0.0,
        )
        assertEquals(
            0.0,
            manager.computeTotals(listOf(INVOICE_ONE), -5.0).feeAmount,
            0.0,
        )
        assertEquals(
            1_000_000.0,
            manager.computeTotals(listOf(INVOICE_ONE), -5.0).grandTotal,
            0.0,
        )
    }

    @Test
    fun `export creates missing parent directories`() {
        val target = tempFolder.root.resolve("nested/dir/statement.xls")

        manager.exportClientStatement(target, CONFIG_FIFTEEN, listOf(INVOICE_ONE))

        assertTrue(target.isFile)
    }

    /** One typed numeric cell, exactly as the emitter writes it, style aside. */
    private fun numberCell(value: String): String =
        "<Data ss:Type=\"Number\">$value</Data></Cell>"

    /** One typed text cell, exactly as the emitter writes it, style aside. */
    private fun textCell(value: String): String =
        "<Data ss:Type=\"String\">$value</Data></Cell>"

    private companion object {
        val CONFIG_FIFTEEN = ClientStatementConfig(
            clientName = "شرکت آریا",
            projectName = "کمپین تابستان",
            agencyFeePercent = 15.0,
        )

        val INVOICE_ONE = Invoice(
            id = "inv-1",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            clientName = "شرکت آریا",
            projectName = "کمپین تابستان",
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

        val INVOICE_TWO = Invoice(
            id = "inv-2",
            invoiceNumber = "1002",
            date = "1403/05/21",
            sellerName = "فروشگاه دوم",
            clientName = "شرکت آریا",
            projectName = "کمپین تابستان",
            items = listOf(
                InvoiceItem(
                    id = "i2",
                    name = "کابل شارژ",
                    quantity = 4.0,
                    unitPrice = 500_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 2_000_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 2_000_000.0,
            currency = CurrencyType.TOMAN,
        )
    }
}
