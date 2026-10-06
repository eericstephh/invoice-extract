package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.model.isUnpaid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spend share of one seller over the archived invoices.
 *
 * @property name The seller name as printed; never blank (see [computeAnalytics]).
 * @property totalAmount Toman summed over the seller's invoices, truncated.
 * @property percentage Share of [DashboardAnalytics.totalSpend], `0f..100f`.
 */
data class VendorStat(
    val name: String,
    val totalAmount: Long,
    val percentage: Float,
)

/**
 * Spend bucket of one month.
 *
 * @property monthName The bucket label in the archive's own calendar: Persian
 *   month and year (`"مرداد 1403"`) for Jalali dates, Western abbreviation and
 *   year (`"Oct 2026"`) for Gregorian ones. The year rides along because two
 *   Octobers from different years must never merge into one bar.
 * @property totalAmount Toman summed over the month's invoices, truncated.
 */
data class MonthlyExpense(
    val monthName: String,
    val totalAmount: Long,
)

/**
 * One of the most expensive goods or services bought across the archive.
 *
 * @property name The line-item name as printed; blank names are dropped.
 * @property quantity Total units bought, fractional units included.
 * @property totalAmount Toman summed over every line carrying this name, truncated.
 */
data class TopItemStat(
    val name: String,
    val quantity: Double,
    val totalAmount: Long,
)

/**
 * The whole Mini-BI snapshot rendered by the analytics dashboard.
 *
 * Every amount is a truncated [Long], matching [com.invoiceextract.desktop.presentation.AmountFormatter.formatToman]:
 * Toman has no subunit, so the fractional remainder is display noise, never money.
 */
data class DashboardAnalytics(
    val totalSpend: Long = 0L,
    val invoiceCount: Int = 0,
    val averageInvoice: Long = 0L,
    val vendorCount: Int = 0,
    val topVendors: List<VendorStat> = emptyList(),
    val monthlyExpenses: List<MonthlyExpense> = emptyList(),
    val topItems: List<TopItemStat> = emptyList(),
    /**
     * Open receivables: Toman summed over every invoice whose
     * [PaymentStatus] is not [PaymentStatus.PAID], via
     * [Invoice.effectiveTomanTotal] like every other money aggregate.
     */
    val totalUnpaidAmount: Long = 0L,
    /** How many archived invoices are still unpaid. */
    val unpaidCount: Int = 0,
    /** How many of them are flagged [PaymentStatus.OVERDUE]. */
    val overdueCount: Int = 0,
)

/**
 * Reduces the archived invoices into [DashboardAnalytics].
 *
 * Pure apart from the dispatcher hop: no I/O, no clock, no mutable state, so the same
 * archive always yields the same snapshot. Runs on [Dispatchers.Default] because an
 * archive of thousands of invoices groups and sorts off the render thread.
 *
 * Conventions, each chosen so a malformed field degrades instead of crashing:
 * - Money aggregates uniformly in Toman via [Invoice.effectiveTomanTotal], so USD,
 *   EUR, USDT and Rial invoices join local ones without currency mixing. Line-item
 *   ranks scale by their invoice's Toman factor for the same reason.
 * - Dates bucket by calendar and mode: Persian mode reads Jalali `YYYY/MM/DD`
 *   (the extractor's contract) into Persian month buckets; English mode reads
 *   Gregorian `YYYY-MM-DD` (or `/`-separated) into Western buckets. In either
 *   mode the other calendar's dates only drop their monthly bucket — totals,
 *   vendors and items still count them — exactly like an unparseable date.
 *   Persian and Arabic-Indic digits are unified first.
 * - Years outside 1390..1410 (Jalali) or 2000..2099 (Gregorian) count as
 *   unparseable for bucketing, so a typo like `1443` never renders a bar of
 *   its own.
 * - A blank seller becomes `"فروشنده نامشخص"` for grouping, but [DashboardAnalytics.vendorCount]
 *   counts distinct *named* sellers only.
 * - Blank item names are dropped: there is nothing to rank them by.
 * - Ties break alphabetically so the top-five lists are deterministic.
 *
 * @param isEnglish `true` groups monthly expenses by Western calendar month
 *   (`"Oct 2026"`); `false` (the default, preserving every existing caller)
 *   groups by Jalali month.
 */
