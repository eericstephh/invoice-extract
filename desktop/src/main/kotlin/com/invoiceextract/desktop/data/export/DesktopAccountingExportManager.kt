package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.File
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import kotlin.math.absoluteValue

/**
 * Which accounting import layout to emit. Exhaustive by design: the batch consolidator
 * switches on it, so adding a vendor is a compile error in every emitter until the new
 * layout is drawn.
 */
enum class AccountingTemplate {
    SEPIDAR,
    HOLOO,
}

/**
 * Zero-dependency generator of Iranian accounting-software import sheets (Sepidar
 * System and Holoo), as RTL SpreadsheetML 2003 XML — no Apache POI, no third-party
 * jar, exactly like the ledger emitters beside it.
 *
 * Each template is one worksheet whose columns match the vendor's import contract, so
 * the file drops straight into the software's Excel import without reshaping. Both
 * templates flatten an invoice to one row per line item and carry the parent
 * invoice's identity on every row, because an import row without its invoice number
 * is un-auditable once it lands in the ledger.
 *
 * **Money is Rial.** Both vendors price in Rial while extracted invoices are usually
 * Toman, so every monetary cell is converted on the way out: Toman × 10, Rial (and
 * the UNKNOWN we cannot convert) verbatim. The conversion is documented per call
 * site rather than hidden, because a silent ×10 is exactly how ledgers drift.
 *
 * **Line totals are printed, not recomputed.** The row total is `totalPrice` — what
 * the source document actually printed — never `quantity × unitPrice − discount + tax
 * recomputed here. The domain keeps both precisely so exports preserve the document
 * while validation cross-checks the arithmetic; recomputing at export time would
 * erase that distinction.
 *
 * **Fields the domain does not carry.** Three columns have no domain source, and each
 * degrades openly instead of inventing data:
 * - *کد کالا*: the item's mapped warehouse code ([InvoiceItem.productCode]) when the
 *   merchant taught the mapping table that good, else a running line number, stable
 *   and unique per sheet. The name/description column beside it is what actually
 *   disambiguates the item.
 * - *واحد سنجش*: the domain has no unit of measure; left blank for the importer (or
 *   its default unit) to fill.
 * - *درصد/مبلغ تخفیف*: the domain stores a discount *amount*, never a percent, so the
 *   amount is emitted.
 *
 * **Counterparty.** *طرف حساب* is the seller first, buyer as fallback: the product's
 * exports are seller-prominent throughout, and the dominant flow is booking received
 * invoices. The fallback keeps the column non-empty when only the buyer was
 * extracted; either way the choice is visible in the cell, never silently switched.
 *
 * File lifecycle mirrors [DesktopBatchExportManager]: callers hand over a target file
 * and get a finished artifact or an exception. Parent directories are created; the
 * file is overwritten.
 */
class DesktopAccountingExportManager {

    /**
     * Writes [invoice] as a Sepidar-importable SpreadsheetML workbook into [targetFile].
     */
    fun exportSepidar(targetFile: File, invoice: Invoice) {
        exportTemplate(targetFile, listOf(invoice), AccountingTemplate.SEPIDAR)
    }

    /**
     * Writes [invoice] as a Holoo-importable SpreadsheetML workbook into [targetFile].
     */
    fun exportHoloo(targetFile: File, invoice: Invoice) {
        exportTemplate(targetFile, listOf(invoice), AccountingTemplate.HOLOO)
    }

    /**
     * Writes [invoices], flattened to line items with each row carrying its parent
     * invoice's identity, as a single [template] import sheet into [targetFile].
     *
     * Row and item-code numbering run across the whole batch rather than restarting
     * per invoice, which is what makes the sheet one import instead of invoices
     * stapled together.
     */
    fun exportBatchAccounting(
        targetFile: File,
        invoices: List<Invoice>,
        template: AccountingTemplate,
    ) {
        exportTemplate(targetFile, invoices, template)
    }

    // -- Shared document ---------------------------------------------------------

