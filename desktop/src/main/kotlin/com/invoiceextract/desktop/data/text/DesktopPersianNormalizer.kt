package com.invoiceextract.desktop.data.text

/**
 * Pure, side-effect-free normalization of Persian document text for the desktop
 * pipeline.
 *
 * A digital PDF's text layer and a language model both pass characters through a
 * Unicode pipeline that is rarely consistent about Persian orthography: the same
 * invoice can carry Arabic `ك` (U+0643) next to Persian `ک` (U+06A9), and PDF
 * extractors routinely emit zero-width joiners, byte-order marks and bidirectional
 * marks between glyphs. Left alone, that output defeats every downstream string
 * comparison and feeds the model invisible noise that costs context tokens.
 *
 * This class performs exactly the transformations that are safe for invoice text and
 * refuses the one that is not:
 *
 *  1. **Letters.** Arabic `ك` (U+0643) is rewritten to Persian `ک` (U+06A9); Arabic
 *     `ي` (U+064A) *and* `ى` (U+0649, alef maksura) are rewritten to Persian `ی`
 *     (U+06CC). These are the only code points that differ across the orthographies
 *     while carrying the same meaning, so the rewrite is lossless.
 *  2. **Zero-width artifacts.** ZWSP (U+200B), ZWNJ (U+200C), ZWJ (U+200D), the
 *     left/right bidirectional marks (U+200E/U+200F), the word joiner (U+2060) and the
 *     BOM / ZWNBSP (U+FEFF) are stripped. None of them carries document content — they
 *     are extraction noise — and being invisible they can never be anything but a bug
 *     downstream.
 *  3. **Whitespace.** Carriage returns fold to `\n` so a PDF saved on Windows does
 *     not pollute every line end with `\r`, runs of spaces and tabs collapse to a
 *     single ASCII space, and each line is trimmed. Newlines themselves are
 *     **strictly preserved**: the line layout of an invoice is what separates one
 *     line item from the next, so flattening it would destroy recoverable structure.
 *  4. **Reversed visual order.** Several Iranian accounting PDF generators lay words
 *     out in logical order but write each Persian word's glyphs backwards, so a line
 *     that should read `شماره فاکتور` reaches the pipeline as `هرامش روتکاف` — the
 *     words in the right place, every word mirrored in place. When a known mirrored
 *     word is present, each line's Persian/Arabic letter runs are read backwards while
 *     numbers and Latin abbreviations are copied forwards, so the model receives
 *     Persian rather than its mirror image. The repair fires **only on detection**: a
 *     healthy document never carries a mirrored word, and unmirroring unconditionally
 *     would corrupt correct text.
 *
 * **Numerals are never converted.** Persian-Indic digits `۰۱۲۳۴۵۶۷۸۹` and Latin
 * digits `0-9` pass through untouched. An invoice routinely mixes `۱٬۲۵۰٬۰۰۰` with
 * `4800` in the same field, and converting one to the other would silently alter
 * amounts — the exact failure a financial pipeline must never introduce.
 *
 * The class holds no state and touches no platform types, so it is safe to call from
 * any thread and any dispatcher.
 */
class DesktopPersianNormalizer {

    /**
     * Normalizes [input].
     *
     * @param input Raw text as produced by the PDF text layer (or, later, an OCR
     *   engine). May be empty.
     * @return The normalized text. Never `null`; empty when [input] is empty.
     */
    fun normalize(input: String): String {
        // Short-circuit before touching a single regex: a page that yielded no text is
        // the common case for a blank trailing page and must stay allocation-free.
        if (input.isEmpty()) return input

        // Letters, then zero-width characters, then whitespace: stripping the invisible
        // characters before normalizing whitespace is what stops a page full of stray
        // marks from leaving meaningless runs of blank lines behind.
        val withoutZeroWidth = stripZeroWidthArtifacts(normalizeLetters(input))

        // The bidi repair runs last, on already-clean lines: the detection set is spelled
        // with Persian ک/ی, so letter unification must precede it, and a stray ZWNJ
        // inside a mirrored word would otherwise split it and hide it from detection.
        val cleaned = normalizeWhitespace(withoutZeroWidth)
        return repairReversedVisualOrder(cleaned)
    }

    /**
     * Rewrites the Arabic letters that carry identical meaning under a different code
     * point in Persian: kaf, and both forms of yeh.
     */
    private fun normalizeLetters(input: String): String {
        return input
            .replace(ARABIC_KAF, PERSIAN_KAF)
            .replace(ARABIC_YEH, PERSIAN_YEH)
            .replace(ARABIC_ALEF_MAKSURA, PERSIAN_YEH)
    }

    /**
     * Removes the invisible joiners, marks and byte-order marks that PDF extraction
     * emits between glyphs. Every one of these is zero-width, so none can represent
     * real content read off the page.
     */
    private fun stripZeroWidthArtifacts(input: String): String {
        return input.replace(ZERO_WIDTH_ARTIFACTS, EMPTY_STRING)
    }

