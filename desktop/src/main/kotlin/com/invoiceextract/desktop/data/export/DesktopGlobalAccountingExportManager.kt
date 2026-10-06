package com.invoiceextract.desktop.data.export

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Which international import layout to emit. Exhaustive by design: the batch
 * consolidator switches on it, so adding a vendor is a compile error in every
 * emitter until the new layout is drawn — the same rule [AccountingTemplate]
 * follows for the Iranian vendors.
 */
enum class GlobalAccountingTemplate {
    QUICKBOOKS,
    XERO,
}

/**
 * Zero-dependency generator of international accounting import sheets
 * (QuickBooks, Xero) as RFC 4180 CSV — no Apache POI, no third-party jar,
 * exactly like the ledger CSV emitter beside it.
 *
 * Each template flattens an invoice to one row per line item with the parent
 * invoice's identity on every row, because an import row without its invoice
 * number is un-auditable once it lands in the ledger. The batch entry point
 * runs the same rows across the whole archive without restarting numbering —
 * numbering here means row order only, since neither template carries a line
 * sequence column.
 *
 * **Money is printed, not recomputed and never converted.** Rate is the
 * printed `unitPrice`, Amount the printed `totalPrice`, both at two decimals —
 * what the source document actually says, in the invoice's own currency. A
 * global template converts nothing: the books receiving it own the FX policy.
 *
 * **Dates are normalized in shape, not in calendar.** `YYYY/MM/DD` (or `-`/`.`
 * variants) become `MM/DD/YYYY`; anything else — including Jalali strings the
 * extractor keeps verbatim — passes through untouched, because a converter
 * that cannot tell 1403 from 2024 must not guess. Global templates assume
 * Gregorian books; Jalali archives should convert before import.
 *
 * **Fields the domain does not carry.** Each degrades openly instead of
 * inventing data:
 * - QuickBooks `TaxCode`: the domain stores tax *amounts*, never jurisdiction
 *   codes — always blank, for the importer to fill.
 * - Xero `AccountCode`: no chart of accounts exists here — always blank.
 * - Xero `Currency`: the invoice's own code; UNKNOWN stays blank so Xero falls
 *   back to the base currency instead of booking dollars that were never seen.
 * - Xero `TaxType`: per-line — `Tax on Purchases` when the line carries tax,
 *   else `Zero Rated`.
 *
 * **Counterparty.** The seller first, buyer as fallback — the product's exports
 * are seller-prominent throughout, and the dominant flow is booking received
 * invoices.
 *
 * File lifecycle mirrors [DesktopBatchExportManager]: callers hand over a
 * target file and get a finished artifact or an exception. Parent directories
 * are created; the file is overwritten.
 */
class DesktopGlobalAccountingExportManager {

    /**
     * Writes [invoice] as a QuickBooks-importable CSV into [targetFile].
     */
    fun exportQuickBooks(targetFile: File, invoice: Invoice) {
        exportTemplate(targetFile, listOf(invoice), GlobalAccountingTemplate.QUICKBOOKS)
    }

    /**
     * Writes [invoice] as a Xero-importable CSV into [targetFile].
     */
    fun exportXero(targetFile: File, invoice: Invoice) {
        exportTemplate(targetFile, listOf(invoice), GlobalAccountingTemplate.XERO)
    }

    /**
     * Writes [invoices], flattened to line items with each row carrying its
     * parent invoice's identity, as a single [template] import file into
     * [targetFile]. An invoice with no line items contributes no rows — a
     * required-field row with empty item cells would fail validation on
     * import, so silence beats a placeholder.
     */
    fun exportBatchGlobalAccounting(
        targetFile: File,
        invoices: List<Invoice>,
        template: GlobalAccountingTemplate,
    ) {
        exportTemplate(targetFile, invoices, template)
    }

    // -- Shared document ---------------------------------------------------------

