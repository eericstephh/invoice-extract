package com.invoiceextract.domain.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Hermetic unit tests for [InvoiceCsvExporter].
 *
 * The exporter is a JVM `object` that writes straight into a [java.io.OutputStream] and
 * touches no platform type, so these run on a plain JVM: no emulator, no Robolectric,
 * and no file system — a [ByteArrayOutputStream] captures everything in memory.
 *
 * Escaping is verified with an independent, hand-rolled RFC 4180 parser ([splitCsvRecord])
 * rather than by reusing the exporter's own logic. Testing an implementation against itself
 * is what lets a quoting bug hide, so the test carries its own decoder.
 *
 * The exporter is shared by the Android and desktop fronts, so its contract is locked
 * here once and enforced for both.
 */
class InvoiceCsvExporterTest {

    @Test
    fun csvOutputStartsWithUtf8Bom() {
        val output = ByteArrayOutputStream()
        InvoiceCsvExporter.export(output, anInvoice())

        val bytes = output.toByteArray()

        // Excel on Windows sniffs the byte stream and, for a mostly-Latin numeric file,
        // fails to detect UTF-8 without the mark — Persian columns come back as mojibake.
        // Its presence at offset 0 is therefore a hard requirement, not a nicety.
        assertArrayEquals(
            "the stream must begin with the UTF-8 BOM (EF BB BF)",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3),
        )
        assertEquals("the BOM must appear exactly once", 1, bomOccurrences(bytes))
        // Everything after the mark must be the UTF-8 body, not a stray second BOM.
        val body = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        assertTrue("the body must start with the first header label", body.startsWith("فروشنده"))
    }

    @Test
    fun escapesQuotesAndCommas() {
        // An ASCII comma (U+002C, written as an escape so it can never be mistaken for a
        // Persian comma) plus embedded double quotes exercise both RFC 4180 rules at once:
        // the field must be enclosed in quotes and every inner quote doubled.
        val trickyName = "پیچ\u002C \"نری\""
        // The Persian comma ، (U+060C) is a different character and not a CSV delimiter, so
        // this name must stay a single unquoted field — no column shift, no enclosure.
        val persianCommaName = "کابل شارژ، مشکی"
        val invoice = anInvoice(
            items = listOf(
                anItem(id = "i1", name = trickyName, quantity = 2.0, unitPrice = 1_000.0, totalPrice = 2_000.0),
                anItem(id = "i2", name = persianCommaName, quantity = 1.0, unitPrice = 5_000.0, totalPrice = 5_000.0),
            ),
            subtotal = 7_000.0,
            grandTotal = 7_000.0,
        )

        val output = ByteArrayOutputStream()
        InvoiceCsvExporter.export(output, invoice)
        val csv = String(output.toByteArray(), StandardCharsets.UTF_8)

        // Only the two item rows start with an ASCII digit followed by the delimiter.
        val itemRows = csv
            .split(Regex("\\r?\\n"))
            .filter { it.startsWith("1,") || it.startsWith("2,") }

        assertEquals("both item rows must be present", 2, itemRows.size)

        val first = splitCsvRecord(itemRows[0])
        assertEquals(
            "a field holding a comma and quotes must not split into extra columns",
            7,
            first.size,
        )
        assertEquals("the escaped item name must round-trip exactly", trickyName, first[1])

        val second = splitCsvRecord(itemRows[1])
        assertEquals("the Persian comma must not split the row", 7, second.size)
        assertEquals("the unescaped item name must round-trip exactly", persianCommaName, second[1])
    }

    // ------------------------------------------------------------------ helpers

    /** Counts non-overlapping UTF-8 BOM occurrences in [bytes]. */
    private fun bomOccurrences(bytes: ByteArray): Int {
        var count = 0
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0xEF.toByte() && bytes[i + 1] == 0xBB.toByte() && bytes[i + 2] == 0xBF.toByte()) {
                count++
                i += 3
            } else {
                i++
            }
        }
        return count
    }

    /**
     * Minimal RFC 4180 record decoder, independent of the exporter's own escaping: splits
     * one physical line on unquoted commas and folds every doubled inner quote back into
     * one. Used so the escaping test never validates the code against itself.
     */
    private fun splitCsvRecord(record: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < record.length) {
            val c = record[i]
            when {
                inQuotes && c == '"' && record.getOrNull(i + 1) == '"' -> {
                    current.append('"')
                    i += 2
                    continue
                }
                inQuotes && c == '"' -> inQuotes = false
                !inQuotes && c == '"' -> inQuotes = true
                !inQuotes && c == ',' -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun anInvoice(
        items: List<InvoiceItem> = emptyList(),
        subtotal: Double = 0.0,
        totalTax: Double = 0.0,
        totalDiscount: Double = 0.0,
        grandTotal: Double = subtotal + totalTax - totalDiscount,
    ): Invoice = Invoice(
        id = "invoice-1",
        invoiceNumber = "10234567890",
        date = "1403/07/15",
        sellerName = "فروشگاه نمونه",
        sellerTaxId = "10234567890",
        buyerName = "خریدار نمونه",
        buyerTaxId = "10987654321",
        items = items,
        subtotal = subtotal,
        totalTax = totalTax,
        totalDiscount = totalDiscount,
        grandTotal = grandTotal,
        currency = CurrencyType.TOMAN,
    )

    private fun anItem(
        id: String = "item",
        name: String = "کالا",
        quantity: Double = 1.0,
        unitPrice: Double = 0.0,
        discount: Double = 0.0,
        tax: Double = 0.0,
        totalPrice: Double = quantity * unitPrice - discount + tax,
    ): InvoiceItem = InvoiceItem(
        id = id,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        discount = discount,
        tax = tax,
        totalPrice = totalPrice,
    )
}
