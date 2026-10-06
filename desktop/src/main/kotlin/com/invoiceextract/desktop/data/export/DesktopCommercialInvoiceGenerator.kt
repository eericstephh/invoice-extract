package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.util.convertNumberToEnglishWords
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import kotlin.math.absoluteValue

/**
 * Zero-dependency generator of the standard commercial / tax invoice as a
 * standalone, self-contained printable `.html` document — no libraries, no
 * network fonts, no external assets. The international counterpart to
 * [DesktopFormalInvoiceGenerator]: where that one serves the Iranian Section
 * 169 format, this one serves the global workspace.
 *
 * The sheet is a single file the default browser opens ready to print
 * (`Ctrl+P`, plus an automatic `window.print()` on load and an on-page print
 * button hidden from the printout itself): LTR, `@page size auto` with 15mm
 * margins (adapting to A4 or US Letter), western corporate type
 * (Inter/Helvetica fallback), hairline grids, the vendor/bill-to identity
 * boxes, the line-item table, the totals with the grand total due as a
 * prominent badge and spelled out in English words, and an authorized
 * signature box.
 *
 * **Money is printed, not recomputed.** Line cells carry what the pipeline
 * validated, in the invoice's own currency; the words line spells the printed
 * [Invoice.grandTotal] (not the Toman-normalized figure, which is a ledger
 * concern, not a document one).
 *
 * **No invented content.** Project/reference prints only when present; postal
 * code and address have no domain source and degrade openly to an em dash;
 * there are no payment-terms sentences because the domain carries no terms.
 *
 * File lifecycle mirrors the sibling managers: the caller hands over a target
 * file and gets it back finished, or an exception. Parent directories are
 * created; the file is overwritten.
 */
class DesktopCommercialInvoiceGenerator {

    /**
     * Writes the commercial sheet for [invoice] into [targetFile].
     *
     * @return [Result.success] with [targetFile], or [Result.failure] when the
     *   file cannot be written.
     */
    fun generateCommercialInvoiceHtml(
        targetFile: File,
        invoice: Invoice,
    ): Result<File> =
        runCatching {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(buildDocument(invoice), StandardCharsets.UTF_8)
            targetFile
        }

    // -- Document ------------------------------------------------------------

    private fun buildDocument(invoice: Invoice): String = buildString {
        append(DOCTYPE)
        append("<html lang=\"en\" dir=\"ltr\">\n<head>\n")
        append("<meta charset=\"UTF-8\">\n")
        append("<title>")
        append(escape("Commercial Invoice - ${invoice.invoiceNumber ?: "—"}"))
        append("</title>\n<style>\n")
        append(STYLES)
        append("</style>\n</head>\n")
        append("<body onload=\"window.print()\">\n")
        append("<div class=\"page\">\n")

        append("<h1 class=\"doc-title\">COMMERCIAL INVOICE</h1>\n")
        append("<p class=\"doc-sub\">Standard Commercial / Tax Invoice</p>\n")

        append("<table class=\"meta\">\n")
        appendMetaRow("Invoice Number", invoice.invoiceNumber ?: "—")
        appendMetaRow("Issue Date", invoice.date ?: "—")
        appendMetaRow("Due Date", invoice.dueDate ?: "—")
        appendMetaRow("Currency", invoice.currency.name)
        invoice.clientName?.takeIf { it.isNotBlank() }?.let { appendMetaRow("Project / Reference", it) }
        invoice.projectName?.takeIf { it.isNotBlank() }?.let { appendMetaRow("Campaign", it) }
        append("</table>\n")

        appendPartyBox(
            title = "Vendor / Seller",
            name = invoice.sellerName,
            taxId = invoice.sellerTaxId,
            nationalId = invoice.sellerNationalId,
            clientName = null,
        )
        appendPartyBox(
            title = "Bill To / Client",
            name = invoice.buyerName,
            taxId = invoice.buyerTaxId,
            nationalId = invoice.buyerNationalId,
            clientName = invoice.clientName,
        )
        appendItemsTable(invoice)
        appendSummary(invoice)

        append("<div class=\"signatures\">\n")
        append("<div class=\"sign-box\">Authorized Signature</div>\n")
        append("</div>\n")

        append("<button class=\"print-btn\" onclick=\"window.print()\">Print</button>\n")
        append("</div>\n</body>\n</html>")
    }

    private fun StringBuilder.appendMetaRow(label: String, value: String) {
        append("<tr><th>")
        append(escape(label))
        append("</th><td>")
        append(escape(value))
        append("</td></tr>\n")
    }

    /**
     * One identity box. The domain carries names, national IDs and tax IDs;
     * postal code and address have no domain source, so they degrade openly
     * to an em dash rather than inventing data.
     */
    private fun StringBuilder.appendPartyBox(
        title: String,
        name: String?,
        nationalId: String?,
        taxId: String?,
        clientName: String?,
    ) {
        append("<div class=\"party\">\n<h2>")
        append(escape(title))
        append("</h2>\n<table class=\"grid\">\n")
        appendPartyRow("Name", name?.ifBlank { null } ?: "—")
        appendPartyRow("Tax ID / EIN / VAT", taxId?.ifBlank { null } ?: "—")
        appendPartyRow("National ID", nationalId?.ifBlank { null } ?: "—")
        clientName?.takeIf { it.isNotBlank() }?.let { appendPartyRow("Client", it) }
        appendPartyRow("Postal Code", "—")
        appendPartyRow("Address", "—")
        append("</table>\n</div>\n")
    }

