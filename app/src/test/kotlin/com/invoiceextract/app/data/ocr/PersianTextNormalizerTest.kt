package com.invoiceextract.app.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Hermetic unit tests for [PersianTextNormalizer] (Phase 15.1).
 *
 * The normalizer is pure and touches no Android type, so these run on a plain JVM with no
 * emulator and no Robolectric. Every test is one [PersianTextNormalizer.normalize] call
 * compared against an expected string.
 *
 * **Why the Arabic inputs are written with `\u` escapes.** The whole point of rule 1 is a
 * *code point* rewrite: Arabic `ك` (U+0643) must become Persian `ک` (U+06A9). Many editors
 * silently substitute one for the other on save, which would turn the assertion into a
 * vacuous pass. Escaping the input makes the code point under test unambiguous and keeps
 * the test honest. Expected outputs are written as readable Persian literals.
 */
class PersianTextNormalizerTest {

    private val normalizer = PersianTextNormalizer()

    @Test
    fun normalizesArabicKafAndYeh() {
        // Arabic kaf ك (U+0643) → Persian keheh ک (U+06A9): "كتاب" → "کتاب"
        assertEquals("کتاب", normalizer.normalize("\u0643\u062A\u0627\u0628"))
        // Arabic yeh ي (U+064A) → Persian farsi yeh ی (U+06CC): "على" → "علی"
        assertEquals("علی", normalizer.normalize("\u0639\u0644\u064A"))
        // A word carrying both letters at once, still lossless.
        assertEquals("کتابی", normalizer.normalize("\u0643\u062A\u0627\u0628\u064A"))
        // Already-Persian text is an identity: the rewrite never corrupts a correct input.
        assertEquals("کتاب", normalizer.normalize("کتاب"))
    }

    @Test
    fun stripsZeroWidthArtifacts() {
        // ZWNJ (U+200C), ZWJ (U+200D) and ZWSP (U+200B) between glyphs are recognizer
        // noise. Removing them must join the neighbours directly, never insert a space.
        assertEquals("کتابها", normalizer.normalize("کتاب\u200Cها"))
        assertEquals("فاکتور", normalizer.normalize("فاکتور\u200B\u200C\u200D"))
        // The word joiner (U+2060) and the BOM / ZWNBSP (U+FEFF) go too.
        assertEquals("پرداخت", normalizer.normalize("\u2060پرداخت\uFEFF"))

        // A real word boundary is preserved: the ASCII space between two words survives,
        // so the output stays two words and does not collapse into one.
        assertEquals("فاکتور پرداخت", normalizer.normalize("فاکتور\u200C \u200Dپرداخت"))

        // Line structure is deliberately preserved — the line layout is what separates one
        // invoice row from the next, so CRLF only folds to LF and never to a space.
        assertEquals("خط اول\nخط دوم", normalizer.normalize("خط اول\r\nخط دوم"))
    }

    @Test
    fun preservesPersianAndLatinDigits() {
        // Persian-Indic digits ۱۲۳ (U+06F1..U+06F3) pass through untouched.
        assertEquals("۱۲۳", normalizer.normalize("\u06F1\u06F2\u06F3"))
        // Latin digits pass through untouched.
        assertEquals("123", normalizer.normalize("123"))
        // A real invoice mixes both systems in one field; neither is converted or dropped.
        // The thousands separator ، (U+066C) is a real character, not zero-width noise,
        // so it must survive as well.
        val mixed = "مجموع: ۱٬۲۵۰٬۰۰۰ تومان / Total: 4800"
        assertEquals(mixed, normalizer.normalize(mixed))
        // Guard against corruption, not just loss: the two numeral systems must stay
        // distinct, so Persian digits are never latinised behind the scenes.
        assertNotEquals("Persian digits must not be converted to Latin", "123", normalizer.normalize("\u06F1\u06F2\u06F3"))
    }
}
