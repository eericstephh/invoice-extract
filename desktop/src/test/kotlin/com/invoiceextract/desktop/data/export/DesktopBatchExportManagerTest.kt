package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopBatchExportManager].
 *
 * Both emitters are pure functions of a file and a list — no daemon, no store — so
 * every case asserts on bytes: the BOM contract, the shared ten-column row layout,
 * flattening across invoices, and escaping of hostile field content.
 */
class DesktopBatchExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopBatchExportManager()

    @Test
    fun `excel ledger carries the consolidated sheet with all rows`() {
        val target = tempFolder.newFile("ledger.xls")

        manager.exportBatchExcel(target, listOf(INVOICE_ONE, INVOICE_TWO))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("<Worksheet ss:Name=\"دفتر تجمیعی فاکتورها\" ss:RightToLeft=\"1\">"))
        // The shared ten-column contract, in order.
        assertTrue(raw.contains("ردیف"))
        assertTrue(raw.contains("کارفرما"))
        assertTrue(raw.contains("پروژه"))
        assertTrue(raw.contains("قیمت واحد (تومان)"))
        // Flattened: two items from the first invoice, one from the second.
        assertTrue(raw.contains("هدفون بی‌سیم"))
        assertTrue(raw.contains("کابل شارژ"))
        assertTrue(raw.contains("ماوس"))
        // Typed numerics, not strings, so Excel can sum the columns.
        assertTrue(raw.contains("ss:Type=\"Number\""))
    }

    @Test
    fun `excel escapes hostile content without breaking the document`() {
        val hostile = INVOICE_ONE.copy(
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

        manager.exportBatchExcel(target, listOf(hostile))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("شرکت &lt;آلفا&gt; &amp; همکاران"))
        assertTrue(raw.contains("کالای &quot;ویژه&quot;"))
    }

    @Test
    fun `csv ledger opens with a BOM and flattens every item`() {
        val target = tempFolder.newFile("ledger.csv")

        manager.exportBatchCsv(target, listOf(INVOICE_ONE, INVOICE_TWO))

        val bytes = target.readBytes()
        // EF BB BF: without it Excel on Windows decodes Persian as mojibake.
        assertTrue(bytes.size > 3)
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])

        val lines = target.readText(StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .lines()
            .filter { it.isNotEmpty() }
        // Header plus three flattened item rows.
        assertEquals(4, lines.size)
        assertEquals("ردیف,شماره فاکتور,تاریخ,فروشنده,کارفرما,پروژه,نام کالا,تعداد,قیمت واحد (تومان),مبلغ کل", lines[0])
        // The running row number spans the batch instead of restarting per invoice.
        assertTrue(lines[1].startsWith("1,"))
        assertTrue(lines[2].startsWith("2,"))
        assertTrue(lines[3].startsWith("3,"))
        assertTrue(lines[3].contains("ماوس"))
    }

    @Test
    fun `csv quotes fields per RFC 4180`() {
        val tricky = INVOICE_ONE.copy(
            items = listOf(
                InvoiceItem(
                    id = "t1",
                    name = "کابل, شارژ \"سریع\"",
                    quantity = 1.0,
                    unitPrice = 200.0,
                    totalPrice = 200.0,
                ),
            ),
        )
        val target = tempFolder.newFile("tricky.csv")

        manager.exportBatchCsv(target, listOf(tricky))

        val raw = target.readText(StandardCharsets.UTF_8)
        // Comma forces enclosure, inner quotes are doubled — the grid cannot shift.
        assertTrue(raw.contains("\"کابل, شارژ \"\"سریع\"\"\""))
    }

    @Test
    fun `tagged invoices carry client and project into both ledgers`() {
        val tagged = INVOICE_ONE.copy(clientName = "کارفرمای نمونه", projectName = "کمپین بهار")

        val excel = tempFolder.newFile("tagged.xls")
        manager.exportBatchExcel(excel, listOf(tagged))
        val excelRaw = excel.readText(StandardCharsets.UTF_8)
        assertTrue(excelRaw.contains("کارفرمای نمونه"))
        assertTrue(excelRaw.contains("کمپین بهار"))

        val csv = tempFolder.newFile("tagged.csv")
        manager.exportBatchCsv(csv, listOf(tagged))
        val csvLines = csv.readText(StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .lines()
            .filter { it.isNotEmpty() }
        // Header plus two flattened item rows, each carrying both tags.
        assertEquals(3, csvLines.size)
        assertTrue(csvLines[1].contains("کارفرمای نمونه"))
        assertTrue(csvLines[1].contains("کمپین بهار"))
        // Untagged invoices leave the columns blank rather than shifting the grid.
        val plain = tempFolder.newFile("plain.csv")
        manager.exportBatchCsv(plain, listOf(INVOICE_ONE))
        val plainFields = plain.readText(StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .lines()
            .first { it.startsWith("1,") }
            .split(",")
        assertEquals(10, plainFields.size)
        assertEquals("", plainFields[4])
        assertEquals("", plainFields[5])
    }

    @Test
    fun `empty batch still writes headers`() {
        val excel = tempFolder.newFile("empty.xls")
        val csv = tempFolder.newFile("empty.csv")

        manager.exportBatchExcel(excel, emptyList())
        manager.exportBatchCsv(csv, emptyList())

        assertTrue(excel.readText(StandardCharsets.UTF_8).contains("نام کالا"))
        assertTrue(csv.readText(StandardCharsets.UTF_8).contains("نام کالا"))
    }

    @Test
    fun `export creates missing parent directories`() {
        val target = File(tempFolder.root, "nested/dir/ledger.csv")

        manager.exportBatchCsv(target, listOf(INVOICE_ONE))

        assertTrue(target.isFile)
    }

    private companion object {
        val INVOICE_ONE = Invoice(
            id = "inv-1",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            items = listOf(
                InvoiceItem(
                    id = "i1",
                    name = "هدفون بی‌سیم",
                    quantity = 2.0,
                    unitPrice = 1_500_000.0,
                    totalPrice = 3_000_000.0,
                    confidence = 0.9f,
                ),
                InvoiceItem(
                    id = "i2",
                    name = "کابل شارژ",
                    quantity = 1.5,
                    unitPrice = 200_000.0,
                    totalPrice = 300_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 3_300_000.0,
            currency = CurrencyType.TOMAN,
        )

        val INVOICE_TWO = Invoice(
            id = "inv-2",
            invoiceNumber = "1002",
            date = "1403/05/21",
            sellerName = "فروشگاه دوم",
            items = listOf(
                InvoiceItem(
                    id = "i3",
                    name = "ماوس",
                    quantity = 1.0,
                    unitPrice = 450_000.0,
                    totalPrice = 450_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 450_000.0,
            currency = CurrencyType.TOMAN,
        )
    }
}