    private fun exportTemplate(
        targetFile: File,
        invoices: List<Invoice>,
        template: GlobalAccountingTemplate,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { stream ->
            // BOM first, as raw bytes, so it is never re-encoded by the writer.
            stream.write(BOM)
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                val headers = when (template) {
                    GlobalAccountingTemplate.QUICKBOOKS -> QUICKBOOKS_HEADERS
                    GlobalAccountingTemplate.XERO -> XERO_HEADERS
                }
                writeRow(writer, headers)
                invoices.forEach { invoice ->
                    invoice.items.forEach { item ->
                        when (template) {
                            GlobalAccountingTemplate.QUICKBOOKS ->
                                writeQuickBooksRow(writer, invoice, item)
                            GlobalAccountingTemplate.XERO ->
                                writeXeroRow(writer, invoice, item)
                        }
                    }
                }
                writer.flush()
            }
        }
    }

    private fun writeQuickBooksRow(
        writer: OutputStreamWriter,
        invoice: Invoice,
        item: InvoiceItem,
    ) {
        writeRow(
            writer,
            listOf(
                counterpartyOf(invoice),
                invoice.invoiceNumber.orEmpty(),
                exportDate(invoice.date),
                exportDate(invoice.dueDate),
                item.name,
                item.productCode.orEmpty(),
                formatQuantity(item.quantity),
                formatMoney(item.unitPrice),
                formatMoney(item.totalPrice),
                "",
            ),
        )
    }

    private fun writeXeroRow(
        writer: OutputStreamWriter,
        invoice: Invoice,
        item: InvoiceItem,
    ) {
        writeRow(
            writer,
            listOf(
                counterpartyOf(invoice),
                invoice.invoiceNumber.orEmpty(),
                exportDate(invoice.date),
                exportDate(invoice.dueDate),
                item.name,
                formatQuantity(item.quantity),
                formatMoney(item.unitPrice),
                "",
                if (item.tax > 0.0) TAX_ON_PURCHASES else TAX_ZERO_RATED,
                formatMoney(item.tax),
                currencyCodeOf(invoice),
            ),
        )
    }

    /** Seller first, buyer as fallback — the exports are seller-prominent. */
    private fun counterpartyOf(invoice: Invoice): String =
        invoice.sellerName?.trim()?.takeIf { it.isNotBlank() }
            ?: invoice.buyerName?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

    /** The invoice's own code; UNKNOWN stays blank for the base-currency fallback. */
    private fun currencyCodeOf(invoice: Invoice): String =
        if (invoice.currency == CurrencyType.UNKNOWN) "" else invoice.currency.name

    /**
     * `YYYY/MM/DD` (or `-`/`.` variants) becomes `MM/DD/YYYY`; anything else
     * passes through untouched — see the class docs on calendars.
     */
    private fun exportDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val match = DATE_PARTS.matchEntire(raw.trim()) ?: return raw.trim()
        val (year, month, day) = match.destructured
        return "${month.padStart(2, '0')}/${day.padStart(2, '0')}/$year"
    }

    /** Two decimals, locale-proof: import money is always ASCII. */
    private fun formatMoney(value: Double): String =
        if (!value.isFinite()) {
            "0.00"
        } else {
            "%.2f".format(Locale.ROOT, value)
        }

    /** Quantities may be fractional; whole ones drop the decimals. */
    private fun formatQuantity(quantity: Double): String =
        if (!quantity.isFinite()) {
            "0"
        } else if (quantity % 1.0 == 0.0) {
            quantity.toLong().toString()
        } else {
            quantity.toString()
        }

    /**
     * One RFC 4180 record: fields containing a comma, quote or line break are
     * wrapped in quotes with inner quotes doubled; CRLF terminates the record
     * so Excel and both importers read one line per row on any host.
     */
    private fun writeRow(writer: OutputStreamWriter, cells: List<String>) {
        writer.append(cells.joinToString(",") { escape(it) })
        writer.append(CRLF)
    }

    private fun escape(cell: String): String {
        if (cell.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return cell
        }
        return "\"" + cell.replace("\"", "\"\"") + "\""
    }

    private companion object {
        val QUICKBOOKS_HEADERS = listOf(
            "*Vendor",
            "*InvoiceNo",
            "*InvoiceDate",
            "*DueDate",
            "*ItemName",
            "ItemDescription",
            "ItemQuantity",
            "ItemRate",
            "*ItemAmount",
            "TaxCode",
        )

        val XERO_HEADERS = listOf(
            "*ContactName",
            "*InvoiceNumber",
            "*InvoiceDate",
            "*DueDate",
            "*Description",
            "*Quantity",
            "*UnitAmount",
            "*AccountCode",
            "*TaxType",
            "TaxAmount",
            "Currency",
        )

        const val TAX_ON_PURCHASES = "Tax on Purchases"
        const val TAX_ZERO_RATED = "Zero Rated"

        val DATE_PARTS = Regex("""(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})""")

        const val CRLF = "\r\n"

        val BOM = "\uFEFF".toByteArray(StandardCharsets.UTF_8)
    }
}