suspend fun computeAnalytics(
    invoices: List<Invoice>,
    isEnglish: Boolean = false,
): DashboardAnalytics =
    withContext(Dispatchers.Default) {
        if (invoices.isEmpty()) return@withContext DashboardAnalytics()

        val spend = invoices.sumOf { it.effectiveTomanTotal.toDouble() }
        val totalSpend = spend.toLong()

        val vendorTotals = invoices
            .groupBy { it.sellerName?.trim().takeIf { name -> !name.isNullOrBlank() } ?: UNKNOWN_VENDOR }
            .mapValues { (_, group) -> group.sumOf { it.effectiveTomanTotal.toDouble() } }

        val topVendors = vendorTotals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(TOP_COUNT)
            .map { (name, vendorSpend) ->
                VendorStat(
                    name = name,
                    totalAmount = vendorSpend.toLong(),
                    percentage = if (spend > 0.0) {
                        (vendorSpend / spend * 100.0).toFloat().coerceIn(0f, 100f)
                    } else {
                        0f
                    },
                )
            }

        val monthlyExpenses = invoices
            .mapNotNull { invoice ->
                monthlyBucket(invoice, isEnglish)?.let { bucket ->
                    bucket to invoice.effectiveTomanTotal.toDouble()
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
            .toSortedMap(compareBy<MonthBucket>({ it.year }, { it.month }))
            .map { (bucket, monthSpend) ->
                MonthlyExpense(
                    monthName = bucket.label,
                    totalAmount = monthSpend.toLong(),
                )
            }

        val topItems = invoices
            .flatMap { invoice ->
                val factor = invoice.tomanFactor()
                invoice.items.map { item -> Triple(item.name.trim(), item.quantity, item.totalPrice * factor) }
            }
            .filter { (name, _, _) -> name.isNotBlank() }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second to it.third })
            .map { (name, lines) ->
                TopItemStat(
                    name = name,
                    quantity = lines.sumOf { it.first },
                    totalAmount = lines.sumOf { it.second }.toLong(),
                )
            }
            .sortedWith(compareByDescending<TopItemStat> { it.totalAmount }.thenBy { it.name })
            .take(TOP_COUNT)

        DashboardAnalytics(
            totalSpend = totalSpend,
            invoiceCount = invoices.size,
            averageInvoice = (spend / invoices.size).toLong(),
            vendorCount = invoices
                .mapNotNull { it.sellerName?.trim().takeIf { name -> !name.isNullOrBlank() } }
                .toSet()
                .size,
            topVendors = topVendors,
            monthlyExpenses = monthlyExpenses,
            topItems = topItems,
            totalUnpaidAmount = invoices
                .filter { it.isUnpaid }
                .sumOf { it.effectiveTomanTotal.toDouble() }
                .toLong(),
            unpaidCount = invoices.count { it.isUnpaid },
            overdueCount = invoices.count { it.paymentStatus == PaymentStatus.OVERDUE },
        )
    }

/**
 * Per-unit scale turning one invoice's line amounts into Toman: `1.0` for local
 * money (up to truncation), the FX ratio for foreign and Rial invoices. A zero
 * or non-finite [Invoice.grandTotal] yields `1.0` — its lines sum to nothing
 * either way, so no scaling decision can change the outcome.
 */
private fun Invoice.tomanFactor(): Double {
    val printed = grandTotal
    if (!printed.isFinite() || printed == 0.0) return 1.0
    return effectiveTomanTotal.toDouble() / printed
}

/**
 * Distinct valid years present in [invoices], newest first — the period
 * filter's chips. Years outside the calendar's plausible span never appear:
 * an obvious date typo must not become a selectable fiscal year.
 *
 * @param isEnglish `true` reads Gregorian years, `false` (the default,
 *   preserving every existing caller) reads Jalali years.
 */
fun availableYears(invoices: List<Invoice>, isEnglish: Boolean = false): List<Int> =
    invoices.mapNotNull { invoice ->
        if (isEnglish) gregorianYearOf(invoice.date) else jalaliYearOf(invoice.date)
    }
        .distinct()
        .sortedDescending()

/**
 * One monthly bucket: its display label plus the numeric year and month the
 * chart sorts by. Chronological order is structural (year, then month), never
 * alphabetical — "Oct 2026" sorts after "Jan 2027" would be the failure mode.
 */
private data class MonthBucket(val label: String, val year: Int, val month: Int)

/**
 * The invoice's monthly bucket in the requested calendar, or `null` when its
 * date belongs to the other calendar or to no calendar at all. A `null` drops
 * the monthly bar only — totals, vendors and items still count the invoice.
 */
private fun monthlyBucket(invoice: Invoice, isEnglish: Boolean): MonthBucket? {
    if (isEnglish) {
        val yearMonth = parseGregorianYearMonth(invoice.date) ?: return null
        return MonthBucket(
            label = "${WESTERN_MONTHS[yearMonth.second - 1]} ${yearMonth.first}",
            year = yearMonth.first,
            month = yearMonth.second,
        )
    }
    val yearMonth = parseJalaliYearMonth(invoice.date) ?: return null
    return MonthBucket(
        label = "${PERSIAN_MONTHS[yearMonth.second - 1]} ${yearMonth.first}",
        year = yearMonth.first,
        month = yearMonth.second,
    )
}

/**
 * The invoice's valid Jalali year, or `null` for a missing, unparseable or
 * out-of-range date. The single funnel behind the monthly buckets, the year
 * chips and the period filter, so all three agree on what counts as a year.
 */
fun jalaliYearOf(date: String?): Int? = parseJalaliYearMonth(date)?.first

