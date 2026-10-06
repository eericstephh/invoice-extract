package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Zero-dependency generator of a Microsoft Excel XML Spreadsheet (SpreadsheetML) for the
 * desktop front.
 *
 * Produces the 2003 XML dialect Excel reads natively — no Apache POI, no third-party
 * jar, no reflection. The output opens directly in Excel and LibreOffice with a
 * `.xls` extension, which is what the desktop save dialog suggests for a spreadsheet.
 *
 * **RTL.** `<Worksheet ss:RightToLeft="1">` flips column order at render time, so the
 * sheet reads right-to-left the moment it opens — no per-cell alignment hack, and no
 * dependence on the locale Excel happens to run in.
 *
 * **Typing.** Every cell declares its data type: counts and amounts are
 * `ss:Type="Number"` so Excel sums and formats them, text is `ss:Type="String"` so
 * Persian names are never coerced into a number or date. Free-form strings pass
 * through [xmlEscape] so a `&` in a company name can never break the document.
 *
 * Streaming: rows are written as they are built and the writer is flushed at the end,
 * never materializing the whole sheet. The stream is left open — the caller owns it.
 *
 * The desktop front owns this copy of the export contract under its own `data.export`
 * package, alongside [InvoiceCsvExporter]; it is kept byte-identical to the `:domain`
 * emitter the Android app drives, so both fronts export the same sheet of the same
 * invoice.
 */
object InvoiceXmlSpreadsheetExporter {

    private const val WORKSHEET_NAME = "فاکتور"

    private val COLUMN_HEADERS = listOf(
        "ردیف",
        "نام کالا / خدمات",
        "تعداد",
        "قیمت واحد (تومان)",
        "تخفیف",
        "مالیات",
        "مبلغ کل",
    )

    /** Column widths in points, sized for the widest realistic Persian content. */
    private val COLUMN_WIDTHS = intArrayOf(45, 230, 55, 110, 90, 90, 110)

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
     * Writes [invoice] as a SpreadsheetML workbook into [output]. Flushed, not closed.
     */
    fun export(output: OutputStream, invoice: Invoice) {
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writeDocument(writer, invoice)
            writer.flush()
        }
    }

    private fun writeDocument(writer: OutputStreamWriter, invoice: Invoice) {
        writer.append(XML_DECLARATION)
        writer.append(STYLE_PROCESSING_INSTRUCTION)
        writer.append(WORKBOOK_OPEN)

        writeStyles(writer)

        writer.append(WORKSHEET_OPEN)
        writer.append(TABLE_OPEN)

        COLUMN_WIDTHS.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        writeHeaderBlock(writer, invoice)
        writeColumnHeaderRow(writer)
        invoice.items.forEachIndexed { index, item ->
            writeItemRow(writer, index, item)
        }
        writeSummaryBlock(writer, invoice)

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
        writer.append(WORKBOOK_CLOSE)
    }

    private fun writeStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)

        // Identity block: label in bold, value plain.
        writer.append(
            "<Style ss:ID=\"Label\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Value\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Table header: bold, tinted, centered.
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

        // Grand total: bold, so the payable amount reads as the sheet's conclusion.
        writer.append(
            "<Style ss:ID=\"GrandTotal\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(STYLES_CLOSE)
    }

    private fun writeHeaderBlock(writer: OutputStreamWriter, invoice: Invoice) {
        val values = listOf(
            invoice.sellerName.orEmpty(),
            invoice.buyerName.orEmpty(),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
        )
        HEADER_LABELS.zip(values).forEach { (label, value) ->
            writer.append(ROW_OPEN)
            writeCell(writer, label, STYLE_LABEL, isNumeric = false)
            writeCell(writer, value, STYLE_VALUE, isNumeric = false)
            writer.append(ROW_CLOSE)
        }
        // Blank separator between the identity block and the line-item grid.
        writer.append(ROW_OPEN)
        writer.append(EMPTY_CELL)
        writer.append(EMPTY_CELL)
        writer.append(ROW_CLOSE)
    }

    private fun writeColumnHeaderRow(writer: OutputStreamWriter) {
        writer.append(ROW_OPEN)
        COLUMN_HEADERS.forEach { header ->
            writeCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)
    }

    private fun writeItemRow(writer: OutputStreamWriter, index: Int, item: InvoiceItem) {
        writer.append(ROW_OPEN)
        writeCell(writer, (index + 1).toString(), STYLE_INDEX, isNumeric = true)
        writeCell(writer, item.name, STYLE_VALUE, isNumeric = false)
        writeCell(writer, formatAmount(item.quantity), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.unitPrice), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.discount), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.tax), STYLE_AMOUNT, isNumeric = true)
        writeCell(writer, formatAmount(item.totalPrice), STYLE_AMOUNT, isNumeric = true)
        writer.append(ROW_CLOSE)
    }

    private fun writeSummaryBlock(writer: OutputStreamWriter, invoice: Invoice) {
        // Label spans the first five columns so the amounts stay under their own headers.
        val summary = listOf(
            SUMMARY_LABELS[0] to invoice.subtotal,
            SUMMARY_LABELS[1] to invoice.totalTax,
            SUMMARY_LABELS[2] to invoice.totalDiscount,
            SUMMARY_LABELS[3] to invoice.grandTotal,
        )
        summary.forEach { (label, amount) ->
            val isGrandTotal = label == SUMMARY_LABELS[3]
            writer.append(ROW_OPEN)
            writeCell(
                writer,
                label,
                if (isGrandTotal) STYLE_GRAND_TOTAL else STYLE_LABEL,
                isNumeric = false,
            )
            writeCell(
                writer,
                formatAmount(amount),
                if (isGrandTotal) STYLE_GRAND_TOTAL else STYLE_AMOUNT,
                isNumeric = true,
            )
            writer.append(ROW_CLOSE)
        }
    }

    /**
     * One cell with an explicit type. Numbers carry `ss:Type="Number"`; everything else
     * is `ss:Type="String"`. Text is escaped so `&`, `<`, `>` and quotes can never break
     * the structure.
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

    private fun formatAmount(value: Double): String =
        if (!value.isFinite()) {
            "0"
        } else if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private const val STYLE_LABEL = "Label"
    private const val STYLE_VALUE = "Value"
    private const val STYLE_TABLE_HEADER = "TableHeader"
    private const val STYLE_INDEX = "Index"
    private const val STYLE_AMOUNT = "Amount"
    private const val STYLE_GRAND_TOTAL = "GrandTotal"

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
