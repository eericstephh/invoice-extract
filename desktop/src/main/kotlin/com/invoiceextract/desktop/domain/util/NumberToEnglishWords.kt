package com.invoiceextract.desktop.domain.util

import kotlin.math.roundToLong

/**
 * Pure Kotlin converter from a decimal money amount to natural English words.
 *
 * Spells dollars and cents separately — `"One Thousand Two Hundred Fifty US
 * Dollars and Seventy-Five Cents"` for `1250.75` — covering zero through the
 * billions (larger magnitudes keep grouping with extended scale names instead
 * of failing). Tens hyphenate (`Twenty-One`), hundreds join with spaces, and
 * the result always carries both units, so `"Zero US Dollars and Zero Cents"`
 * is one call away for `0.0`.
 *
 * The currency names follow [currencyCode]: USD/EUR/GBP resolve to their
 * proper units (including Penny/Pence); anything else reads as the code
 * itself, so an unknown code degrades to a transparent label rather than a
 * wrong currency.
 *
 * Pure and stateless: safe to call from any thread and any dispatcher.
 */
fun convertNumberToEnglishWords(amount: Double, currencyCode: String = "USD"): String {
    // Non-finite money degrades to zero rather than leaking NaN into a sentence.
    val safe = if (amount.isFinite()) amount else 0.0
    // Work in absolute cents as a Long: doubles cannot center halves exactly,
    // and money must never read a cent it does not own.
    val totalCents = (kotlin.math.abs(safe) * 100.0).roundToLong()
    val dollars = totalCents / 100L
    val cents = (totalCents % 100L).toInt()
    val (dollarUnit, centUnit) = unitsFor(dollars, cents, currencyCode)

    val prefix = if (safe < 0.0) "Minus " else ""
    return prefix + spellDollars(dollars) + " " + dollarUnit +
        " and " + spellCents(cents) + " " + centUnit
}

/**
 * Unit names for the amount's own currency. A blank code falls back to USD —
 * the global workspace default — so the sentence always names a currency
 * instead of trailing off.
 */
private fun unitsFor(dollars: Long, cents: Int, currencyCode: String): Pair<String, String> {
    val code = currencyCode.trim().uppercase().ifBlank { "USD" }
    val dollarUnit = when (code) {
        "USD", "$", "DOLLAR", "DOLLARS" -> if (dollars == 1L) "US Dollar" else "US Dollars"
        "EUR" -> if (dollars == 1L) "Euro" else "Euros"
        "GBP" -> if (dollars == 1L) "Pound" else "Pounds"
        else -> if (dollars == 1L) code else code + "s"
    }
    val centUnit = when (code) {
        "GBP" -> if (cents == 1) "Penny" else "Pence"
        else -> if (cents == 1) "Cent" else "Cents"
    }
    return dollarUnit to centUnit
}

/** Words for a whole-dollar count, zero included. */
private fun spellDollars(dollars: Long): String {
    if (dollars == 0L) return "Zero"

    val parts = mutableListOf<String>()
    var remainder = dollars
    var scale = 0
    while (remainder > 0L) {
        val group = (remainder % 1000L).toInt()
        if (group != 0) {
            val words = threeDigitWords(group)
            val scaleName = SCALE_NAMES.getOrNull(scale).orEmpty()
            parts.add(if (scaleName.isEmpty()) words else "$words $scaleName")
        }
        remainder /= 1000L
        scale++
    }
    return parts.asReversed().joinToString(" ")
}

/** Words for a cent count: `0` reads `Zero`, the unit rides separately. */
private fun spellCents(cents: Int): String =
    if (cents == 0) "Zero" else threeDigitWords(cents)

/** Words for `1..999`; never called with zero (empty groups are skipped). */
private fun threeDigitWords(number: Int): String {
    val chunks = mutableListOf<String>()

    val hundreds = number / 100
    if (hundreds > 0) chunks.add("${ONES[hundreds]} Hundred")

    val rest = number % 100
    if (rest in 10..19) {
        chunks.add(TEENS[rest - 10])
    } else {
        val tens = rest / 10
        val ones = rest % 10
        if (tens >= 2 && ones > 0) {
            chunks.add("${TENS[tens]}-${ONES[ones]}")
        } else {
            if (tens >= 2) chunks.add(TENS[tens])
            if (ones > 0) chunks.add(ONES[ones])
        }
    }

    return chunks.joinToString(" ")
}

private val ONES = listOf(
    "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
)

private val TEENS = listOf(
    "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
    "Seventeen", "Eighteen", "Nineteen",
)

private val TENS = listOf(
    "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
)

private val SCALE_NAMES = listOf(
    "", "Thousand", "Million", "Billion", "Trillion", "Quadrillion",
)
