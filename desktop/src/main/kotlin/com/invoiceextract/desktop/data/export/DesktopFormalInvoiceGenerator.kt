package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.util.convertNumberToWords
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import kotlin.math.absoluteValue

/**
 * Zero-dependency generator of the formal Iranian tax invoice
 * (صورتحساب فروش کالا و خدمات، ماده ۱۶۹ مکرر قانون مالیات‌های مستقیم) as a
 * standalone, self-contained printable A4 `.html` document — no libraries, no
 * network fonts, no external assets.
 *
 * The sheet is a single file the default browser opens ready to print
 * (`Ctrl+P`, plus an automatic `window.print()` on load and an on-page print
 * button hidden from the printout itself): RTL, `@page A4 portrait` with
 * 10mm margins, high-contrast grid borders, the seller/buyer identity boxes,
 * the nine-column line table, the totals with the grand total spelled out in
 * Persian words, and the dual seller/buyer signature and stamp boxes.
 *
 * **Money is printed, not recomputed.** Line cells carry what the pipeline
 * validated; the words line spells the invoice's [Invoice.effectiveTomanTotal],
 * so foreign money settles in Toman exactly like every other ledger.
 *
 * File lifecycle mirrors the sibling managers: the caller hands over a target
 * file and gets it back finished, or an exception. Parent directories are
 * created; the file is overwritten.
 */
class DesktopFormalInvoiceGenerator {

    /**
     * Writes the formal A4 sheet for [invoice] into [targetFile].
     *
     * @return [Result.success] with [targetFile], or [Result.failure] when the
     *   file cannot be written.
     */
    fun generateFormalInvoiceHtml(
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
        append("<html lang=\"fa\" dir=\"rtl\">\n<head>\n")
        append("<meta charset=\"UTF-8\">\n")
        append("<title>")
        append(escape("صورتحساب فروش کالا و خدمات - ${invoice.invoiceNumber ?: "—"}"))
        append("</title>\n<style>\n")
        append(STYLES)
        append("</style>\n</head>\n")
        append("<body onload=\"window.print()\">\n")
        append("<div class=\"page\">\n")

        appendHeader(invoice)
        appendPartyBox(
            title = "مشخصات فروشنده",
            name = invoice.sellerName,
            nationalId = invoice.sellerNationalId,
            taxId = invoice.sellerTaxId,
        )
        appendPartyBox(
            title = "مشخصات خریدار",
            name = invoice.buyerName,
            nationalId = invoice.buyerNationalId,
            taxId = invoice.buyerTaxId,
        )
        appendItemsTable(invoice)
        appendSummary(invoice)

        append("<div class=\"signatures\">\n")
        append("<div class=\"sign-box\">مهر و امضای فروشنده</div>\n")
        append("<div class=\"sign-box\">مهر و امضای خریدار</div>\n")
        append("</div>\n")

        append("<button class=\"print-btn\" onclick=\"window.print()\">چاپ</button>\n")
        append("</div>\n</body>\n</html>")
    }

    private fun StringBuilder.appendHeader(invoice: Invoice) {
        append("<h1 class=\"doc-title\">صورتحساب فروش کالا و خدمات</h1>\n")
        append("<p class=\"doc-sub\">Iranian Official Tax Invoice Template — ماده ۱۶۹ مکرر قانون مالیات‌های مستقیم</p>\n")
        append("<table class=\"meta\">\n")
        appendMetaRow("شماره فاکتور", invoice.invoiceNumber ?: "—")
        appendMetaRow("تاریخ صدور", invoice.date ?: "—")
        invoice.clientName?.takeIf { it.isNotBlank() }?.let { appendMetaRow("کارفرما", it) }
        invoice.projectName?.takeIf { it.isNotBlank() }?.let { appendMetaRow("پروژه", it) }
        append("</table>\n")
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
    ) {
        append("<div class=\"party\">\n<h2>")
        append(escape(title))
        append("</h2>\n<table class=\"grid\">\n")
        appendPartyRow("نام شخص حقیقی / حقوقی", name?.ifBlank { null } ?: "—")
        appendPartyRow("شناسه / کد ملی", nationalId?.ifBlank { null } ?: "—")
        appendPartyRow("شماره اقتصادی", taxId?.ifBlank { null } ?: "—")
        appendPartyRow("کد پستی", "—")
        appendPartyRow("نشانی", "—")
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
            append("<tr><td colspan=\"9\" class=\"center\">—</td></tr>\n")
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
        appendCell(item.productCode?.takeIf { it.isNotBlank() } ?: rowNumber.toString())
        appendCell(item.name.ifBlank { "—" })
        appendCell(formatAmount(item.quantity))
        appendCell(UNIT_FALLBACK)
        appendCell(formatAmount(item.unitPrice))
        appendCell(formatAmount(item.discount))
        appendCell(formatAmount(item.tax))
        appendCell(formatAmount(item.totalPrice))
        append("</tr>\n")
    }