    private fun StringBuilder.appendPartyRow(label: String, value: String) {
        append("<tr><th>")
        append(escape(label))
        append("</th><td>")
        append(escape(value))
        append("</td></tr>\n")
    }

    private fun StringBuilder.appendItemsTable(invoice: Invoice) {
        append("<table class=\"grid items\">\n<thead><tr>")
        ITEM_HEADERS.forEach { header ->
            append("<th>")
            append(escape(header))
            append("</th>")
        }
        append("</tr></thead>\n<tbody>\n")
        if (invoice.items.isEmpty()) {
            append("<tr><td colspan=\"6\" class=\"center\">—</td></tr>\n")
        } else {
            invoice.items.forEachIndexed { index, item ->
                appendItemRow(index + 1, item)
            }
        }
        append("</tbody>\n</table>\n")
    }

    private fun StringBuilder.appendItemRow(rowNumber: Int, item: InvoiceItem) {
        append("<tr>")
        appendCell(rowNumber.toString())
        appendCell(item.name.ifBlank { "—" })
        appendCell(formatAmount(item.quantity))
        appendCell(formatAmount(item.unitPrice))
        appendCell(formatAmount(item.discount))
        appendCell(formatAmount(item.totalPrice))
        append("</tr>\n")
    }

    private fun StringBuilder.appendCell(value: String) {
        append("<td>")
        append(escape(value))
        append("</td>\n")
    }

    private fun StringBuilder.appendSummary(invoice: Invoice) {
        append("<table class=\"summary\">\n")
        appendSummaryRow("Subtotal", formatAmount(invoice.subtotal))
        appendSummaryRow("Total Discount", formatAmount(invoice.totalDiscount))
        appendSummaryRow("Tax / VAT", formatAmount(invoice.totalTax))
        append("</table>\n")
        append("<p class=\"grand-total\">Grand Total Due: ")
        append(escape(formatAmount(invoice.grandTotal)))
        append(" ")
        append(escape(invoice.currency.name))
        append("</p>\n")
        append("<p class=\"words\">Amount in Words: ")
        append(escape(convertNumberToEnglishWords(invoice.grandTotal, invoice.currency.name)))
        append("</p>\n")
    }

    private fun StringBuilder.appendSummaryRow(label: String, value: String) {
        append("<tr><th>")
        append(escape(label))
        append("</th><td>")
        append(escape(value))
        append("</td></tr>\n")
    }

    /**
     * Finite plain digits, no grouping — the sibling SpreadsheetML contract,
     * kept identical here so the printed sheet and the exported ledgers never
     * disagree on a figure.
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

    /** Escapes the four HTML-significant characters. */
    private fun escape(value: String): String =
        if (value.none { it in HTML_SPECIAL_CHARS }) {
            value
        } else {
            buildString(value.length) {
                for (c in value) {
                    when (c) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        else -> append(c)
                    }
                }
            }
        }

    private companion object {
        const val DOCTYPE = "<!DOCTYPE html>\n"

        val ITEM_HEADERS = listOf(
            "Line #",
            "Description",
            "Quantity",
            "Unit Price",
            "Discount",
            "Amount",
        )

        /**
         * First double past `Long.MAX_VALUE` (2^63): every smaller-magnitude
         * integral double converts exactly with `toLong()`, everything at or
         * above it needs [BigDecimal] to keep its digits.
         */
        const val LONG_RANGE_CEILING = 9.223372036854776E18

        val HTML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"')

        const val STYLES = """
            @page { size: auto; margin: 15mm; }
            * { box-sizing: border-box; }
            body { font-family: Inter, Helvetica, Arial, sans-serif; color: #111; background: #fff; margin: 0; }
            .page { max-width: 190mm; margin: 0 auto; }
            .doc-title { text-align: center; font-size: 22px; letter-spacing: 2px; margin: 0 0 4px; }
            .doc-sub { text-align: center; font-size: 11px; color: #444; margin: 0 0 12px; }
            table { width: 100%; border-collapse: collapse; margin-bottom: 12px; font-size: 12px; }
            table.grid th, table.grid td { border: 1px solid #333; padding: 6px 8px; }
            table.grid th { background: #f2f4f7; }
            table.meta th, table.meta td, table.summary th, table.summary td { border: 1px solid #333; padding: 6px 8px; }
            table.meta th, table.summary th { background: #f2f4f7; width: 28%; }
            table.items td, table.items th { text-align: center; }
            table.items td:nth-child(2) { text-align: left; }
            .party h2 { font-size: 14px; margin: 12px 0 6px; text-transform: uppercase; letter-spacing: 1px; }
            .grand-total { font-size: 16px; font-weight: bold; border: 2px solid #111; padding: 10px; text-align: right; }
            .words { font-size: 13px; font-style: italic; border-top: 1px solid #333; padding-top: 8px; }
            .signatures { display: flex; gap: 12px; margin-top: 32px; }
            .sign-box { flex: 1; height: 90px; border: 1.5px solid #333; text-align: center; padding-top: 8px; font-size: 12px; }
            .print-btn { display: block; margin: 16px auto; padding: 8px 32px; font-size: 14px; cursor: pointer; }
            .center { text-align: center; }
            @media print { .print-btn { display: none; } }
        """
    }
}
