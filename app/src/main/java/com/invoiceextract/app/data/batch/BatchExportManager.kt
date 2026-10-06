package com.invoiceextract.app.data.batch

import android.content.Context
import android.net.Uri
import com.invoiceextract.app.data.export.InvoiceExportManager.ExportException
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Consolidated multi-invoice export engine (Phase 10.1).
 *
 * Flattens an arbitrary number of invoices into a single ledger: one CSV table or one
 * SpreadsheetML sheet whose rows are the line items of *every* invoice, with the
 * invoice identity repeated on each row. This is the export the batch screen offers
 * once a run finishes — "one file with everything in it" rather than one file per
 * invoice, which for a 50-file batch means 50 files nobody wants to open.
 *
 * Mirrors [InvoiceExportManager] deliberately: the same typed [ExportException] failure
 * contract, the same SAF stream lifecycle, and the same per-field escaping rules. A
 * caller that already handles the single-invoice failures needs no new branches for the
 * batch one.
 *
 * **No document model in memory.** Rows are written as they are generated and never
 * collected, so the peak cost of a 5,000-row ledger is one row's worth of string
 * building. The generators' `use` blocks own the writer and flush it, so a slow flash
 * write cannot leave a truncated file behind.
 *
 * @property appContext Application context; the content resolver is process-scoped, so
 *   this never leaks an activity.
 */
class BatchExportManager(private val appContext: Context) {

    /**
     * Writes a consolidated CSV of [invoices] into [uri].
     *
     * UTF-8 BOM first (`EF BB BF`), then one header row, then every item of every
     * invoice. Excel on Windows needs the BOM to decode Persian; LibreOffice and Google
     * Sheets ignore it, so it is free insurance.
     *
     * @return [Result.success], or [Result.failure] carrying [ExportException].
     */
    suspend fun exportBatchCsv(uri: Uri, invoices: List<Invoice>): Result<Unit> =
        write(uri) { stream ->
            writeCsv(stream, invoices)
        }

    /**
     * Writes a consolidated SpreadsheetML 2003 workbook into [uri].
     *
     * One sheet named "دفتر تجمیعی فاکتورها", laid out RTL at the worksheet level, with
     * amounts typed as `ss:Type="Number"` so Excel can still sum the column. Opens
     * natively in Excel and LibreOffice with no library, no Apache POI and no reflection.
     *
     * @return [Result.success], or [Result.failure] carrying [ExportException].
     */
    suspend fun exportBatchExcel(uri: Uri, invoices: List<Invoice>): Result<Unit> =
        write(uri) { stream ->
            writeExcel(stream, invoices)
        }

