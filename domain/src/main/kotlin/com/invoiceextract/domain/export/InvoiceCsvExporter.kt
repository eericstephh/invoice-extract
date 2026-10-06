package com.invoiceextract.domain.export

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Zero-dependency generator of Persian-compatible CSV.
 *
 * Writes a complete invoice sheet straight into an [OutputStream] with no in-memory
 * document model: rows are emitted one at a time and the writer is flushed at the end,
 * so a 500-line invoice costs the same peak memory as a 5-line one.
 *
 * **UTF-8 BOM.** The 3-byte mark (`EF BB BF`) is written before anything else. Without
 * it, Excel on Windows sniffs the byte stream, fails to detect UTF-8 for a mostly-Latin
 * numeric file, and decodes Persian as mojibake — the single most reported "broken
 * export" cause for RTL locales. LibreOffice and Google Sheets ignore the BOM, so it is
 * free insurance against the one consumer that needs it.
 *
 * **Escaping.** RFC 4180: a field containing a comma, double quote, CR or LF is wrapped
 * in quotes and every inner quote is doubled. Applied to *every* field including the
 * Persian ones, so an item name containing a comma can never shift the column grid.
 *
 * **Numbers** are emitted as plain digits with no grouping separators and no locale
 * decimal comma, so Excel parses them back as numbers, not text. A non-finite value
 * (which can only come from a corrupt model) degrades to `0` rather than leaking `NaN`
 * into a spreadsheet.
 *
 * Lives in `:domain` so the Android and desktop fronts share one implementation: two
 * CSV emitters that drift apart would silently produce different exports of the same
 * invoice, which is exactly the bug a single shared object prevents.
 */
object InvoiceCsvExporter {

    /** Persian column headers, in sheet order. */
    private val COLUMN_HEADERS = listOf(
        "ردیف",
        "نام کالا / خدمات",
        "تعداد",
        "قیمت واحد (تومان)",
        "تخفیف",
        "مالیات",
        "مبلغ کل",
    )

    private val HEADER_LABELS = listOf(
        "فروشنده",
        "خریدار",
        "شماره فاکتور",
        "تاریخ",
    )

    private val SUMMARY_LABELS = listOf(
        "جمع جزء",
        "کل مالیات",
        "کل تخفیف",
        "مبلغ نهایی",
    )

    /**
     * Writes [invoice] as CSV into [output]. The stream is flushed but **not** closed —
     * the caller owns its lifecycle.
     */
    fun export(output: OutputStream, invoice: Invoice) {
        // BOM first, as raw bytes, so it is never re-encoded by the writer.
        output.write(BOM)

        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writeHeaderBlock(writer, invoice)
            writer.appendLine()
            writeRow(writer, COLUMN_HEADERS)
            invoice.items.forEachIndexed { index, item ->
                writeItemRow(writer, index, item)
            }
            writeSummaryBlock(writer, invoice)
            writer.flush()
        }
    }

    private fun writeHeaderBlock(writer: OutputStreamWriter, invoice: Invoice) {
        val values = listOf(
            invoice.sellerName.orEmpty(),
            invoice.buyerName.orEmpty(),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
        )
        HEADER_LABELS.zip(values).forEach { (label, value) ->
            writeRow(writer, listOf(label, value))
        }
    }

    private fun writeItemRow(writer: OutputStreamWriter, index: Int, item: InvoiceItem) {
        writeRow(
            writer,
            listOf(
                (index + 1).toString(),
                item.name,
                formatAmount(item.quantity),
                formatAmount(item.unitPrice),
                formatAmount(item.discount),
                formatAmount(item.tax),
                formatAmount(item.totalPrice),
            ),
        )
    }

    private fun writeSummaryBlock(writer: OutputStreamWriter, invoice: Invoice) {
        val values = listOf(
            invoice.subtotal,
            invoice.totalTax,
            invoice.totalDiscount,
            invoice.grandTotal,
        )
        SUMMARY_LABELS.zip(values).forEach { (label, value) ->
            writeRow(writer, listOf(label, formatAmount(value)))
        }
    }

    /**
     * Emits one record. Every field passes through [escapeField], so quoting is decided
     * per value rather than assumed.
     */
    private fun writeRow(writer: OutputStreamWriter, fields: List<String>) {
        for (i in fields.indices) {
            if (i > 0) writer.append(COMMA)
            writer.append(escapeField(fields[i]))
        }
        writer.appendLine()
    }

    /**
     * RFC 4180 quoting: enclose when the field holds a delimiter, quote, CR or LF, and
     * escape inner quotes by doubling. A blank field needs no enclosure.
     */
    private fun escapeField(field: String): String {
        if (field.isEmpty()) return field
        if (field.none { it == COMMA || it == QUOTE || it == CR || it == LF }) return field
        return buildString(field.length + 2) {
            append(QUOTE)
            for (c in field) {
                if (c == QUOTE) append(QUOTE)
                append(c)
            }
            append(QUOTE)
        }
    }

    /**
     * Finite plain digits, no grouping and no locale decimal separator. Integer-valued
     * amounts render without a trailing `.0`, which is what a Persian invoice printed on
     * paper looks like and what Excel keeps numeric.
     */
    private fun formatAmount(value: Double): String =
        if (!value.isFinite()) {
            "0"
        } else if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private const val COMMA = ','
    private const val QUOTE = '"'
    private const val CR = '\r'
    private const val LF = '\n'

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
