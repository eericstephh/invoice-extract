package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.model.symbol
import java.io.File
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Client statement configuration for the Agency & Freelancer Suite.
 *
 * @property clientName The client (کارفرما) the statement is issued to.
 * @property projectName The project (پروژه) the attached invoices belong to.
 * @property agencyFeePercent Management/execution markup percent applied on top
 *   of the direct-cost subtotal. `0.0` means a clean pass-through with no fee.
 */
data class ClientStatementConfig(
    val clientName: String,
    val projectName: String,
    val agencyFeePercent: Double = 0.0,
)

/**
 * The computed money of a client statement, in Toman.
 *
 * Kept as an explicit value (rather than buried inside the XML writer) so the
 * dialog's live preview and the unit tests read the same arithmetic the sheet
 * prints — one formula, three consumers.
 */
data class ClientStatementTotals(
    val subtotal: Double,
    val feeAmount: Double,
    val grandTotal: Double,
)

/**
 * Zero-dependency generator of the agency client statement
 * (صدور صورت‌وضعیت مالی پروژه با کارمزد آژانس) as a standalone RTL
 * SpreadsheetML 2003 XML workbook — no Apache POI, exactly like the ledger
 * and accounting emitters beside it.
 *
 * One worksheet named "صورت‌وضعیت پروژه": a header block (document title,
 * client, issue date, attached-invoice count), one row per attached invoice
 * (ردیف | تاریخ | شماره فاکتور | تأمین‌کننده | شرح هزینه | مبلغ), then three
 * summary rows — direct-cost subtotal, agency markup, and the highlighted
 * grand total the client settles.
 *
 * **Money is printed in Toman, not recomputed.** Each row carries the invoice's
 * [Invoice.effectiveTomanTotal] — what the pipeline validated, converted — and
 * the subtotal is their sum. Amounts render as plain ungrouped digits in
 * `Number` cells so Excel sums and formats them; identity text stays `String`.
 *
 * File lifecycle mirrors the sibling managers: callers hand over a target file
 * and get a finished artifact or an exception. Parent directories are created;
 * the file is overwritten.
 */
class DesktopClientStatementExportManager {