    private fun exportTemplate(
        targetFile: File,
        invoices: List<Invoice>,
        template: AccountingTemplate,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.append(XML_DECLARATION)
                writer.append(STYLE_PROCESSING_INSTRUCTION)
                writer.append(WORKBOOK_OPEN)

                writeStyles(writer)
                writeWorksheet(writer, invoices, template)

                writer.append(WORKBOOK_CLOSE)
                writer.flush()
            }
        }
    }

    private fun writeWorksheet(
        writer: OutputStreamWriter,
        invoices: List<Invoice>,
        template: AccountingTemplate,
    ) {
        val headers = when (template) {
            AccountingTemplate.SEPIDAR -> SEPIDAR_HEADERS
            AccountingTemplate.HOLOO -> HOLOO_HEADERS
        }
        val widths = when (template) {
            AccountingTemplate.SEPIDAR -> SEPIDAR_COLUMN_WIDTHS
            AccountingTemplate.HOLOO -> HOLOO_COLUMN_WIDTHS
        }
        // The numeric-column mask travels with the layout: amounts and counters are
        // typed Number so the importer sums and formats them, identity text stays
        // String so Persian names are never coerced into numbers or dates.
        val numericMask = when (template) {
            AccountingTemplate.SEPIDAR -> SEPIDAR_NUMERIC_MASK
            AccountingTemplate.HOLOO -> HOLOO_NUMERIC_MASK
        }
        val worksheetName = when (template) {
            AccountingTemplate.SEPIDAR -> SEPIDAR_WORKSHEET_NAME
            AccountingTemplate.HOLOO -> HOLOO_WORKSHEET_NAME
        }

        writer.append("<Worksheet ss:Name=\"$worksheetName\" ss:RightToLeft=\"1\">\n")
        writer.append(TABLE_OPEN)

        widths.forEach { width ->
            writer.append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"$width\"/>")
        }

        writer.append(ROW_OPEN)
        headers.forEach { header ->
            writeCell(writer, header, STYLE_TABLE_HEADER, isNumeric = false)
        }
        writer.append(ROW_CLOSE)

        var lineNumber = 1
        invoices.forEach { invoice ->
            invoice.items.forEach { item ->
                val row = when (template) {
                    AccountingTemplate.SEPIDAR -> sepidarRow(invoice, item, lineNumber)
                    AccountingTemplate.HOLOO -> holooRow(invoice, item, lineNumber)
                }
                lineNumber++

                writer.append(ROW_OPEN)
                row.forEachIndexed { index, value ->
                    // The mask types counters and amounts as Number — except an
                    // alphanumeric warehouse code, which Excel must receive as String
                    // or it coerces `WH-001` into a date-like disaster. Purely numeric
                    // codes keep the Number type the importer expects.
                    val numeric = numericMask[index] && !isAlphanumericCode(template, index, value)
                    val style = if (numeric) STYLE_AMOUNT else STYLE_VALUE
                    writeCell(writer, value, style, isNumeric = numeric)
                }
                writer.append(ROW_CLOSE)
            }
        }

        writer.append(TABLE_CLOSE)
        writer.append(WORKSHEET_CLOSE)
    }

    // -- Row contracts -----------------------------------------------------------

    /**
     * One Sepidar import row. Amounts cross into Rial here; quantities and identity
     * text pass through untouched.
     */
    private fun sepidarRow(invoice: Invoice, item: InvoiceItem, lineCode: Int): List<String> =
        listOf(
            counterparty(invoice),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
            productCodeOrFallback(item, lineCode),
            item.name,
            UNIT_OF_MEASURE_FALLBACK,
            formatAmount(item.quantity),
            formatRial(item.unitPrice, invoice.currency),
            formatRial(item.discount, invoice.currency),
            formatRial(item.tax, invoice.currency),
            formatRial(item.totalPrice, invoice.currency),
        )

    /**
     * One Holoo import row. Same money rule as Sepidar. The running number stays the
     * ردیف unconditionally; only the کد کالا column takes the mapped code.
     */
    private fun holooRow(invoice: Invoice, item: InvoiceItem, rowNumber: Int): List<String> =
        listOf(
            rowNumber.toString(),
            counterparty(invoice),
            invoice.invoiceNumber.orEmpty(),
            invoice.date.orEmpty(),
            productCodeOrFallback(item, rowNumber),
            item.name,
            formatAmount(item.quantity),
            formatRial(item.unitPrice, invoice.currency),
            formatRial(item.discount, invoice.currency),
            formatRial(item.tax, invoice.currency),
            formatRial(item.totalPrice, invoice.currency),
        )

    /**
     * The *کد کالا* cell: the mapped warehouse code when the merchant taught the table
     * that good, else the running line number. Blank codes read as unmapped — the table
     * cell holds what the user typed, and an empty field is not a code.
     */
    private fun productCodeOrFallback(item: InvoiceItem, fallback: Int): String =
        item.productCode?.takeIf { it.isNotBlank() } ?: fallback.toString()

    /**
     * True when [value] sits in the *کد کالا* column and is not a plain number — the
     * one case where the column mask's Number type must yield to String.
     */
    private fun isAlphanumericCode(template: AccountingTemplate, index: Int, value: String): Boolean {
        val codeIndex = when (template) {
            AccountingTemplate.SEPIDAR -> SEPIDAR_CODE_COLUMN_INDEX
            AccountingTemplate.HOLOO -> HOLOO_CODE_COLUMN_INDEX
        }
        return index == codeIndex && value.toDoubleOrNull() == null
    }

    /** Seller first, buyer as fallback — see the class contract for why. */
    private fun counterparty(invoice: Invoice): String =
        invoice.sellerName?.ifBlank { null }
            ?: invoice.buyerName?.ifBlank { null }
            ?: ""

    /**
     * Monetary amount in Rial. Toman values cross at ×10 (1 Toman = 10 Rial); Rial
     * and UNKNOWN pass through — UNKNOWN cannot be converted, so treating it as
     * already-Rial is stated here rather than guessed at silently.
     */
    private fun formatRial(value: Double, currency: CurrencyType): String =
        formatAmount(if (currency == CurrencyType.TOMAN) value * RIAL_PER_TOMAN else value)

    /**
     * Finite plain digits, no grouping and no locale decimal separator. Integer-valued
     * amounts render without a trailing `.0`, which is what a Persian invoice printed on
     * paper looks like and what Excel keeps numeric. Non-finite input — possible only
     * from a corrupt model, or a Toman fortune overflowing ×10 — degrades to `0`
     * rather than leaking `NaN` or `Infinity` into an import file.
     *
     * Beyond ±Long range `toLong()` would saturate instead of throwing, silently
     * collapsing a fortune to `9223372036854775807`; those values take the exact
     * [BigDecimal] road instead, so no overflow — silent or otherwise — can ever
     * reach the sheet.
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

    // -- SpreadsheetML plumbing (mirrors the sibling emitters) --------------------

    private fun writeStyles(writer: OutputStreamWriter) {
        writer.append(STYLES_OPEN)

        writer.append(
            "<Style ss:ID=\"Value\"><Alignment ss:Vertical=\"Center\"/></Style>",
        )

        // Import header: bold, tinted, centered.
        writer.append(
            "<Style ss:ID=\"TableHeader\"><Font ss:Bold=\"1\" ss:Size=\"11\"/>" +
                "<Interior ss:Color=\"#E8EDF2\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style>",
        )

        // Amounts: grouped thousands, kept right-aligned by RTL direction.
        writer.append(
            "<Style ss:ID=\"Amount\"><NumberFormat ss:Format=\"#,##0\"/>" +
                "<Alignment ss:Vertical=\"Center\"/></Style>",
        )

        writer.append(STYLES_CLOSE)
    }

    /**
     * One cell with an explicit type. Text is escaped so `&`, `<`, `>` and quotes can
     * never break the structure.
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
        const val SEPIDAR_WORKSHEET_NAME = "سپیدار"
        const val HOLOO_WORKSHEET_NAME = "هلو"

        val SEPIDAR_HEADERS = listOf(
            "کد/نام طرف حساب",
            "شماره فاکتور",
            "تاریخ (YYYY/MM/DD)",
            "کد کالا",
            "شرح کالا یا خدمات",
            "واحد سنجش",
            "تعداد",
            "مبلغ واحد (ریال)",
            "تخفیف",
            "مالیات و عوارض",
            "مبلغ کل سطر (ریال)",
        )

        val HOLOO_HEADERS = listOf(
            "ردیف",
            "نام طرف حساب",
            "شماره فاکتور",
            "تاریخ",
            "کد کالا",
            "نام کالا",
            "تعداد",
            "قیمت واحد (ریال)",
            "درصد/مبلغ تخفیف",
            "ارزش افزوده",
            "جمع کل سطر",
        )

        /** Column widths in points, sized for the widest realistic Persian content. */
        val SEPIDAR_COLUMN_WIDTHS = intArrayOf(170, 110, 110, 70, 220, 70, 55, 110, 90, 100, 120)
        val HOLOO_COLUMN_WIDTHS = intArrayOf(45, 170, 110, 90, 70, 200, 55, 110, 100, 90, 120)

        /**
         * Per-column numeric mask, parallel to the header lists: `true` marks the
         * counter and amount columns that Excel must type as Number.
         */
        val SEPIDAR_NUMERIC_MASK = booleanArrayOf(
            false, false, false, true, false, false, true, true, true, true, true,
        )
        val HOLOO_NUMERIC_MASK = booleanArrayOf(
            true, false, false, false, true, false, true, true, true, true, true,
        )

        /** The domain carries no unit of measure; the column stays blank (see contract). */
        const val UNIT_OF_MEASURE_FALLBACK = ""

        /**
         * *کد کالا* column positions, parallel to the header lists — the only columns
         * whose Number type is content-dependent (see [isAlphanumericCode]).
         */
        const val SEPIDAR_CODE_COLUMN_INDEX = 3
        const val HOLOO_CODE_COLUMN_INDEX = 4

        const val RIAL_PER_TOMAN = 10.0

        /**
         * First double past `Long.MAX_VALUE` (2^63): every smaller-magnitude integral
         * double converts exactly with `toLong()`, everything at or above it needs
         * [BigDecimal] to keep its digits.
         */
        const val LONG_RANGE_CEILING = 9.223372036854776E18

        const val STYLE_VALUE = "Value"
        const val STYLE_TABLE_HEADER = "TableHeader"
        const val STYLE_AMOUNT = "Amount"

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
