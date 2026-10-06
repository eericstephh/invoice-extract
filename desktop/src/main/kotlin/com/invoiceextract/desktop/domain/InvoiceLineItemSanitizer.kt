package com.invoiceextract.desktop.domain

import com.invoiceextract.domain.model.InvoiceItem

/**
 * Strips ghost subtotal rows from extracted line items.
 *
 * The model sometimes echoes an invoice's own summary lines — «جمع کل»,
 * «مالیات بر ارزش افزوده», a trailing `subtotal` — as if they were goods
 * bought. Left alone, those phantoms inflate ledgers and surface in the
 * "Top Purchased Items" leaderboard next to real products. This predicate
 * recognizes them by name and drops them before enrichment, so a ghost row
 * never teaches the mapping table either.
 *
 * The verdict is deliberately conservative: a bulk line that merely mentions
 * a keyword (quantity above one *and* a unit price distinct from the line
 * total) survives, because only ghost rows lack a genuine quantity × unit
 * breakdown.
 *
 * Pure and stateless: safe to call from any thread and any dispatcher.
 */
object InvoiceLineItemSanitizer {

    /**
     * Returns [items] without their summary rows, in order. A list with no
     * ghosts comes back unchanged.
     */
    fun sanitize(items: List<InvoiceItem>): List<InvoiceItem> =
        items.filterNot(::isSummaryRow)

    /**
     * True when [item] reads as a printed summary rather than a good bought:
     * its normalized name matches a summary keyword and it carries no genuine
     * quantity/unit-price distinction.
     */
    fun isSummaryRow(item: InvoiceItem): Boolean {
        if (!containsSummaryKeyword(item.name)) return false
        // A genuine bulk line that merely mentions a keyword — e.g. five units
        // of something with "total" in its name — keeps its breakdown and stays.
        if (item.quantity > 1.0 && item.unitPrice != item.totalPrice) return false
        return true
    }

    private fun containsSummaryKeyword(name: String): Boolean {
        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return false
        return SUMMARY_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    /**
     * Match-normalization for names: trimmed, Latin lowercased, Arabic kaf/yeh
     * unified to Persian, invisible joiners and marks stripped, whitespace
     * collapsed. Mirrors [com.invoiceextract.desktop.data.text.DesktopPersianNormalizer]'s
     * letter rules without pulling the document pipeline into a pure predicate.
     */
    private fun normalizeName(name: String): String {
        val unified = buildString(name.length) {
            for (c in name.trim()) {
                when (c) {
                    'ك' -> append('ک')
                    'ي', 'ى' -> append('ی')
                    '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF' -> append("")
                    else -> append(c.lowercaseChar())
                }
            }
        }
        return unified.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val SUMMARY_KEYWORDS = listOf(
        "جمع جزء",
        "جزء جمع",
        "جمع کل",
        "سرجمع",
        "تخفیف کل",
        "مالیات بر ارزش افزوده",
        "مجموع فاکتور",
        "subtotal",
        "grand total",
        "total discount",
    )

    private val WHITESPACE = Regex("\\s+")
}
