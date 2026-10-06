package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.model.symbol
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Zero-dependency generator of the consolidated batch ledger: every line item of every
 * successfully extracted invoice, flattened into one sheet.
 *
 * Two emitters, one row contract. The Excel and CSV outputs carry the same ten
 * columns in the same order — ردیف, شماره فاکتور, تاریخ, فروشنده, کارفرما, پروژه,
 * نام کالا, تعداد, قیمت واحد (تومان), مبلغ کل — so the `.xls` a user opens and the
 * `.csv` they import into accounting software describe the same ledger. Client and
 * project ride beside the seller (blank when untagged) so agencies get their
 * project-level breakdown without a second export. The running row number spans the
 * whole batch rather than restarting per invoice, which is what makes the sheet a
 * ledger instead of invoices stapled together.
 *
 * Conventions are inherited from the single-invoice emitters beside it
 * ([InvoiceCsvExporter], [InvoiceXmlSpreadsheetExporter]): the CSV opens with a UTF-8
 * BOM and quotes per RFC 4180, the SpreadsheetML sheet renders right-to-left with typed
 * cells, amounts are plain ungrouped digits, and rows stream out one at a time without
 * materializing the sheet. Unlike those two, this manager owns the file lifecycle —
 * callers hand over a target file and get a finished artifact or an exception, because
 * a batch export is fire-and-forget from the view model's side.
 */
class DesktopBatchExportManager {