    /**
     * Writes a client statement for [invoices] under [config] into [targetFile].
     */
    fun exportClientStatement(
        targetFile: File,
        config: ClientStatementConfig,
        invoices: List<Invoice>,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.append(XML_DECLARATION)
                writer.append(STYLE_PROCESSING_INSTRUCTION)
                writer.append(WORKBOOK_OPEN)

                writeStyles(writer)
                writeWorksheet(writer, config, invoices)

                writer.append(WORKBOOK_CLOSE)
                writer.flush()
            }
        }
    }

    /**
     * The statement arithmetic, uniformly in Toman: the subtotal sums each
     * invoice's [Invoice.effectiveTomanTotal], the fee is
     * `subtotal × percent / 100`, the grand total is their sum.
     *
     * A non-finite or negative percent degrades to `0.0` — a markup is never
     * negative and `NaN` must never reach the sheet.
     */
    fun computeTotals(invoices: List<Invoice>, agencyFeePercent: Double): ClientStatementTotals {
        val subtotal = invoices.sumOf { it.effectiveTomanTotal.toDouble() }
        val percent = if (agencyFeePercent.isFinite() && agencyFeePercent > 0.0) {
            agencyFeePercent
        } else {
            0.0
        }
        val feeAmount = subtotal * percent / 100.0
        return ClientStatementTotals(
            subtotal = subtotal,
            feeAmount = feeAmount,
            grandTotal = subtotal + feeAmount,
        )
    }

    // -- Document ------------------------------------------------------------

    private fun writeWorksheet(
        writer: OutputStreamWriter,
        config: ClientStatementConfig,
        invoices: List<Invoice>,
    ) {
        val totals = computeTotals(invoices, config.agencyFeePercent)

        writer.append("<Worksheet ss:Name=\"$WORKSHEET_NAME\" ss:RightToLeft=\"1\">\n")
        writer.append(TABLE_OPEN)

        COLUMN_WIDTHS.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        // Title row, merged across the full table width.
        writer.append(ROW_OPEN)
        writer.append("<Cell ss:MergeAcross=\"5\" ss:StyleID=\"$STYLE_TITLE\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape("صورت‌وضعیت هزینه‌های پروژه: ${config.projectName}"))
        writer.append(DATA_CELL_CLOSE)
        writer.append(ROW_CLOSE)

        writeHeaderField(writer, "کارفرما", config.clientName)
        writeHeaderField(writer, "تاریخ صدور", jalaliIssueDate())
        writeHeaderField(writer, "تعداد فاکتورهای پیوست", invoices.size.toString())

        // Spacer row so the table breathes below the header block.
        writer.append(ROW_OPEN)
        writer.append(ROW_CLOSE)

        writer.append(ROW_OPEN)
        TABLE_HEADERS.forEach { header ->
            writeCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)

        invoices.forEachIndexed { index, invoice ->
            writer.append(ROW_OPEN)
            writeCell(writer, (index + 1).toString(), STYLE_VALUE, isNumeric = true)
            writeCell(writer, invoice.date.orEmpty(), STYLE_VALUE, isNumeric = false)
            writeCell(writer, invoice.invoiceNumber.orEmpty(), STYLE_VALUE, isNumeric = false)
            writeCell(
                writer,
                invoice.sellerName?.ifBlank { null } ?: "",
                STYLE_VALUE,
                isNumeric = false,
            )
            writeCell(writer, describeInvoice(invoice), STYLE_VALUE, isNumeric = false)
            writeCell(
                writer,
                formatAmount(invoice.effectiveTomanTotal.toDouble()),
                STYLE_AMOUNT,
                isNumeric = true,
            )
            writer.append(ROW_CLOSE)
        }

        writeSummaryRow(
            writer,
            "جمع کل هزینه‌های مستقیم پروژه",
            totals.subtotal,
            STYLE_SUMMARY,
        )
        writeSummaryRow(
            writer,
            "کارمزد مدیریت و اجرای آژانس (${formatPercent(config.agencyFeePercent)}٪)",
            totals.feeAmount,
            STYLE_SUMMARY,
        )
        writeSummaryRow(
            writer,
            "مبلغ نهایی قابل تسویه توسط کارفرما",
            totals.grandTotal,
            STYLE_GRAND_TOTAL,
        )

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
    }

    /** One header-block row: label cell plus a value merged across the rest. */
    private fun writeHeaderField(writer: OutputStreamWriter, label: String, value: String) {
        writer.append(ROW_OPEN)
        writeCell(writer, label, STYLE_HEADER_LABEL, isNumeric = false)
        writer.append("<Cell ss:MergeAcross=\"4\" ss:StyleID=\"$STYLE_HEADER_VALUE\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape(value))
        writer.append(DATA_CELL_CLOSE)
        writer.append(ROW_CLOSE)
    }

    /** One summary row: label merged across five columns plus the amount cell. */
    private fun writeSummaryRow(
        writer: OutputStreamWriter,
        label: String,
        amount: Double,
        styleId: String,
    ) {
        writer.append(ROW_OPEN)
        writer.append("<Cell ss:MergeAcross=\"4\" ss:StyleID=\"$styleId\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape(label))
        writer.append(DATA_CELL_CLOSE)
        writeCell(writer, formatAmount(amount), styleId, isNumeric = true)
        writer.append(ROW_CLOSE)
    }

    /**
     * The cost description cell: the invoice's line-item names joined with "،",
     * so the row stays auditable without exploding one invoice into many rows.
     * An item-less invoice degrades to an em dash rather than an empty cell.
     * Foreign invoices carry their original figure — e.g. `($100.00 @ 95,000)` —
     * so the converted Toman beside it traces back to the document.
     */
    private fun describeInvoice(invoice: Invoice): String {
        val names = invoice.items.mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
            .joinToString("، ")
            .takeIf { it.isNotBlank() }
            ?: "—"
        if (!invoice.currency.isForeignCurrency) return names
        val foreign = invoice.originalForeignAmount ?: invoice.grandTotal
        val rate = invoice.exchangeRate?.takeIf { it.isFinite() } ?: 1.0
        return "$names (${invoice.currency.symbol}${formatForeign(foreign)} @ ${formatRate(rate)})"
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
     * Finite plain digits, no grouping and no locale decimal separator — the same
     * contract as the sibling emitters, so Excel keeps every amount numeric.
     * Non-finite input degrades to `0`; beyond ±Long range the exact [BigDecimal]
     * road keeps the digits instead of saturating.
     */
    private fun formatAmount(value: Double): String =
        if (!value.isFinite()) {
            "0"
        } else if (value % 1.0 == 0.0) {
            if (value.absoluteValue < LONG_RANGE_CEILING) value.toLong().toString()
            else BigDecimal(value).toPlainString()
        } else {
            value.toString()
        }

    /** The fee percent as printed in the markup label; non-finite degrades to `0`. */
    private fun formatPercent(value: Double): String =
        if (!value.isFinite()) {
            "0"
        } else if (value % 1.0 == 0.0) {
            if (value.absoluteValue < LONG_RANGE_CEILING) value.toLong().toString()
            else BigDecimal(value).toPlainString()
        } else {
            value.toString()
        }

    /** Today's date as a Jalali calendar string, e.g. `1404/07/06`. */
    private fun jalaliIssueDate(today: LocalDate = LocalDate.now()): String {
        val (jy, jm, jd) = gregorianToJalali(today.year, today.monthValue, today.dayOfMonth)
        return "%04d/%02d/%02d".format(jy, jm, jd)
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDays = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
        var y = gy - 1600
        var m = gm - 1
        var d = gd - 1
        var gDayNo = 365 * y + (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        for (i in 0 until m) gDayNo += gDays[i]
        if (m > 1 && ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0))) gDayNo++
        gDayNo += d
        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053
        jDayNo %= 12053
        y = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461
        if (jDayNo >= 366) {
            y += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }
        m = 0
        while (m < 11 && jDayNo >= jDays[m]) {
            jDayNo -= jDays[m]
            m++
        }
        return Triple(y, m + 1, jDayNo + 1)
    }

    // -- SpreadsheetML plumbing (mirrors the sibling emitters) -----------------

    private fun writeStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)

        writer.append(
            "<Style ss:ID=\"Value\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"TableHeader\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#E8EDF2\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"Amount\"><NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"Title\"><Font ss:Bold=\"1\" ss:Size=\"14\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"HeaderLabel\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"HeaderValue\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"Summary\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#F1F5F9\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Grand total: bold on a highlighted wash, so the settlement figure
        // is unmissable at the foot of the sheet.
        writer.append(
            "<Style ss:ID=\"GrandTotal\"><Font ss:Bold=\"1\" ss:Size=\"12\"/>" +
                "<Interior ss:Color=\"#FEF3C7\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(STYLES_CLOSE)
    }

    /**
     * One cell with an explicit type. Text is escaped so `&`, `<`, `>` and
     * quotes can never break the structure.
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

    private companion object {
        const val WORKSHEET_NAME = "صورت‌وضعیت پروژه"

        /** Grouped thousands, locale-independent: `95000` → `95,000`. */
        val GROUPED_FORMAT = DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT))

        /** Two-decimal foreign money: `100` → `100.00`. */
        val FOREIGN_FORMAT = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ROOT))

        val TABLE_HEADERS = listOf(
            "ردیف",
            "تاریخ",
            "شماره فاکتور",
            "تأمین‌کننده / فروشنده",
            "شرح هزینه یا اقلام",
            "مبلغ (تومان)",
        )

        /** Column widths in points, sized for the widest realistic Persian content. */
        val COLUMN_WIDTHS = intArrayOf(45, 90, 110, 170, 260, 120)

        /**
         * First double past `Long.MAX_VALUE` (2^63): every smaller-magnitude
         * integral double converts exactly with `toLong()`, everything at or
         * above it needs [BigDecimal] to keep its digits.
         */
        const val LONG_RANGE_CEILING = 9.223372036854776E18

        const val STYLE_VALUE = "Value"
        const val STYLE_TABLE_HEADER = "TableHeader"
        const val STYLE_AMOUNT = "Amount"
        const val STYLE_TITLE = "Title"
        const val STYLE_HEADER_LABEL = "HeaderLabel"
        const val STYLE_HEADER_VALUE = "HeaderValue"
        const val STYLE_SUMMARY = "Summary"
        const val STYLE_GRAND_TOTAL = "GrandTotal"

        const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

        const val STYLE_PROCESSING_INSTRUCTION =
            "<?mso-application progid=\"Excel.Sheet\"?>\n"

        const val WORKBOOK_OPEN =
            "<Workbook xmlns=\"urn:schemas-microsoft-com:office:excel\" " +
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" " +
                "xmlns:o=\"urn:schemas-microsoft-com:office:office\">\n"

        const val WORKBOOK_CLOSE = "</Workbook>"

        const val WORKSHEET_CLOSE = "</Worksheet>\n"

        const val STYLES_OPEN = "<Styles>\n"
        const val STYLES_CLOSE = "</Styles>\n"

        const val TABLE_OPEN = "<Table>\n"
        const val TABLE_CLOSE = "</Table>\n"

        const val ROW_OPEN = "<Row>\n"
        const val ROW_CLOSE = "</Row>\n"

        const val DATA_CELL_CLOSE = "</Data></Cell>\n"

        val XML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"', '\'')
    }
}
