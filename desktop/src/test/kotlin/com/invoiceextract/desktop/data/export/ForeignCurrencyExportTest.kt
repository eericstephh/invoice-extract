package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Foreign-money shaping for the Toman ledgers: the batch ledger and the
 * client statement print converted amounts in their numeric columns and
 * annotate the description with the original foreign figure.
 */
class ForeignCurrencyExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val batchManager = DesktopBatchExportManager()
    private val statementManager = DesktopClientStatementExportManager()

    @Test
    fun `batch ledger converts foreign lines and annotates the name`() {
        val target = tempFolder.newFile("ledger-fx.xls")

        batchManager.exportBatchExcel(target, listOf(USD_INVOICE, TOMAN_INVOICE))

        val raw = target.readText(StandardCharsets.UTF_8)
        // Converted Toman lands in the numeric cells…
        assertTrue(raw.contains(numberCell("9500000")))
        assertTrue(raw.contains(numberCell("1000000")))
        // …while the original dollar figure rides the name cell.
        assertTrue(raw.contains("[Google Ads] ($100.00 @ 95,000)"))
        // Domestic rows stay plain.
        assertTrue(raw.contains(textCell("هدفون بی‌سیم")))
    }

    @Test
    fun `batch csv ledger converts foreign lines too`() {
        val target = tempFolder.newFile("ledger-fx.csv")

        batchManager.exportBatchCsv(target, listOf(USD_INVOICE))

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains("9500000"))
        assertTrue(raw.contains("[Google Ads] ($100.00 @ 95,000)"))
    }

    @Test
    fun `statement totals a mixed archive in toman with fx annotation`() {
        val target = tempFolder.newFile("statement-fx.xls")

        statementManager.exportClientStatement(
            target,
            ClientStatementConfig(
                clientName = "شرکت آریا",
                projectName = "کمپین بین‌الملل",
                agencyFeePercent = 10.0,
            ),
            listOf(USD_INVOICE, TOMAN_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        // Subtotal 9,500,000 + 1,000,000 = 10,500,000; fee 1,050,000; total 11,550,000.
        assertTrue(raw.contains(numberCell("10500000")))
        assertTrue(raw.contains(numberCell("1050000")))
        assertTrue(raw.contains(numberCell("11550000")))
        assertTrue(raw.contains("($100.00 @ 95,000)"))
    }

    @Test
    fun `rial invoice converts to toman in the statement`() {
        val target = tempFolder.newFile("statement-rial.xls")

        statementManager.exportClientStatement(
            target,
            ClientStatementConfig(clientName = "الف", projectName = "ب"),
            listOf(RIAL_INVOICE),
        )

        val raw = target.readText(StandardCharsets.UTF_8)
        assertTrue(raw.contains(numberCell("500000")))
    }

    /** One typed numeric cell, exactly as the emitters write it, style aside. */
    private fun numberCell(value: String): String =
        "<Data ss:Type=\"Number\">$value</Data></Cell>"

    /** One typed text cell, exactly as the emitters write it, style aside. */
    private fun textCell(value: String): String =
        "<Data ss:Type=\"String\">$value</Data></Cell>"

    private companion object {
        val USD_INVOICE = Invoice(
            id = "inv-usd",
            invoiceNumber = "ADS-77",
            date = "1403/05/20",
            sellerName = "Google",
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
            grandTotal = 100.0,
            currency = CurrencyType.USD,
            exchangeRate = 95_000.0,
        )

        val TOMAN_INVOICE = Invoice(
            id = "inv-toman",
            invoiceNumber = "1001",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            items = listOf(
                InvoiceItem(
                    id = "i2",
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

        val RIAL_INVOICE = Invoice(
            id = "inv-rial",
            invoiceNumber = "2001",
            date = "1403/05/21",
            sellerName = "فروشگاه دوم",
            items = listOf(
                InvoiceItem(
                    id = "i3",
                    name = "ماوس",
                    quantity = 1.0,
                    unitPrice = 5_000_000.0,
                    discount = 0.0,
                    tax = 0.0,
                    totalPrice = 5_000_000.0,
                    confidence = 0.9f,
                ),
            ),
            grandTotal = 5_000_000.0,
            currency = CurrencyType.RIAL,
        )
    }
}