/**
 * The invoice's valid Gregorian year, or `null` for a missing, unparseable or
 * out-of-range date. The single funnel behind English buckets, chips and the
 * period filter, so all three agree on what counts as a year.
 */
fun gregorianYearOf(date: String?): Int? = parseGregorianYearMonth(date)?.first

/**
 * Reads a `(year, month)` pair out of a Gregorian date string.
 *
 * Accepts the international `YYYY-MM-DD` shape (and `/`- or `.`-separated
 * twins) with unified digits. Returns `null` for a missing, blank or
 * unparseable date, for a year outside [MIN_GREGORIAN_YEAR]..[MAX_GREGORIAN_YEAR],
 * and for a month outside 1..12.
 */
private fun parseGregorianYearMonth(date: String?): Pair<Int, Int>? {
    val ascii = unifyDigits(date) ?: return null

    val parts = ascii.split(NON_DIGIT_SEPARATOR).filter { it.isNotEmpty() }
    if (parts.size < 3) return null

    // Year-first only: the international contract (`2026-10-14`). Day-first
    // shapes have no producer in this pipeline, so they are not guessed.
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    if (year !in MIN_GREGORIAN_YEAR..MAX_GREGORIAN_YEAR || month !in 1..WESTERN_MONTHS.size) {
        return null
    }

    return year to month
}

/**
 * Reads a `(year, month)` pair out of a Jalali date string.
 *
 * Accepts the extractor's `YYYY/MM/DD` shape with any non-digit separator, after
 * unifying Persian (`۰-۹`) and Arabic-Indic (`٠-٩`) digits to ASCII. Returns `null`
 * for a missing, blank or unparseable date — the caller drops the monthly bucket, not
 * the invoice — and for a year outside [MIN_JALALI_YEAR]..[MAX_JALALI_YEAR], so an
 * obvious date typo (a `1443` far outside any plausible archive) never renders as
 * a bar of its own.
 */
private fun parseJalaliYearMonth(date: String?): Pair<Int, Int>? {
    val ascii = unifyDigits(date) ?: return null

    val parts = ascii.split(NON_DIGIT_SEPARATOR).filter { it.isNotEmpty() }
    if (parts.size < 2) return null

    // Year-first is the extractor's contract (`1403/05/20`); a leading day-first shape
    // has no Jalali producer in this pipeline, so it is not guessed.
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    if (year !in MIN_JALALI_YEAR..MAX_JALALI_YEAR || month !in 1..PERSIAN_MONTHS.size) return null

    return year to month
}

private const val TOP_COUNT = 5

/**
 * Unifies Persian (`۰-۹`) and Arabic-Indic (`٠-٩`) digits to ASCII, or `null`
 * for a missing or blank date. Shared by both calendars so a date typed on a
 * Persian keyboard buckets identically to its Latin-typed twin.
 */
private fun unifyDigits(date: String?): String? {
    if (date.isNullOrBlank()) return null
    return date.map { char ->
        when (char) {
            in PERSIAN_DIGIT_START..PERSIAN_DIGIT_END ->
                ('0'.code + (char.code - PERSIAN_DIGIT_START.code)).toChar()
            in ARABIC_INDIC_DIGIT_START..ARABIC_INDIC_DIGIT_END ->
                ('0'.code + (char.code - ARABIC_INDIC_DIGIT_START.code)).toChar()
            else -> char
        }
    }.joinToString("")
}

/**
 * Plausible Jalali archive span: anything older predates the product's earliest
 * plausible record, anything newer is a date typo (like the `1443` that once
 * rendered a bar of its own). Out-of-range years leave the monthly buckets —
 * totals, vendors and items still count the invoice.
 */
private const val MIN_JALALI_YEAR = 1390
private const val MAX_JALALI_YEAR = 1410

/**
 * Plausible Gregorian archive span, mirroring the Jalali guard above: a year
 * typo like `2143` never renders a bar of its own.
 */
private const val MIN_GREGORIAN_YEAR = 2000
private const val MAX_GREGORIAN_YEAR = 2099

private const val UNKNOWN_VENDOR = "فروشنده نامشخص"

private val PERSIAN_MONTHS = listOf(
    "فروردین",
    "اردیبهشت",
    "خرداد",
    "تیر",
    "مرداد",
    "شهریور",
    "مهر",
    "آبان",
    "آذر",
    "دی",
    "بهمن",
    "اسفند",
)

/**
 * Western month abbreviations for English buckets. Fixed English symbols like
 * the Persian list — not translation keys — so the chart never waits on a
 * dictionary it cannot influence.
 */
private val WESTERN_MONTHS = listOf(
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
)

private const val PERSIAN_DIGIT_START = '۰'
private const val PERSIAN_DIGIT_END = '۹'
private const val ARABIC_INDIC_DIGIT_START = '٠'
private const val ARABIC_INDIC_DIGIT_END = '٩'

private val NON_DIGIT_SEPARATOR = Regex("[^0-9]+")