    /**
     * Unifies line endings to `\n`, collapses every kind of horizontal space to a
     * single ASCII space and trims each line. Newlines are deliberately *not*
     * collapsed — unlike the mobile normalizer, the desktop pipeline feeds a local
     * LLM where the page's line structure is the strongest layout signal available,
     * so it is preserved verbatim.
     */
    private fun normalizeWhitespace(input: String): String {
        return input
            .replace(CARRIAGE_RETURN_LINE_FEED, NEW_LINE)
            .replace(CARRIAGE_RETURN, NEW_LINE)
            .lineSequence()
            .map { line -> line.replace(HORIZONTAL_WHITESPACE, SPACE).trim() }
            .joinToString(NEW_LINE)
            .trim()
    }

    /**
     * Un-mirrors the Persian words of a document whose PDF generator wrote every RTL
     * word's glyphs in reverse visual order.
     *
     * Word order survives this defect — the generators lay words out in logical order
     * and only write each word's characters backwards — which is why the repair works
     * per token rather than over the whole line: mirroring the full line would also
     * reverse the word order and scramble the invoice's layout. Detection is the gate —
     * see [REVERSED_TOKENS] — because this transformation applied to already-correct
     * text is pure corruption.
     *
     * Inside one token only the Persian/Arabic letter runs are read backwards; the runs
     * between them (numbers, Latin abbreviations, punctuation) are copied forwards. A
     * token routinely fuses scripts — a mirrored word touching `lt` or a Persian digit
     * group — and mirroring the unit along with the word would turn `lt` into `tl` and
     * `۱۰۲۳` into `۳۲۰۱`.
     *
     * Numbers are preserved by design rather than un-mirrored: the generators that
     * produce this defect write digits in normal reading order, and the model reads
     * amounts off the page, so a digit that moved is an amount that changed.
     */
    private fun repairReversedVisualOrder(input: String): String {
        if (!containsReversedTokens(input)) return input

        return input.lineSequence()
            .map { line ->
                line.split(HORIZONTAL_WHITESPACE)
                    .joinToString(SPACE) { token -> unReverseToken(token) }
            }
            .joinToString(NEW_LINE)
    }

    /**
     * True when [input] carries a mirrored word from the vocabulary set — `روتکاف`,
     * `هرامش`, `خرینات`, `تمسق`, `عمج`, … — which cannot appear inside healthy Persian.
     * Containment rather than exact-token matching is what keeps a marker glued to a
     * neighboring unit (`روتکافlt`, `روتکاف۱۰۲۳`) arming the repair too: those fused
     * forms are exactly how the PDF text layer emits this defect.
     */
    private fun containsReversedTokens(input: String): Boolean =
        REVERSED_TOKENS.any { it in input }

    /**
     * Un-mirrors one whitespace-delimited token by reading each maximal Persian/Arabic
     * letter run backwards and copying everything between them forwards.
     */
    private fun unReverseToken(token: String): String {
        if (token.isEmpty()) return token

        val output = StringBuilder(token.length)
        var runStart = 0
        var runIsPersian = token.first().isPersianOrArabicLetter()

        for (index in token.indices) {
            if (token[index].isPersianOrArabicLetter() != runIsPersian) {
                appendRun(output, token, runStart, index, reversed = runIsPersian)
                runStart = index
                runIsPersian = !runIsPersian
            }
        }
        appendRun(output, token, runStart, token.length, reversed = runIsPersian)

        return output.toString()
    }

    /**
     * Copies `source[start until end)` into [output]: forwards when the run holds
     * numbers or Latin text, and un-mirrored when it holds Persian letters.
     */
    private fun appendRun(
        output: StringBuilder,
        source: String,
        start: Int,
        end: Int,
        reversed: Boolean,
    ) {
        if (reversed) {
            output.append(naturalForm(source.substring(start, end)))
        } else {
            output.append(source, start, end)
        }
    }

    /**
     * The natural reading of a mirrored token.
     *
     * Most markers are the strict character mirror of the word they stand for, so the
     * natural form is the mirrored spelling reversed. The exceptions are the tokens the
     * live-test corpus produced whose mirror is *another real word* or a misspelling —
     * `تمسق` mirrors to `قسمت` (portion) rather than `قیمت` (price), and a financial
     * document that silently reads "portion" where the page says "price" is a worse
     * defect than the one this class exists to repair. Those are spelled out in
     * [REVERSED_TOKEN_FIXES].
     */
    private fun naturalForm(mirrored: String): String =
        REVERSED_TOKEN_FIXES[mirrored] ?: mirrored.reversed()