    /**
     * Writes [invoices], flattened to line items, as a SpreadsheetML workbook into
     * [targetFile]. Parent directories are created; the file is overwritten.
     */
    fun exportBatchExcel(targetFile: File, invoices: List<Invoice>) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writeExcelDocument(writer, invoices)
                writer.flush()
            }
        }
    }

    /**
     * Writes [invoices], flattened to line items, as BOM-prefixed CSV into [targetFile].
     * Parent directories are created; the file is overwritten.
     */
    fun exportBatchCsv(targetFile: File, invoices: List<Invoice>) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            // BOM first, as raw bytes, so it is never re-encoded by the writer — the
            // same Excel-on-Windows mojibake insurance the single-invoice emitter uses.
            stream.write(BOM)
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writeCsvRow(writer, LEDGER_HEADERS)
                var rowNumber = 1
                invoices.forEach { invoice ->
                    invoice.items.forEach { item ->
                        writeCsvRow(writer, ledgerRow(invoice, item, rowNumber++))
                    }
                }
                writer.flush()
            }
        }
    }

    // -- SpreadsheetML -----------------------------------------------------------

    private fun writeExcelDocument(writer: OutputStreamWriter, invoices: List<Invoice>) {
        writer.append(XML_DECLARATION)
        writer.append(STYLE_PROCESSING_INSTRUCTION)
        writer.append(WORKBOOK_OPEN)

        writeExcelStyles(writer)

        writer.append(WORKSHEET_OPEN)
        writer.append(TABLE_OPEN)

        LEDGER_COLUMN_WIDTHS.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        writer.append(ROW_OPEN)
        LEDGER_HEADERS.forEach { header ->
            writeExcelCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)

        var rowNumber = 1
        invoices.forEach { invoice ->
            invoice.items.forEach { item ->
                writer.append(ROW_OPEN)
                val row = ledgerRow(invoice, item, rowNumber++)
                // Layout contract: the first cell is the running number, client and
                // project are text beside the seller, and the numeric amount cells are
                // تعداد / قیمت واحد / مبلغ کل — everything else text.
                writeExcelCell(writer, row[0], STYLE_INDEX, isNumeric = true)
                writeExcelCell(writer, row[1], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[2], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[3], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[4], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[5], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[6], STYLE_VALUE, isNumeric = false)
                writeExcelCell(writer, row[7], STYLE_AMOUNT, isNumeric = true)
                writeExcelCell(writer, row[8], STYLE_AMOUNT, isNumeric = true)
                writeExcelCell(writer, row[9], STYLE_AMOUNT, isNumeric = true)
                writer.append(ROW_CLOSE)
            }
        }

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
        writer.append(WORKBOOK_CLOSE)
    }

    private fun writeExcelStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)

        writer.append(
            "<Style ss:ID=\"Value\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Ledger header: bold, tinted, centered.
        writer.append(
            "<Style ss:ID=\"TableHeader\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#E8EDF2\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style>",
        )

        // Row number: small centered integer.
        writer.append(
            "<Style ss:ID=\"Index\"><Alignment ss:Horizontal=\"Center\" " +
                "ss:Vertical=\"Center\"/></Style>",
        )

        // Amounts: grouped thousands, kept right-aligned by RTL direction.
        writer.append(
            "<Style ss:ID=\"Amount\"><NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(STYLES_CLOSE)
    }

    /**
     * One cell with an explicit type. Numbers carry `ss:Type="Number"`; everything else
     * is `ss:Type="String"`. Text is escaped so `&`, `<`, `>` and quotes can never break
     * the structure.
     */
    private fun writeExcelCell(
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

    /** Escapes the five XML-significant characters. */
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

    // -- CSV ---------------------------------------------------------------------

    /**
     * Emits one record. Every field passes through [escapeCsvField], so quoting is
     * decided per value rather than assumed.
     */
    private fun writeCsvRow(writer: OutputStreamWriter, fields: List<String>) {
        for (i in fields.indices) {
            if (i > 0) writer.append(COMMA)
            writer.append(escapeCsvField(fields[i]))
        }
        writer.appendLine()
    }

    /**
     * RFC 4180 quoting: enclose when the field holds a delimiter, quote, CR or LF, and
     * escape inner quotes by doubling. A blank field needs no enclosure.
     */
    private fun escapeCsvField(field: String): String {
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

    // -- Shared row contract -----------------------------------------------------

    /** One ledger row: the invoice's identity columns plus the item's amount columns. */
    private fun ledgerRow(invoice: Invoice, item: InvoiceItem, rowNumber: Int): List<String> {
        val factor = invoice.tomanFactor()
        return listOf(
            rowNumber.toString(),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
            invoice.sellerName.orEmpty(),
            invoice.clientName.orEmpty(),
            invoice.projectName.orEmpty(),
            annotatedName(invoice, item),
            formatAmount(item.quantity),
            formatScaled(item.unitPrice, factor),
            formatScaled(item.totalPrice, factor),
        )
    }

    /**
     * The item name cell: plain for local money, annotated with the original
     * foreign figure for USD/EUR/USDT rows — e.g. `[Google Ads] ($100.00 @ 95,000)`
     * — so the converted Toman beside it stays auditable against the document.
     */
    private fun annotatedName(invoice: Invoice, item: InvoiceItem): String {
        if (!invoice.currency.isForeignCurrency) return item.name
        val rate = invoice.exchangeRate?.takeIf { it.isFinite() } ?: 1.0
        return "[${item.name}] (${invoice.currency.symbol}${formatForeign(item.totalPrice)} @ ${formatRate(rate)})"
    }

    /** Foreign money with two decimals (`100.00`); non-finite degrades to `0.00`. */
    private fun formatForeign(value: Double): String =
        if (!value.isFinite()) {
            "0.00"
        } else {
            FOREIGN_FORMAT.format(value)
        }

    /** Day rate with grouped thousands (`95,000`); non-finite degrades to `1`. */
    private fun formatRate(rate: Double): String =
        if (!rate.isFinite()) {
            "1"
        } else if (rate % 1.0 == 0.0 && rate.absoluteValue < LONG_RANGE_CEILING) {
            GROUPED_FORMAT.format(rate.toLong())
        } else {
            rate.toString()
        }

    /**
     * A printed amount scaled into Toman. Non-finite products degrade to `0`
     * rather than leaking `NaN` into a numeric cell.
     */
    private fun formatScaled(value: Double, factor: Double): String {
        val scaled = value * factor
        return if (!scaled.isFinite()) "0" else formatAmount(scaled)
    }

    /**
     * Per-unit scale turning one invoice's line amounts into Toman: `1.0` for
     * local money, the FX ratio for foreign and Rial invoices. A zero or
     * non-finite [Invoice.grandTotal] yields `1.0` — its lines sum to nothing
     * either way, so no scaling decision can change the outcome.
     */
    private fun Invoice.tomanFactor(): Double {
        val printed = grandTotal
        if (!printed.isFinite() || printed == 0.0) return 1.0
        return effectiveTomanTotal.toDouble() / printed
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

    private companion object {
        const val WORKSHEET_NAME = "دفتر تجمیعی فاکتورها"

        /** Grouped thousands, locale-independent: `95000` → `95,000`. */
        val GROUPED_FORMAT = DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT))

        /** Two-decimal foreign money: `100` → `100.00`. */
        val FOREIGN_FORMAT = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ROOT))

        /** First double past `Long.MAX_VALUE` (2^63). */
        const val LONG_RANGE_CEILING = 9.223372036854776E18

        val LEDGER_HEADERS = listOf(
            "ردیف",
            "شماره فاکتور",
            "تاریخ",
            "فروشنده",
            "کارفرما",
            "پروژه",
            "نام کالا",
            "تعداد",
            "قیمت واحد (تومان)",
            "مبلغ کل",
        )

        /** Column widths in points, sized for the widest realistic Persian content. */
        val LEDGER_COLUMN_WIDTHS = intArrayOf(45, 110, 90, 170, 150, 150, 200, 55, 110, 110)

        const val STYLE_VALUE = "Value"
        const val STYLE_TABLE_HEADER = "TableHeader"
        const val STYLE_INDEX = "Index"
        const val STYLE_AMOUNT = "Amount"

        const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

        const val STYLE_PROCESSING_INSTRUCTION =
            "<?mso-application progid=\"Excel.Sheet\"?>\n"

        const val WORKBOOK_OPEN =
            "<Workbook xmlns=\"urn:schemas-microsoft-com:office:excel\" " +
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" " +
                "xmlns:o=\"urn:schemas-microsoft-com:office:office\">\n"

        const val WORKBOOK_CLOSE = "</Workbook>"

        const val WORKSHEET_OPEN =
            "<Worksheet ss:Name=\"$WORKSHEET_NAME\" ss:RightToLeft=\"1\">\n"

        const val WORKSHEET_CLOSE = "</Worksheet>\n"

        const val STYLES_OPEN = "<Styles>\n"
        const val STYLES_CLOSE = "</Styles>\n"

        const val TABLE_OPEN = "<Table>\n"
        const val TABLE_CLOSE = "</Table>\n"

        const val ROW_OPEN = "<Row>\n"
        const val ROW_CLOSE = "</Row>\n"

        const val DATA_CELL_CLOSE = "</Data></Cell>\n"

        val XML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"', '\'')

        const val COMMA = ','
        const val QUOTE = '"'
        const val CR = '\r'
        const val LF = '\n'

        val BOM = "\uFEFF".toByteArray(StandardCharsets.UTF_8)
    }
}
