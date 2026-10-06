package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.analytics.DashboardAnalytics
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Zero-dependency generator of the executive BI analytics report as a
 * standalone RTL SpreadsheetML 2003 XML workbook — no Apache POI, exactly like
 * the ledger, statement and settlement emitters beside it.
 *
 * One worksheet named "گزارش تحلیلی مالی" / "Financial Analytics Report": a
 * title block (document title, fiscal period, issue date), then four
 * sections — KPI summary, monthly breakdown with budget shares, the top-five
 * vendor leaderboard with spend shares, and the costliest items. Amounts
 * render as plain ungrouped digits in `Number` cells so Excel sums and formats
 * them; identity text stays `String`; every string passes through [xmlEscape].
 *
 * Money is printed, never recomputed here: rows carry the snapshot's own
 * Toman longs, and shares divide by [DashboardAnalytics.totalSpend] with a
 * zero-total guard, so an empty archive renders headers and zeros instead of
 * dividing by zero.
 *
 * File lifecycle mirrors the sibling managers: callers hand over a target file
 * and get a finished artifact or an exception. Parent directories are created;
 * the file is overwritten.
 */
class DesktopAnalyticsExportManager {

    /**
     * Writes the analytics snapshot into [targetFile].
     *
     * @param selectedYear The dashboard's period filter, or `null` for the
     *   whole archive — printed as the fiscal period line.
     * @param isEnglish `true` for the English workbook, `false` for Persian.
     * @param today The issue date; a parameter (rather than a direct call) so
     *   tests pin the header without waiting for midnight.
     */
    fun exportAnalyticsReport(
        targetFile: File,
        analytics: DashboardAnalytics,
        selectedYear: Int?,
        isEnglish: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Result<File> = runCatching {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            // BOM first, as raw bytes, so it is never re-encoded by the writer.
            stream.write(BOM)
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writeDocument(writer, analytics, selectedYear, isEnglish, today)
                writer.flush()
            }
        }
        targetFile
    }

    private fun writeDocument(
        writer: OutputStreamWriter,
        analytics: DashboardAnalytics,
        selectedYear: Int?,
        isEnglish: Boolean,
        today: LocalDate,
    ) {
        val copy = copyFor(isEnglish)
        writer.append(XML_DECLARATION)
        writer.append(STYLE_PROCESSING_INSTRUCTION)
        writer.append(WORKBOOK_OPEN)

        writeStyles(writer)

        writer.append("<Worksheet ss:Name=\"${copy.sheetName}\" ss:RightToLeft=\"1\">\n")
        writer.append(TABLE_OPEN)
        COLUMN_WIDTHS.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        writeTitleBlock(writer, copy, analytics, selectedYear, today, isEnglish)
        writeKpiSection(writer, copy, analytics)
        writeMonthlySection(writer, copy, analytics)
        writeVendorSection(writer, copy, analytics)
        writeItemSection(writer, copy, analytics)

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
        writer.append(WORKBOOK_CLOSE)
    }

    private fun writeTitleBlock(
        writer: OutputStreamWriter,
        copy: ReportCopy,
        analytics: DashboardAnalytics,
        selectedYear: Int?,
        today: LocalDate,
        isEnglish: Boolean,
    ) {
        writer.append(ROW_OPEN)
        writeCell(writer, copy.docTitle, STYLE_TITLE, isNumeric = false)
        writer.append(ROW_CLOSE)

        val period = if (selectedYear == null) {
            copy.allPeriods
        } else {
            copy.fiscalYearPrefix + selectedYear.toString()
        }
        writer.append(ROW_OPEN)
        writeCell(writer, copy.periodLabel, STYLE_LABEL, isNumeric = false)
        writeCell(writer, period, STYLE_VALUE, isNumeric = false)
        writer.append(ROW_CLOSE)

        writer.append(ROW_OPEN)
        writeCell(writer, copy.issueDateLabel, STYLE_LABEL, isNumeric = false)
        writeCell(writer, issueDate(today, isEnglish), STYLE_VALUE, isNumeric = false)
        writer.append(ROW_CLOSE)

        writer.append(ROW_OPEN)
        writeCell(writer, copy.invoiceCountLabel, STYLE_LABEL, isNumeric = false)
        writeCell(writer, analytics.invoiceCount.toString(), STYLE_VALUE, isNumeric = true)
        writer.append(ROW_CLOSE)

        writer.append(BLANK_ROW)
    }

    private fun writeKpiSection(
        writer: OutputStreamWriter,
        copy: ReportCopy,
        analytics: DashboardAnalytics,
    ) {
        writeSectionTitle(writer, copy.kpiTitle)
        writeHeaderRow(writer, listOf(copy.kpiMetric, copy.kpiValue))
        val rows = listOf(
            copy.kpiTotalSpend to analytics.totalSpend.toString(),
            copy.kpiInvoiceCount to analytics.invoiceCount.toString(),
            copy.kpiAverage to analytics.averageInvoice.toString(),
            copy.kpiVendors to analytics.vendorCount.toString(),
            copy.kpiUnpaid to analytics.totalUnpaidAmount.toString(),
        )
        rows.forEach { (label, value) ->
            writer.append(ROW_OPEN)
            writeCell(writer, label, STYLE_LABEL, isNumeric = false)
            writeCell(writer, value, STYLE_AMOUNT, isNumeric = true)
            writer.append(ROW_CLOSE)
        }
        writer.append(BLANK_ROW)
    }

    private fun writeMonthlySection(
        writer: OutputStreamWriter,
        copy: ReportCopy,
        analytics: DashboardAnalytics,
    ) {
        writeSectionTitle(writer, copy.monthlyTitle)
        writeHeaderRow(writer, listOf(copy.colRow, copy.colMonth, copy.colAmount, copy.colShare))
        analytics.monthlyExpenses.forEachIndexed { index, expense ->
            writer.append(ROW_OPEN)
            writeCell(writer, (index + 1).toString(), STYLE_INDEX, isNumeric = true)
            writeCell(writer, expense.monthName, STYLE_VALUE, isNumeric = false)
            writeCell(writer, expense.totalAmount.toString(), STYLE_AMOUNT, isNumeric = true)
            writeCell(writer, shareOf(expense.totalAmount, analytics.totalSpend), STYLE_VALUE, isNumeric = false)
            writer.append(ROW_CLOSE)
        }
        writer.append(ROW_OPEN)
        writeCell(writer, copy.subtotalLabel, STYLE_SUBTOTAL, isNumeric = false)
        writeCell(writer, "", STYLE_SUBTOTAL, isNumeric = false)
        writeCell(writer, analytics.totalSpend.toString(), STYLE_SUBTOTAL, isNumeric = true)
        writeCell(writer, shareOf(analytics.totalSpend, analytics.totalSpend), STYLE_SUBTOTAL, isNumeric = false)
        writer.append(ROW_CLOSE)
        writer.append(BLANK_ROW)
    }

    private fun writeVendorSection(
        writer: OutputStreamWriter,
        copy: ReportCopy,
        analytics: DashboardAnalytics,
    ) {
        writeSectionTitle(writer, copy.vendorTitle)
        writeHeaderRow(writer, listOf(copy.colRank, copy.colVendor, copy.colAmount, copy.colShare))
        analytics.topVendors.forEachIndexed { index, vendor ->
            writer.append(ROW_OPEN)
            writeCell(writer, (index + 1).toString(), STYLE_INDEX, isNumeric = true)
            writeCell(writer, vendor.name, STYLE_VALUE, isNumeric = false)
            writeCell(writer, vendor.totalAmount.toString(), STYLE_AMOUNT, isNumeric = true)
            writeCell(writer, formatPercent(vendor.percentage.toDouble()), STYLE_VALUE, isNumeric = false)
            writer.append(ROW_CLOSE)
        }
        writer.append(BLANK_ROW)
    }

    private fun writeItemSection(
        writer: OutputStreamWriter,
        copy: ReportCopy,
        analytics: DashboardAnalytics,
    ) {
        writeSectionTitle(writer, copy.itemTitle)
        writeHeaderRow(writer, listOf(copy.colRank, copy.colItem, copy.colQty, copy.colAmount))
        analytics.topItems.forEachIndexed { index, item ->
            writer.append(ROW_OPEN)
            writeCell(writer, (index + 1).toString(), STYLE_INDEX, isNumeric = true)
            writeCell(writer, item.name, STYLE_VALUE, isNumeric = false)
            writeCell(writer, formatQuantity(item.quantity), STYLE_AMOUNT, isNumeric = true)
            writeCell(writer, item.totalAmount.toString(), STYLE_AMOUNT, isNumeric = true)
            writer.append(ROW_CLOSE)
        }
        writer.append(BLANK_ROW)
    }

    private fun writeSectionTitle(writer: OutputStreamWriter, title: String) {
        writer.append(ROW_OPEN)
        writeCell(writer, title, STYLE_SECTION, isNumeric = false)
        writer.append(ROW_CLOSE)
    }

    private fun writeHeaderRow(writer: OutputStreamWriter, headers: List<String>) {
        writer.append(ROW_OPEN)
        headers.forEach { header ->
            writeCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)
    }

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

    /** Share of [part] in [whole] as `12.5%`; a zero whole renders `0%`, never NaN. */
    private fun shareOf(part: Long, whole: Long): String {
        if (whole <= 0L || part < 0L) return "0%"
        return formatPercent(part.toDouble() / whole.toDouble() * 100.0)
    }

    private fun formatPercent(value: Double): String {
        if (!value.isFinite()) return "0%"
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        return "$text%"
    }

    private fun formatQuantity(quantity: Double): String =
        if (!quantity.isFinite()) {
            "0"
        } else if (quantity % 1.0 == 0.0) {
            quantity.toLong().toString()
        } else {
            quantity.toString()
        }

    /** Today as a Jalali string for FA, Gregorian for EN. */
    private fun issueDate(today: LocalDate, isEnglish: Boolean): String {
        if (isEnglish) {
            return today.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
        }
        val (jy, jm, jd) = gregorianToJalali(today.year, today.monthValue, today.dayOfMonth)
        // Manual padding, not String.format: `%02d` follows the default locale
        // and renders Persian digits on a fa host, which Excel would read as
        // text. Digits in a sheet are always ASCII.
        return "$jy/${jm.toString().padStart(2, '0')}/${jd.toString().padStart(2, '0')}"
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        // Bit-for-bit the sibling emitters' table (client statement, petty
        // cash): every sheet the app prints must date a day identically, so
        // this converter is shared by copy, never re-derived per report.
        val jDays = intArrayOf(31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
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

    private fun writeStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)
        writer.append(
            "<Style ss:ID=\"Title\"><Font ss:Bold=\"1\" ss:Size=\"14\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Section\"><Font ss:Bold=\"1\" ss:Size=\"12\"/>" +
                "<Interior ss:Color=\"#DDE5EE\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Label\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Value\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"TableHeader\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#E8EDF2\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Index\"><Alignment ss:Horizontal=\"Center\" " +
                "ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Amount\"><NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(
            "<Style ss:ID=\"Subtotal\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#F1E4B8\" ss:Pattern=\"Solid\"/>" +
                "<NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )
        writer.append(STYLES_CLOSE)
    }

    private data class ReportCopy(
        val sheetName: String,
        val docTitle: String,
        val periodLabel: String,
        val fiscalYearPrefix: String,
        val allPeriods: String,
        val issueDateLabel: String,
        val invoiceCountLabel: String,
        val kpiTitle: String,
        val kpiMetric: String,
        val kpiValue: String,
        val kpiTotalSpend: String,
        val kpiInvoiceCount: String,
        val kpiAverage: String,
        val kpiVendors: String,
        val kpiUnpaid: String,
        val monthlyTitle: String,
        val vendorTitle: String,
        val itemTitle: String,
        val colRow: String,
        val colMonth: String,
        val colAmount: String,
        val colShare: String,
        val colRank: String,
        val colVendor: String,
        val colItem: String,
        val colQty: String,
        val subtotalLabel: String,
    )

    private fun copyFor(isEnglish: Boolean): ReportCopy = if (isEnglish) {
        ReportCopy(
            sheetName = "Financial Analytics Report",
            docTitle = "Executive Expense Performance Report",
            periodLabel = "Fiscal period",
            fiscalYearPrefix = "Fiscal year ",
            allPeriods = "All periods (all years)",
            issueDateLabel = "Report issue date",
            invoiceCountLabel = "Archived invoices",
            kpiTitle = "Key Performance Indicators",
            kpiMetric = "Metric",
            kpiValue = "Value (Toman)",
            kpiTotalSpend = "Total spend (Toman)",
            kpiInvoiceCount = "Total invoices",
            kpiAverage = "Average invoice value",
            kpiVendors = "Counterparties",
            kpiUnpaid = "Total unpaid receivables",
            monthlyTitle = "Monthly Expense Trend",
            vendorTitle = "Top 5 Vendors",
            itemTitle = "Costliest Purchased Items",
            colRow = "#",
            colMonth = "Month & Year",
            colAmount = "Expense (Toman)",
            colShare = "Budget Share (%)",
            colRank = "Rank",
            colVendor = "Vendor",
            colItem = "Item / Service",
            colQty = "Total Qty",
            subtotalLabel = "Total",
        )
    } else {
        ReportCopy(
            sheetName = "گزارش تحلیلی مالی",
            docTitle = "گزارش جامع تحلیلی و عملکرد هزینه‌ها",
            periodLabel = "دوره مالی",
            fiscalYearPrefix = "سال مالی ",
            allPeriods = "کل دوره‌ها (همه سال‌ها)",
            issueDateLabel = "تاریخ صدور گزارش",
            invoiceCountLabel = "فاکتورهای آرشیو",
            kpiTitle = "شاخص‌های کلیدی عملکرد",
            kpiMetric = "شاخص",
            kpiValue = "مقدار (تومان)",
            kpiTotalSpend = "مجموع کل مخارج (تومان)",
            kpiInvoiceCount = "تعداد کل فاکتورها",
            kpiAverage = "میانگین ارزش فاکتور",
            kpiVendors = "تعداد طرف‌حساب‌ها",
            kpiUnpaid = "مجموع مطالبات وصول‌نشده",
            monthlyTitle = "روند مخارج به تفکیک ماه",
            vendorTitle = "۵ تأمین‌کننده برتر",
            itemTitle = "پرهزینه‌ترین اقلام خریداری‌شده",
            colRow = "ردیف",
            colMonth = "نام ماه و سال",
            colAmount = "مبلغ هزینه (تومان)",
            colShare = "سهم از کل بودجه (٪)",
            colRank = "رتبه",
            colVendor = "نام فروشنده / تأمین‌کننده",
            colItem = "شرح کالا / خدمات",
            colQty = "تعداد کل",
            subtotalLabel = "جمع کل",
        )
    }

    private companion object {
        /** Five columns max across sections, sized for Persian content. */
        val COLUMN_WIDTHS = intArrayOf(60, 230, 130, 130, 130)

        val BOM = "\uFEFF".toByteArray(StandardCharsets.UTF_8)

        const val STYLE_TITLE = "Title"
        const val STYLE_SECTION = "Section"
        const val STYLE_LABEL = "Label"
        const val STYLE_VALUE = "Value"
        const val STYLE_TABLE_HEADER = "TableHeader"
        const val STYLE_INDEX = "Index"
        const val STYLE_AMOUNT = "Amount"
        const val STYLE_SUBTOTAL = "Subtotal"

        const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        const val STYLE_PROCESSING_INSTRUCTION = "<?mso-application progid=\"Excel.Sheet\"?>\n"
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
        const val BLANK_ROW = "<Row><Cell/></Row>\n"

        val XML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"', '\'')
    }
}