    /**
     * Dispatches [block] onto [Dispatchers.IO] and owns the stream lifecycle.
     *
     * The SAF write grant is transient and tied to the activity, so the whole export
     * completes inside this call. The stream is closed even when the generator throws,
     * and it is flushed before close so nothing is truncated on a slow write.
     */
    private suspend fun write(
        uri: Uri,
        block: (OutputStream) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = appContext.contentResolver.openOutputStream(uri)
                ?: throw ExportException.StreamUnavailable(uri)

            stream.use {
                block(it)
                it.flush()
            }
        }.recoverCatching { cause ->
            // One typed failure, so callers match on the sealed hierarchy rather than
            // duck-typing exception messages.
            if (cause is ExportException) throw cause
            throw ExportException.WriteFailed(cause)
        }
    }

    // ------------------------------------------------------------------ CSV

    private fun writeCsv(output: OutputStream, invoices: List<Invoice>) {
        // BOM as raw bytes, before the writer wraps the stream, so it is never re-encoded.
        output.write(BOM)

        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writeRow(writer, CSV_HEADERS)

            // A running row number across the whole ledger, so each row's "ردیف" is
            // unique and the exported file's numbering does not restart per invoice.
            var rowNumber = 0
            invoices.forEach { invoice ->
                invoice.items.forEach { item ->
                    writeRow(writer, invoiceToRow(++rowNumber, invoice, item))
                }
            }

            writer.flush()
        }
    }

    private fun invoiceToRow(rowNumber: Int, invoice: Invoice, item: InvoiceItem): List<String> =
        listOf(
            rowNumber.toString(),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
            invoice.sellerName.orEmpty(),
            item.name,
            formatAmount(item.quantity),
            formatAmount(item.unitPrice),
            formatAmount(item.totalPrice),
        )

    /**
     * Emits one record with RFC 4180 escaping applied to every field, including the
     * Persian ones: an item name containing a comma would otherwise shift the column
     * grid.
     */
    private fun writeRow(writer: OutputStreamWriter, fields: List<String>) {
        for (i in fields.indices) {
            if (i > 0) writer.append(COMMA)
            writer.append(escapeField(fields[i]))
        }
        writer.appendLine()
    }

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

    // ------------------------------------------------------------------ Excel XML

    private fun writeExcel(output: OutputStream, invoices: List<Invoice>) {
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writer.append(XML_DECLARATION)
            writer.append(STYLE_PROCESSING_INSTRUCTION)
            writer.append(WORKBOOK_OPEN)

            writeStyles(writer)

            writer.append(WORKSHEET_OPEN)
            writer.append(TABLE_OPEN)

            EXCEL_COLUMN_WIDTHS.forEach { width ->
                writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
            }

            // First row: the sheet title. Carries no cell data, just identifies the ledger.
            writer.append(ROW_OPEN)
            writeCell(writer, WORKSHEET_NAME, STYLE_TITLE, isNumeric = false)
            for (i in 1 until EXCEL_HEADERS.size) {
                writer.append(EMPTY_CELL)
            }
            writer.append(ROW_CLOSE)

            writeColumnHeaderRow(writer)

            // The ledger body: every item of every invoice, invoice identity repeated.
            var rowNumber = 0
            invoices.forEach { invoice ->
                invoice.items.forEach { item ->
                    writeItemRow(writer, ++rowNumber, invoice, item)
                }
            }

            writer.append(TABLE_CLOSE)
            writer.append(WORKSHEET_CLOSE)
            writer.append(WORKBOOK_CLOSE)
            writer.flush()
        }
    }

    private fun writeStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)

        // Ledger title: bold and slightly larger, so the sheet reads as one document.
        writer.append(
            "<Style ss:ID=\"Title\"><Font ss:Bold=\"1\" ss:Size=\"13\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Table header: bold, tinted, centered.
        writer.append(
            "<Style ss:ID=\"TableHeader\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#E8EDF2\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" " +
                "ss:WrapText=\"1\"/></Style>",
        )

        // Row number: small centered integer.
        writer.append(
            "<Style ss:ID=\"Index\"><Alignment ss:Horizontal=\"Center\" " +
                "ss:Vertical=\"Center\"/></Style>",
        )

        // Plain text: Persian invoice numbers and names.
        writer.append(
            "<Style ss:ID=\"Text\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Amounts: grouped thousands, right-aligned by the sheet's RTL direction.
        writer.append(
            "<Style ss:ID=\"Amount\"><NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(STYLES_CLOSE)
    }

    private fun writeColumnHeaderRow(writer: OutputStreamWriter) {
        writer.append(ROW_OPEN)
        EXCEL_HEADERS.forEach { header ->
            writeCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)
    }

    private fun writeItemRow(
        writer: OutputStreamWriter,
        rowNumber: Int,
        invoice: Invoice,
        item: InvoiceItem,
    ) {
        writer.append(ROW_OPEN)
        writeCell(writer, rowNumber.toString(), STYLE_INDEX, isNumeric = true)
        writeCell(writer, invoice.invoiceNumber.orEmpty(), STYLE_TEXT, isNumeric = false)
        writeCell(writer, invoice.date.orEmpty(), STYLE_TEXT, isNumeric = false)
        writeCell(writer, invoice.sellerName.orEmpty(), STYLE_TEXT, isNumeric = false)
        writeCell(writer, item.name, STYLE_TEXT, isNumeric = false)
        writeCell(writer, formatAmount(item.quantity), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.unitPrice), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.totalPrice), STYLE_AMOUNT, isNumeric = true)
        writer.append(ROW_CLOSE)
    }

    /**
     * One cell with an explicit type. Numbers carry `ss:Type="Number"` so Excel sums
     * them; everything else is `ss:Type="String"` so Persian is never coerced into a
     * number or a date. Free-form text is escaped so `&`, `<`, `>` and quotes can never
     * break the structure.
     */
    private fun writeCell(
        writer: OutputStreamWriter,
        value: String,
        styleId: String,
        isNumeric: Boolean,
    ) {
        val type = if (isNumeric) "Number" else "String"
        writer.append("<Cell ss:StyleID=\"$styleId\"><Data ss:Type=\"$type\">")
        writer.append(xmlEscape(value))
        writer.append(DATA_CELL_CLOSE)
    }

    private fun xmlEscape(value: String): String =
        if (value.none { it in XML_SPECIAL_CHARS }) {
            value
        } else {
            buildString(value.length) {
                for (c in value) {
                    when (c) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&apos;")
                        else -> append(c)
                    }
                }
            }
        }

    /**
     * Finite plain digits, no grouping and no locale decimal separator. Integer-valued
     * amounts render without a trailing `.0`, which is what Excel keeps numeric.
     */
    private fun formatAmount(value: Double): String =
        if (!value.isFinite()) {
            "0"
        } else if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private companion object {
        /** Persian table header, in sheet column order. */
        private val CSV_HEADERS = listOf(
            "ردیف",
            "شماره فاکتور",
            "تاریخ",
            "فروشنده",
            "نام کالا / خدمات",
            "تعداد",
            "قیمت واحد",
            "مبلغ کل",
        )

        private val EXCEL_HEADERS = CSV_HEADERS

        /** Column widths in points, sized for the widest realistic Persian content. */
        private val EXCEL_COLUMN_WIDTHS = intArrayOf(45, 110, 80, 130, 180, 55, 110, 110)

        private const val WORKSHEET_NAME = "دفتر تجمیعی فاکتورها"

        private const val COMMA = ','
        private const val QUOTE = '"'
        private const val CR = '\r'
        private const val LF = '\n'

        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        private const val STYLE_TITLE = "Title"
        private const val STYLE_TABLE_HEADER = "TableHeader"
        private const val STYLE_INDEX = "Index"
        private const val STYLE_TEXT = "Text"
        private const val STYLE_AMOUNT = "Amount"

        private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

        private const val STYLE_PROCESSING_INSTRUCTION =
            "<?mso-application progid=\"Excel.Sheet\"?>\n"

        private const val WORKBOOK_OPEN =
            "<Workbook xmlns=\"urn:schemas-microsoft-com:office:excel\" " +
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" " +
                "xmlns:o=\"urn:schemas-microsoft-com:office:office\">\n"

        private const val WORKBOOK_CLOSE = "</Workbook>"

        private const val WORKSHEET_OPEN =
            "<Worksheet ss:Name=\"$WORKSHEET_NAME\" ss:RightToLeft=\"1\">\n"

        private const val WORKSHEET_CLOSE = "</Worksheet>\n"

        private const val STYLES_OPEN = "<Styles>\n"
        private const val STYLES_CLOSE = "</Styles>\n"

        private const val TABLE_OPEN = "<Table>\n"
        private const val TABLE_CLOSE = "</Table>\n"

        private const val ROW_OPEN = "<Row>\n"
        private const val ROW_CLOSE = "</Row>\n"

        private const val DATA_CELL_CLOSE = "</Data></Cell>\n"

        private const val EMPTY_CELL = "<Cell/>"

        private val XML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"', '\'')
    }
}
