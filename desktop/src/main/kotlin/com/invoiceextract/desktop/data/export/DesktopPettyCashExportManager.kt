package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.analytics.PettyCashSettlement
import com.invoiceextract.desktop.domain.analytics.SettlementStatus
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.model.symbol
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Zero-dependency generator of the petty-cash settlement sheet
 * (فرم تسویه تنخواه) as a standalone RTL SpreadsheetML 2003 XML workbook —
 * no Apache POI, exactly like the statement and ledger emitters beside it.
 *
 * One worksheet named "صورتجلسه تسویه تنخواه": a header block (document title,
 * client, settlement date, attached-invoice count), the advance & summary
 * block (advance received, expenses spent, settlement verdict, final balance),
 * one row per attached invoice
 * (ردیف | تاریخ | شماره فاکتور | تأمین‌کننده | شرح هزینه | مبلغ), and the
 * signature footer both parties sign.
 *
 * **Money is printed in Toman, not recomputed.** Each row carries the
 * invoice's [Invoice.effectiveTomanTotal] and the summary block carries the
 * [PettyCashSettlement] the dialog previewed — one arithmetic, two consumers.
 * Amounts render as plain ungrouped digits in `Number` cells so Excel sums and
 * formats them; identity text stays `String`.
 *
 * File lifecycle mirrors the sibling managers: callers hand over a target file
 * and get a finished artifact or an exception. Parent directories are created;
 * the file is overwritten.
 */
class DesktopPettyCashExportManager {

    /**
     * Writes the settlement of [invoices] against their advance into [targetFile].
     */
    fun exportPettyCashSettlement(
        targetFile: File,
        clientName: String,
        projectName: String,
        settlement: PettyCashSettlement,
        invoices: List<Invoice>,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.append(XML_DECLARATION)
                writer.append(STYLE_PROCESSING_INSTRUCTION)
                writer.append(WORKBOOK_OPEN)

                writeStyles(writer)
                writeWorksheet(writer, clientName, projectName, settlement, invoices)

                writer.append(WORKBOOK_CLOSE)
                writer.flush()
            }
        }
    }

    // -- Document ------------------------------------------------------------

    private fun writeWorksheet(
        writer: OutputStreamWriter,
        clientName: String,
        projectName: String,
        settlement: PettyCashSettlement,
        invoices: List<Invoice>,
    ) {
        writer.append("<Worksheet ss:Name=\"$WORKSHEET_NAME\" ss:RightToLeft=\"1\">\n")
        writer.append(TABLE_OPEN)

        COLUMN_WIDTHS.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        // Title row, merged across the full table width.
        writer.append(ROW_OPEN)
        writer.append("<Cell ss:MergeAcross=\"5\" ss:StyleID=\"$STYLE_TITLE\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape("صورتجلسه تسویه تنخواه‌گردان پروژه: $projectName"))
        writer.append(DATA_CELL_CLOSE)
        writer.append(ROW_CLOSE)

        writeHeaderField(writer, "کارفرما", clientName)
        writeHeaderField(writer, "تاریخ تسویه", jalaliIssueDate())
        writeHeaderField(writer, "تعداد فاکتورهای پیوست", invoices.size.toString())

        // Spacer row so the summary block breathes below the header block.
        writer.append(ROW_OPEN)
        writer.append(ROW_CLOSE)

        writeSummaryRow(
            writer,
            "مبلغ تنخواه دریافت شده (تومان)",
            settlement.advanceAmount,
            STYLE_SUMMARY,
        )
        writeSummaryRow(
            writer,
            "جمع کل هزینه‌های انجام‌شده (تومان)",
            settlement.totalExpenses,
            STYLE_SUMMARY,
        )
        writeHeaderField(writer, "وضعیت تسویه", statusText(settlement.status))
        writeSummaryRow(
            writer,
            "مانده نهایی تسویه (تومان)",
            settlement.balance.absoluteValue,
            STYLE_GRAND_TOTAL,
        )

        // Spacer row so the table breathes below the summary block.
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
                formatLong(invoice.effectiveTomanTotal),
                STYLE_AMOUNT,
                isNumeric = true,
            )
            writer.append(ROW_CLOSE)
        }

        // Signature footer: both parties sign the same page they settle on.
        writer.append(ROW_OPEN)
        writer.append(ROW_CLOSE)
        writeSignatureRow(writer, "امضای تنخواه‌دار: ..............................")
        writeSignatureRow(writer, "امضای مدیر مالی / کارفرما: ..............................")

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
    }

    /** The verdict sentence printed in the summary block. */
    private fun statusText(status: SettlementStatus): String = when (status) {
        SettlementStatus.BALANCED -> "متعادل — تسویه کامل، بدون مانده"
        SettlementStatus.SURPLUS -> "مازاد تنخواه — بازگشت به کارفرما"
        SettlementStatus.DEFICIT -> "کسری تنخواه — طلب تنخواه‌دار"
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
        amount: Long,
        styleId: String,
    ) {
        writer.append(ROW_OPEN)
        writer.append("<Cell ss:MergeAcross=\"4\" ss:StyleID=\"$styleId\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape(label))
        writer.append(DATA_CELL_CLOSE)
        writeCell(writer, formatLong(amount), styleId, isNumeric = true)
        writer.append(ROW_CLOSE)
    }

    /** One signature row, merged across the full table width. */
    private fun writeSignatureRow(writer: OutputStreamWriter, caption: String) {
        writer.append(ROW_OPEN)
        writer.append("<Cell ss:MergeAcross=\"5\" ss:StyleID=\"$STYLE_SIGNATURE\">")
        writer.append("<Data ss:Type=\"String\">")
        writer.append(xmlEscape(caption))
        writer.append(DATA_CELL_CLOSE)
        writer.append(ROW_CLOSE)
    }

    /**
     * The cost description cell: the invoice's line-item names joined with "،".
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

    /** Plain integral digits for Toman amounts, which have no subunit. */
    private fun formatLong(value: Long): String = value.toString()

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

        // Final balance: bold on a highlighted wash, so the settlement figure
        // is unmissable at the foot of the summary block.
        writer.append(
            "<Style ss:ID=\"GrandTotal\"><Font ss:Bold=\"1\" ss:Size=\"12\"/>" +
                "<Interior ss:Color=\"#FEF3C7\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(
            "<Style ss:ID=\"Signature\"><Alignment ss:Vertical=\"Center\"/>" +
                "<Font ss:Size=\"11\"/></Style>",
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
        const val WORKSHEET_NAME = "صورتجلسه تسویه تنخواه"

        val TABLE_HEADERS = listOf(
            "ردیف",
            "تاریخ",
            "شماره فاکتور",
            "تأمین‌کننده / فروشنده",
            "شرح هزینه",
            "مبلغ (تومان)",
        )

        /** Column widths in points, sized for the widest realistic Persian content. */
        val COLUMN_WIDTHS = intArrayOf(45, 90, 110, 170, 260, 120)

        /** Grouped thousands, locale-independent: `95000` → `95,000`. */
        val GROUPED_FORMAT = DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT))

        /** Two-decimal foreign money: `100` → `100.00`. */
        val FOREIGN_FORMAT = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ROOT))

        /**
         * First double past `Long.MAX_VALUE` (2^63): every smaller-magnitude
         * integral rate converts exactly with `toLong()`.
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
        const val STYLE_SIGNATURE = "Signature"

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
