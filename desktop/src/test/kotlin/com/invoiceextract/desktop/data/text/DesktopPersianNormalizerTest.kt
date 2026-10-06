package com.invoiceextract.desktop.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [DesktopPersianNormalizer].
 *
 * Every rule is a pure character transformation, so each case is one input string and
 * one expected output. The two contracts that matter most to a financial pipeline get
 * their own cases: numerals must never move, and line structure must survive.
 */
class DesktopPersianNormalizerTest {

    private val normalizer = DesktopPersianNormalizer()

    @Test
    fun `empty input is returned untouched`() {
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun `Arabic kaf becomes Persian kaf`() {
        assertEquals("کالا", normalizer.normalize("كالا"))
    }

    @Test
    fun `Arabic yeh becomes Persian yeh`() {
        assertEquals("نقدی", normalizer.normalize("نقدي"))
    }

    @Test
    fun `alef maksura becomes Persian yeh`() {
        // U+0649 is the third yeh variant the PDF text layer can emit.
        assertEquals("نقدی", normalizer.normalize("نقدى"))
    }

    @Test
    fun `all three letter variants are normalized in one pass`() {
        assertEquals("کیک یخی", normalizer.normalize("كيك يخي"))
    }

    @Test
    fun `zero-width artifacts are stripped`() {
        // ZWSP + ZWNJ + ZWJ + LRM + RLM + word joiner + BOM, scattered through both
        // words. The real space between the words is what keeps them apart; the
        // zero-widths are removed outright, never rewritten as spaces.
        val polluted = "ف\u200Bا\u200Cک\u200Dت\u200Eو\u200Fر \u2060ت\uFEFFست"

        assertEquals("فاکتور تست", normalizer.normalize(polluted))
    }

    @Test
    fun `zero-width artifacts do not leave empty lines behind`() {
        // A page full of stray marks must not collapse into a wall of blank lines.
        val polluted = "خط اول\n\u200C\u200D\nخط دوم"

        assertEquals("خط اول\n\nخط دوم", normalizer.normalize(polluted))
    }

    @Test
    fun `Persian digits are preserved untouched`() {
        val digits = "۰۱۲۳۴۵۶۷۸۹"

        assertEquals(digits, normalizer.normalize(digits))
    }

    @Test
    fun `Latin digits are preserved untouched`() {
        val digits = "0123456789"

        assertEquals(digits, normalizer.normalize(digits))
    }

    @Test
    fun `mixed digit systems in one amount are preserved`() {
        // An invoice routinely mixes both systems; converting either would alter amounts.
        assertEquals("Total: 4800 تومان ۱٬۲۵۰٬۰۰۰", normalizer.normalize("Total: 4800 تومان ۱٬۲۵۰٬۰۰۰"))
    }

    @Test
    fun `runs of spaces and tabs collapse to a single space`() {
        assertEquals("فاکتور ۱۰۲۳", normalizer.normalize("فاکتور      \t\t۱۰۲۳"))
    }

    @Test
    fun `exotic horizontal spaces collapse to an ASCII space`() {
        // NBSP (U+00A0) and figure space (U+2007) are both invisible width.
        assertEquals("مبلغ کل", normalizer.normalize("مبلغ\u00A0\u2007کل"))
    }

    @Test
    fun `lines are trimmed of leading and trailing whitespace`() {
        assertEquals("وسط", normalizer.normalize("   وسط   "))
    }

    @Test
    fun `newlines are strictly preserved`() {
        // Three blank lines stay three blank lines: the line layout is what separates
        // invoice rows, so the normalizer must never flatten it.
        val input = "ردیف اول\n\n\nردیف دوم"

        assertEquals(input, normalizer.normalize(input))
    }

    @Test
    fun `carriage returns fold to newline and nothing is lost`() {
        assertEquals("خط اول\nخط دوم", normalizer.normalize("خط اول\r\nخط دوم"))
    }

    @Test
    fun `carriage return alone also folds`() {
        assertEquals("خط اول\nخط دوم", normalizer.normalize("خط اول\rخط دوم"))
    }

    @Test
    fun `realistic mixed OCR line is fully repaired`() {
        // Arabic yeh, a real space plus a stray ZWNJ between the words, a tab run and a
        // trailing BOM in one line. The ZWNJ is dropped — it is not a word separator.
        val raw = "نقدي \u200Cكالا\t\t۱۰۲۳\uFEFF"
        val expected = "نقدی کالا ۱۰۲۳"

        assertEquals(expected, normalizer.normalize(raw))
    }

    @Test
    fun `digits survive whitespace collapsing adjacent to them`() {
        assertEquals("1 2 3", normalizer.normalize("1\t\t2   3"))
    }

    @Test
    fun `persian text round-trips unchanged when already clean`() {
        val clean = "فاکتور فروش\nشرکت نمونه\n۱۰۲۳۴۵۶۷۸۹۰"

        assertEquals(clean, normalizer.normalize(clean))
    }

    @Test
    fun `reversed invoice words are un-mirrored`() {
        // "شماره فاکتور" with each word's glyphs written backwards.
        assertEquals("شماره فاکتور", normalizer.normalize("هرامش روتکاف"))
    }

    @Test
    fun `reversed invoice vocabulary is un-mirrored`() {
        // One case per marker, using the field names an Iranian invoice actually carries.
        assertEquals("فاکتور", normalizer.normalize("روتکاف"))
        assertEquals("تاریخ", normalizer.normalize("خرینات"))
        assertEquals("قیمت", normalizer.normalize("تمسق"))
        assertEquals("شماره", normalizer.normalize("هرامش"))
        assertEquals("جمع", normalizer.normalize("عمج"))
        assertEquals("فروننده", normalizer.normalize("هدننورف"))
    }

    @Test
    fun `a token whose mirror is a different real word takes the table's spelling`() {
        // "تمسق" reversed character by character is "قسمت" (portion), not "قیمت" (price).
        // On a financial document the wrong one is a silent, expensive misread.
        assertEquals("قیمت کل", normalizer.normalize("تمسق لک"))
    }

    @Test
    fun `reversed line keeps latin units in reading order`() {
        // "lt" must survive as "lt"; mirroring it into "tl" would corrupt the unit.
        assertEquals("فاکتور ۱۰۲۳ lt", normalizer.normalize("روتکاف ۱۰۲۳ lt"))
    }

    @Test
    fun `reversed word glued to a latin unit does not mirror the unit`() {
        // One token, two scripts: only the Persian run is read backwards.
        assertEquals("فاکتورlt", normalizer.normalize("روتکافlt"))
    }

    @Test
    fun `reversed word glued to digits keeps the digits in order`() {
        // Persian numerals share the Arabic block with the letters, so the run scanner
        // must still treat them as numbers and leave them alone.
        assertEquals("فاکتور۱۲۳۴", normalizer.normalize("روتکاف۱۲۳۴"))
    }

    @Test
    fun `each line of a reversed document is repaired independently`() {
        // "شماره فاکتور" / "قیمت جمع" — line structure is preserved exactly as it is for
        // clean text, since the layout is what separates one field from the next.
        assertEquals("شماره فاکتور\nقیمت جمع", normalizer.normalize("هرامش روتکاف\nتمسق عمج"))
    }

    @Test
    fun `already correct persian text is untouched when no marker is present`() {
        // The repair must stay dormant on a healthy document — mirroring correct text
        // would be pure corruption.
        val clean = "فاکتور شماره ۱۰۲۳"

        assertEquals(clean, normalizer.normalize(clean))
    }

    @Test
    fun `a word sharing characters with a marker but spelled correctly is left alone`() {
        // No marker appears in this line, so nothing is mirrored even though the words
        // are built from the same letters as the mirrored vocabulary.
        assertEquals("جمع کل", normalizer.normalize("جمع کل"))
    }

    @Test
    fun `arabic kaf inside a reversed word still matches after letter unification`() {
        // The detection set is spelled with Persian ک, so the Arabic ك in the raw text
        // must be unified before detection runs.
        assertEquals("شماره فاکتور", normalizer.normalize("هرامش روتكاف"))
    }

    @Test
    fun `a zwnj splitting a reversed word does not hide it from detection`() {
        // Zero-width stripping happens before the repair, so a stray ZWNJ inside the
        // mirrored word cannot defeat the marker lookup.
        assertEquals("شماره فاکتور", normalizer.normalize("هرا\u200Cمش روتکاف"))
    }
}
