package com.invoiceextract.app.data.ocr

/**
 * Pure, side-effect-free normalization of Persian OCR text.
 *
 * ML Kit has no dedicated Persian model, so the Arabic-script recognizer is used.
 * That model is perfectly capable of reading an Iranian invoice, but it reports the
 * shared Arabic/Persian letters with their *Arabic* code points and liberally sprays
 * invisible joiners between words. Left alone, that output breaks every downstream
 * string comparison: `Query("نقدی")` never matches `نقدي`, and a stray ZWNJ defeats an
 * exact lookup even when the visible glyphs are identical.
 *
 * This class therefore does the three transformations that are safe for invoice text
 * and — just as importantly — refuses to do the one that is not:
 *
 *  1. **Letters.** Arabic `ك` (U+0643) and `ي` (U+064A) are rewritten to their Persian
 *     counterparts `ک` (U+06A9) and `ی` (U+06CC). These are the only two letters whose
 *     code points differ between the two orthographies while carrying the *same*
 *     meaning, so the rewrite is lossless.
 *  2. **Zero-width characters.** ZWNJ (U+200C), ZWJ (U+200D), ZWSP (U+200B), the word
 *     joiner (U+2060) and a BOM (U+FEFF) are stripped. None of them carries OCR
 *     signal — they are recognizer noise between glyphs — and being invisible they
 *     can never be anything but a bug in later matching.
 *  3. **Whitespace.** Carriage returns are folded to `\n`, exotic space characters
 *     (NBSP, the various Unicode spaces, tabs) collapse to a plain ASCII space, lines
 *     are trimmed and runs of blank lines are reduced to a single paragraph break.
 *     Newlines themselves are *preserved*: the line layout of an invoice is what
 *     separates one line item from the next, so flattening it to spaces would destroy
 *     recoverable structure.
 *
 * **Numerals are never converted.** Persian-Indic digits `۰۱۲۳۴۵۶۷۸۹`, Eastern
 * Arabic-Indic digits and Latin digits `0-9` all pass through untouched. An invoice
 * routinely mixes `تومان ۱٬۲۵۰٬۰۰۰` with `Total: 4800` in the same field, and
 * converting one to the other would silently alter amounts — the exact failure the
 * "no data loss" rule exists to prevent. Downstage layers that *want* a single numeral
 * system must do that conversion explicitly and deliberately.
 *
 * The class holds no state and touches no Android types, so it is safe to call from
 * any thread and any dispatcher.
 */
class PersianTextNormalizer {

    /**
     * Normalizes [input].
     *
     * @param input Raw text as produced by the OCR engine. May be empty.
     * @return The normalized text. Never `null`; empty when [input] is empty.
     */
    fun normalize(input: String): String {
        // Short-circuit before compiling a single regex: a page that yielded no text
        // is the common case for a blank back page and needs to stay allocation-free.
        if (input.isEmpty()) return input

        // Letters first, then zero-width characters, then whitespace: stripping the
        // invisible characters before normalizing whitespace is what stops a page-full
        // of stray ZWNJs from collapsing into meaningless runs of blank lines.
        val withoutZeroWidth = stripZeroWidthCharacters(normalizeLetters(input))
        return normalizeWhitespace(withoutZeroWidth)
    }

    /**
     * Rewrites the two Arabic letters that carry identical meaning under a different
     * code point in Persian: kaf and yeh.
     */
    private fun normalizeLetters(input: String): String {
        return input
            .replace(ARABIC_KAF, PERSIAN_KAF)
            .replace(ARABIC_YEH, PERSIAN_YEH)
    }

    /**
     * Removes the invisible joiners, separators and marks that the recognizer emits
     * between glyphs. Every one of these is zero-width, so none can represent real
     * content read off the page.
     */
    private fun stripZeroWidthCharacters(input: String): String {
        return input.replace(ZERO_WIDTH_CHARACTERS, "")
    }

    /**
     * Folds line endings to `\n`, collapses every kind of horizontal space to a single
     * ASCII space, trims each line and caps blank-line runs at one. Newlines are
     * deliberately *not* collapsed — the line structure is what makes an invoice
     * parseable downstream.
     */
    private fun normalizeWhitespace(input: String): String {
        return input
            .replace(CARRIAGE_RETURN_LINE_FEED, NEW_LINE)
            .replace(CARRIAGE_RETURN, NEW_LINE)
            .lineSequence()
            .map { line -> line.replace(HORIZONTAL_WHITESPACE, SPACE).trim() }
            .joinToString(NEW_LINE)
            .replace(EXCESS_BLANK_LINES, PARAGRAPH_BREAK)
            .trim()
    }

    private companion object {
        // --- Arabic letters, the two that differ across the orthographies ---------
        private const val ARABIC_KAF = 'ك' // U+0643 ARABIC LETTER KAF
        private const val PERSIAN_KAF = 'ک' // U+06A9 ARABIC LETTER KEHEH (Persian kaf)

        private const val ARABIC_YEH = 'ي' // U+064A ARABIC LETTER YEH
        private const val PERSIAN_YEH = 'ی' // U+06CC ARABIC LETTER FARSI YEH

        // --- Line endings -------------------------------------------------------
        // String, not Char: the String.replace overloads only accept (Char, Char) or
        // (String, String), so mixing a String "\r\n" with a Char '\n' does not compile.
        private const val CARRIAGE_RETURN = "\r"
        private const val NEW_LINE = "\n"
        private const val SPACE = " "
        private const val CARRIAGE_RETURN_LINE_FEED = "\r\n"
        private const val PARAGRAPH_BREAK = "\n\n"

        /**
         * Zero-width characters that never carry OCR content: ZWSP, ZWNJ, ZWJ
         * (U+200B-U+200D), the word joiner (U+2060) and the byte-order mark / ZWNBSP
         * (U+FEFF). Compiled once and reused — [normalize] is called once per page and
         * these patterns never change.
         */
        private val ZERO_WIDTH_CHARACTERS = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")

        /**
         * Every horizontal space character: ASCII space, tab, form feed, NBSP and the
         * whole Unicode `Zs` category. `\n` is intentionally excluded so line breaks
         * survive the rewrite.
         */
        private val HORIZONTAL_WHITESPACE = Regex("[\\t\\f\\p{Zs}]+")

        /** Three or more consecutive newlines become exactly one blank line. */
        private val EXCESS_BLANK_LINES = Regex("\n{3,}")
    }
}
