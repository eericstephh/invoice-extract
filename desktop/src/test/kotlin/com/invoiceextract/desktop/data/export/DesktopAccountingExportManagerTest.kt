package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopAccountingExportManager].
 *
 * The manager is a pure function of files and invoices — no daemon, no store — so
 * every case asserts on bytes: header order per template, the Rial conversion, the
 * running line numbering across a batch, XML escaping, and the empty-batch shape.
 */
class DesktopAccountingExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopAccountingExportManager()

    @Test
    fun `sepidar sheet carries the vendor headers in order`() {
        val target = tempFolder.newFile("sepidar.xls")

        manager.exportSepidar(target, TOMAN_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"سپیدار\" ss:RightToLeft=\"1\">"))
        val headers = listOf(
            "کد/نام طرف حساب",
            "شماره فاکتور",
            "تاریخ (YYYY/MM/DD)",
            "کد کالا",
            "شرح کالا یا خدمات",
            "واحد سنجش",
            "تعداد",
            "مبلغ واحد (ریال)",
            "تخفیف",
            "مالیات و عوارض",
            "مبلغ کل سطر (ریال)",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
    }

    @Test
    fun `sepidar converts toman amounts to rial`() {
        // 2 × 1,500,000 Toman headphones: unit and total cross at ×10.
        val target = tempFolder.newFile("sepidar.xls")

        manager.exportSepidar(target, TOMAN_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("15000000")))
        assertTrue(raw.contains(numberCell("30000000")))
        // Exact-cell match: the raw Toman figures must not survive as their own cells.
        assertFalse(raw.contains(numberCell("1500000")))
        assertFalse(raw.contains(numberCell("3000000")))
        // Counterparty prefers the seller, identity columns stay text.
        assertTrue(raw.contains(textCell("شرکت نمونه")))
        assertTrue(raw.contains(textCell("1001")))
    }

    @Test
    fun `sepidar keeps rial invoices unconverted`() {
        val target = tempFolder.newFile("sepidar-rial.xls")

        manager.exportSepidar(target, RIAL_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("2250000")))
        assertTrue(raw.contains(numberCell("4500000")))
        // A ×10 here would mean Toman logic leaked into a Rial invoice.
        assertFalse(raw.contains("22500000"))
        assertFalse(raw.contains("45000000"))
    }

    @Test
    fun `sepidar falls back to buyer when seller is missing`() {
        val target = tempFolder.newFile("sepidar-buyer.xls")

        manager.exportSepidar(target, TOMAN_INVOICE.copy(sellerName = null))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(textCell("علی محمدی")))
    }

    @Test
    fun `sepidar escapes hostile content without breaking the document`() {
        val hostile = TOMAN_INVOICE.copy(
            sellerName = "شرکت <آلفا> & همکاران",
            items = listOf(
                InvoiceItem(
                    id = "h1",
                    name = "کالای \"ویژه\"",
                    quantity = 1.0,
                    unitPrice = 100.0,
                    totalPrice = 100.0,
                ),
            ),
        )
        val target = tempFolder.newFile("hostile.xls")

        manager.exportSepidar(target, hostile)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("شرکت &lt;آلفا&gt; &amp; همکاران"))
        assertTrue(raw.contains("کالای &quot;ویژه&quot;"))
    }

    @Test
    fun `holoo sheet flattens multi-item invoices with running numbers`() {
        val target = tempFolder.newFile("holoo.xls")

        manager.exportHoloo(target, TOMAN_INVOICE)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"هلو\" ss:RightToLeft=\"1\">"))
        val headers = listOf(
            "ردیف",
            "نام طرف حساب",
            "شماره فاکتور",
            "تاریخ",
            "کد کالا",
            "نام کالا",
            "تعداد",
            "قیمت واحد (ریال)",
            "درصد/مبلغ تخفیف",
            "ارزش افزوده",
            "جمع کل سطر",
        )
        var cursor = 0
        headers.forEach { header ->
            val index = raw.indexOf(header, cursor)
            assertTrue("header missing or out of order: $header", index >= cursor)
            cursor = index + header.length
        }
        // Two items → ردیف 1 and 2, each carrying the parent invoice number.
        assertTrue(raw.contains(numberCell("1")))
        assertTrue(raw.contains(numberCell("2")))
        assertTrue(raw.contains("هدفون بی‌سیم"))
        assertTrue(raw.contains("کابل شارژ"))
        assertEquals(2, raw.split("1001").size - 1)
        // Discount and tax ride as Rial amounts on their own columns.
        assertTrue(raw.contains(numberCell("2700000")))
    }

    @Test
    fun `batch consolidation spans invoices without restarting numbers`() {
        val target = tempFolder.newFile("batch-sepidar.xls")

        manager.exportBatchAccounting(target, listOf(TOMAN_INVOICE, RIAL_INVOICE), AccountingTemplate.SEPIDAR)

        val raw = target.readText(StandardCharsets.UTF_8)
        // Two items from the first invoice, one from the second: codes 1, 2, 3.
        assertTrue(raw.contains(numberCell("1")))
        assertTrue(raw.contains(numberCell("2")))
        assertTrue(raw.contains(numberCell("3")))
        // Each row keeps its own parent identity.
        assertTrue(raw.contains("شرکت نمونه"))
        assertTrue(raw.contains("فروشگاه دوم"))
        assertTrue(raw.contains(textCell("1001")))
        assertTrue(raw.contains(textCell("1002")))
    }

    @Test
    fun `batch holoo template flattens with the holoo layout`() {
        val target = tempFolder.newFile("batch-holoo.xls")

        manager.exportBatchAccounting(target, listOf(TOMAN_INVOICE, RIAL_INVOICE), AccountingTemplate.HOLOO)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"هلو\" ss:RightToLeft=\"1\">"))
        assertTrue(raw.contains("ماوس"))
        assertEquals(3, raw.split("<Row>").size - 1 - 1) // rows minus the header row
    }

    @Test
    fun `sepidar emits the mapped warehouse code instead of the line number`() {
        val target = tempFolder.newFile("sepidar-mapped.xls")
        val mapped = TOMAN_INVOICE.copy(
            items = listOf(
                TOMAN_INVOICE.items[0].copy(productCode = "WH-001"),
                TOMAN_INVOICE.items[1],
            ),
        )

        manager.exportSepidar(target, mapped)

        val raw = target.readText(StandardCharsets.UTF_8)
        // Alphanumeric codes travel as text: a Number-typed `WH-001` would corrupt on import.
        assertTrue(raw.contains(textCell("WH-001")))
        assertFalse(raw.contains("<Data ss:Type=\"Number\">WH-001</Data>"))
        // The unmapped second line still falls back to its sequence number.
        assertTrue(raw.contains(numberCell("2")))
    }

    @Test
    fun `holoo keeps the row counter while the code column takes the mapping`() {
        val target = tempFolder.newFile("holoo-mapped.xls")
        val mapped = TOMAN_INVOICE.copy(
            items = listOf(TOMAN_INVOICE.items[0].copy(productCode = "WH-001")),
        )

        manager.exportHoloo(target, mapped)

        val raw = target.readText(StandardCharsets.UTF_8)
        // ردیف stays the running number; کد کالا carries the mapping.
        assertTrue(raw.contains(numberCell("1")))
        assertTrue(raw.contains(textCell("WH-001")))
    }

    @Test
    fun `numeric warehouse codes keep the number cell type`() {
        val target = tempFolder.newFile("sepidar-numeric-code.xls")
        val mapped = TOMAN_INVOICE.copy(
            items = listOf(TOMAN_INVOICE.items[0].copy(productCode = "1001")),
        )

        manager.exportSepidar(target, mapped)

        // Purely numeric codes keep the Number type the importer expects.
        assertTrue(target.readText(StandardCharsets.UTF_8).contains(numberCell("1001")))
    }

    @Test
    fun `blank product codes read as unmapped`() {
        val target = tempFolder.newFile("sepidar-blank-code.xls")
        val mapped = TOMAN_INVOICE.copy(
            items = listOf(TOMAN_INVOICE.items[0].copy(productCode = "   ")),
        )

        manager.exportSepidar(target, mapped)

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("1")))
        assertFalse(raw.contains(textCell("   ")))
    }

    @Test
    fun `empty batch still writes headers`() {
        val sepidar = tempFolder.newFile("empty-sepidar.xls")
        val holoo = tempFolder.newFile("empty-holoo.xls")

        manager.exportBatchAccounting(sepidar, emptyList(), AccountingTemplate.SEPIDAR)
        manager.exportBatchAccounting(holoo, emptyList(), AccountingTemplate.HOLOO)

        assertTrue(sepidar.readText(StandardCharsets.UTF_8).contains("شرح کالا یا خدمات"))
        assertTrue(holoo.readText(StandardCharsets.UTF_8).contains("نام کالا"))
    }

    @Test
    fun `export creates missing parent directories`() {
        val target = tempFolder.root.resolve("nested/dir/sepidar.xls")

        manager.exportSepidar(target, TOMAN_INVOICE)

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
            id = "inv-1",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            buyerName = "علی محمدی",
            items = listOf(
                InvoiceItem(
                    id = "i1",
                    name = "هدفون بی‌سیم",
                    quantity = 2.0,
                    unitPrice = 1_500_000.0,
                    discount = 0.0,
                    tax = 270_000.0,
                    totalPrice = 3_000_000.0,
                    confidence = 0.9f,
                ),
                InvoiceItem(
                    id = "i2",
                    name = "کابل شارژ",
                    quantity = 1.5,
                    unitPrice = 220_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 330_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 3_300_000.0,
            currency = CurrencyType.TOMAN,
        )

        val RIAL_INVOICE = Invoice(
            id = "inv-2",
            invoiceNumber = "1002",
            date = "1403/05/21",
            sellerName = "فروشگاه دوم",
            items = listOf(
                InvoiceItem(
                    id = "i3",
                    name = "ماوس",
                    quantity = 2.0,
                    unitPrice = 2_250_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 4_500_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 4_500_000.0,
            currency = CurrencyType.RIAL,
        )
    }
}