    /**
     * True for the letters of Persian and Arabic: the main Arabic block, its supplement
     * and both presentation-forms blocks. Persian numerals share the main block with the
     * letters (U+06F0-U+06F9), so they are excluded here — a digit is never part of the
     * mirrored script run and must survive the repair untouched.
     */
    private fun Char.isPersianOrArabicLetter(): Boolean = when (this.code) {
        in ARABIC_BLOCK_START..ARABIC_BLOCK_END -> !isArabicDigit()
        in ARABIC_SUPPLEMENT_START..ARABIC_SUPPLEMENT_END -> true
        in ARABIC_PRESENTATION_A_START..ARABIC_PRESENTATION_A_END -> true
        in ARABIC_PRESENTATION_B_START..ARABIC_PRESENTATION_B_END -> true
        else -> false
    }

    /** Arabic-Indic and Extended Arabic-Indic (Persian) digits, both of which mirror. */
    private fun Char.isArabicDigit(): Boolean = when (this.code) {
        in ARABIC_INDIC_DIGIT_START..ARABIC_INDIC_DIGIT_END,
        in EXTENDED_ARABIC_INDIC_DIGIT_START..EXTENDED_ARABIC_INDIC_DIGIT_END -> true
        else -> false
    }

    private companion object {
        // --- Arabic letters, the ones that differ across the orthographies --------
        private const val ARABIC_KAF = 'ك' // U+0643 ARABIC LETTER KAF
        private const val PERSIAN_KAF = 'ک' // U+06A9 ARABIC LETTER KEHEH (Persian kaf)

        private const val ARABIC_YEH = 'ي' // U+064A ARABIC LETTER YEH
        private const val ARABIC_ALEF_MAKSURA = 'ى' // U+0649 ARABIC LETTER ALEF MAKSURA
        private const val PERSIAN_YEH = 'ی' // U+06CC ARABIC LETTER FARSI YEH

        // --- Line endings -------------------------------------------------------
        // String, not Char: the String.replace overloads only accept (Char, Char) or
        // (String, String), so mixing a String "\r\n" with a Char '\n' does not compile.
        private const val CARRIAGE_RETURN = "\r"
        private const val NEW_LINE = "\n"
        private const val SPACE = " "
        private const val EMPTY_STRING = ""
        private const val CARRIAGE_RETURN_LINE_FEED = "\r\n"

        /**
         * Zero-width artifacts that never carry document content: ZWSP, ZWNJ and ZWJ
         * (U+200B-U+200D), the left and right bidirectional marks (U+200E-U+200F), the
         * word joiner (U+2060) and the byte-order mark / ZWNBSP (U+FEFF).
         *
         * Compiled once and reused — [normalize] runs once per page and this set never
         * changes. The set mirrors the shipped mobile normalizer so both fronts agree
         * on what counts as extraction noise.
         */
        private val ZERO_WIDTH_ARTIFACTS = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")

        /**
         * Every horizontal space character: tab, form feed, NBSP and the whole Unicode
         * `Zs` category. `\n` is intentionally excluded so line breaks survive.
         */
        private val HORIZONTAL_WHITESPACE = Regex("[\\t\\f\\p{Zs}]+")

        // --- Reversed visual order (bidi) ----------------------------------------
        // Mirrored spellings of the vocabulary every Iranian invoice carries. A healthy
        // document cannot contain any of these sequences, so one hit is enough to arm the
        // repair over the whole document: these generators mirror every word or none.
        // Spelled with Persian ک/ی, matching the output of [normalizeLetters].
        private val REVERSED_TOKENS = setOf(
            "روتکاف",
            "خرینات",
            "تمسق",
            "هرامش",
            "عمج",
            "تلاوت",
            "هدننورف",
        )

        // Tokens from the live-test corpus whose mirrored form is not the word the page
        // means, so reversing them by hand would trade one corruption for another. The
        // key is the spelling as observed in the PDF text layer; the value is the word
        // the invoice actually carries.
        private val REVERSED_TOKEN_FIXES = mapOf(
            // Mirror would be قسمت (portion); the field is قیمت (price).
            "تمسق" to "قیمت",
            // Carries an extra ن the generator emits; the field is تاریخ (date).
            "خرینات" to "تاریخ",
        )

        // Unicode blocks holding Persian and Arabic letters.
        private const val ARABIC_BLOCK_START = 0x0600
        private const val ARABIC_BLOCK_END = 0x06FF
        private const val ARABIC_SUPPLEMENT_START = 0x0750
        private const val ARABIC_SUPPLEMENT_END = 0x077F
        private const val ARABIC_PRESENTATION_A_START = 0xFB50
        private const val ARABIC_PRESENTATION_A_END = 0xFDFF
        private const val ARABIC_PRESENTATION_B_START = 0xFE70
        private const val ARABIC_PRESENTATION_B_END = 0xFEFF

        // Persian/Arabic numerals that live inside the Arabic block and must not be
        // treated as letters by the run scanner.
        private const val ARABIC_INDIC_DIGIT_START = 0x0660
        private const val ARABIC_INDIC_DIGIT_END = 0x0669
        private const val EXTENDED_ARABIC_INDIC_DIGIT_START = 0x06F0
        private const val EXTENDED_ARABIC_INDIC_DIGIT_END = 0x06F9
    }
}