    private fun StringBuilder.appendCell(value: String) {
        append("<td>")
        append(escape(value))
        append("</td>")
    }

    private fun StringBuilder.appendSummary(invoice: Invoice) {
        append("<table class=\"summary\">\n")
        appendSummaryRow("جمع کل ناخالص", formatAmount(invoice.subtotal))
        appendSummaryRow("مجموع تخفیفات", formatAmount(invoice.totalDiscount))
        appendSummaryRow("مالیات بر ارزش افزوده", formatAmount(invoice.totalTax))
        appendSummaryRow("مبلغ نهایی فاکتور (تومان)", formatAmount(invoice.grandTotal))
        append("</table>\n")
        append("<p class=\"words\">مبلغ کل به حروف: ")
        append(escape(convertNumberToWords(invoice.effectiveTomanTotal)))
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
            "ردیف",
            "کد کالا",
            "شرح کالا یا خدمات",
            "تعداد",
            "واحد",
            "مبلغ واحد (تومان)",
            "تخفیف",
            "مالیات و عوارض",
            "مبلغ کل (تومان)",
        )

        /** The domain carries no unit of measure; the column stays blank. */
        const val UNIT_FALLBACK = ""

        /**
         * First double past `Long.MAX_VALUE` (2^63): every smaller-magnitude
         * integral double converts exactly with `toLong()`, everything at or
         * above it needs [BigDecimal] to keep its digits.
         */
        const val LONG_RANGE_CEILING = 9.223372036854776E18

        val HTML_SPECIAL_CHARS = charArrayOf('&', '<', '>', '"')

        const val STYLES = """
            @page { size: A4 portrait; margin: 10mm; }
            * { box-sizing: border-box; }
            body { font-family: Tahoma, Arial, sans-serif; color: #000; background: #fff; margin: 0; }
            .page { max-width: 190mm; margin: 0 auto; }
            .doc-title { text-align: center; font-size: 20px; margin: 0 0 4px; }
            .doc-sub { text-align: center; font-size: 11px; color: #333; margin: 0 0 12px; }
            table { width: 100%; border-collapse: collapse; margin-bottom: 12px; font-size: 12px; }
            table.grid th, table.grid td { border: 1.5px solid #000; padding: 6px 8px; }
            table.grid th { background: #f0f0f0; }
            table.meta th, table.meta td, table.summary th, table.summary td { border: 1.5px solid #000; padding: 6px 8px; }
            table.meta th, table.summary th { background: #f0f0f0; width: 28%; }
            table.items td, table.items th { text-align: center; }
            .party h2 { font-size: 14px; margin: 12px 0 6px; }
            .words { font-size: 14px; font-weight: bold; border: 2px solid #000; padding: 10px; }
            .signatures { display: flex; gap: 12px; margin-top: 24px; }
            .sign-box { flex: 1; height: 90px; border: 1.5px solid #000; text-align: center; padding-top: 8px; font-size: 12px; }
            .print-btn { display: block; margin: 16px auto; padding: 8px 32px; font-size: 14px; cursor: pointer; }
            .center { text-align: center; }
            @media print { .print-btn { display: none; } }
        """
    }
}
