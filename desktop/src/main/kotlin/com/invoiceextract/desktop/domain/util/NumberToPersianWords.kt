package com.invoiceextract.desktop.domain.util

/**
 * Pure Kotlin converter from integral Toman amounts to natural Persian words.
 *
 * Covers zero through the trillions (`تریلیون`); larger magnitudes keep
 * grouping with extended scale names instead of failing. Negative inputs
 * read with a `منفی` prefix, and the result always carries the `تومان` unit —
 * so `"یک میلیون و چهل و چهار هزار تومان"` is one call away for `1044000L`.
 *
 * Pure and stateless: safe to call from any thread and any dispatcher.
 */
fun convertNumberToWords(amount: Long): String {
    if (amount == 0L) return "صفر $TOMAN_UNIT"

    // Two's-complement absolute: negation alone would overflow for MIN_VALUE
    // and recurse forever, while money must never crash the sheet.
    val negative = amount < 0L
    var remainder: ULong = if (negative) amount.toULong().inv() + 1UL else amount.toULong()

    val parts = mutableListOf<String>()
    var scale = 0
    while (remainder > 0UL) {
        val group = (remainder % 1000UL).toInt()
        if (group != 0) {
            val words = threeDigitWords(group)
            val scaleName = SCALE_NAMES.getOrNull(scale).orEmpty()
            parts.add(if (scaleName.isEmpty()) words else "$words $scaleName")
        }
        remainder /= 1000UL
        scale++
    }

    val prefix = if (negative) "منفی " else ""
    return prefix + parts.asReversed().joinToString(" $AND ") + " $TOMAN_UNIT"
}

/** Words for `1..999`; never called with zero (empty groups are skipped). */
private fun threeDigitWords(number: Int): String {
    val chunks = mutableListOf<String>()

    val hundreds = number / 100
    if (hundreds > 0) chunks.add(HUNDREDS[hundreds])

    val rest = number % 100
    if (rest in 10..19) {
        chunks.add(TEENS[rest - 10])
    } else {
        val tens = rest / 10
        if (tens >= 2) chunks.add(TENS[tens])
        val ones = rest % 10
        if (ones > 0) chunks.add(ONES[ones])
    }

    return chunks.joinToString(" $AND ")
}

private const val AND = "و"
private const val TOMAN_UNIT = "تومان"

private val ONES = listOf(
    "", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه",
)

private val TEENS = listOf(
    "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده",
)

private val TENS = listOf(
    "", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود",
)

private val HUNDREDS = listOf(
    "", "یکصد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد",
)

private val SCALE_NAMES = listOf(
    "", "هزار", "میلیون", "میلیارد", "تریلیون", "کوادریلیون",
)
