package com.invoiceextract.desktop.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hermetic tests for [convertNumberToEnglishWords]: zero through the
 * billions, teens, hyphenated tens, cents handling, currency units and the
 * non-finite guard.
 */
class NumberToEnglishWordsTest {

    @Test
    fun `zero reads with both units`() {
        assertEquals(
            "Zero US Dollars and Zero Cents",
            convertNumberToEnglishWords(0.0),
        )
    }

    @Test
    fun `spec examples read exactly`() {
        assertEquals(
            "Five Hundred Fifty US Dollars and Zero Cents",
            convertNumberToEnglishWords(550.00),
        )
        assertEquals(
            "One Thousand Two Hundred Fifty US Dollars and Seventy-Five Cents",
            convertNumberToEnglishWords(1250.75),
        )
    }

    @Test
    fun `single digits and teens`() {
        assertEquals("One US Dollar and Zero Cents", convertNumberToEnglishWords(1.0))
        assertEquals("Nine US Dollars and Zero Cents", convertNumberToEnglishWords(9.0))
        assertEquals("Ten US Dollars and Zero Cents", convertNumberToEnglishWords(10.0))
        assertEquals("Nineteen US Dollars and Zero Cents", convertNumberToEnglishWords(19.0))
    }

    @Test
    fun `tens hyphenate with ones`() {
        assertEquals("Twenty US Dollars and Zero Cents", convertNumberToEnglishWords(20.0))
        assertEquals("Twenty-One US Dollars and Zero Cents", convertNumberToEnglishWords(21.0))
        assertEquals("Ninety-Nine US Dollars and Zero Cents", convertNumberToEnglishWords(99.0))
    }

    @Test
    fun `hundreds join with spaces`() {
        assertEquals("One Hundred US Dollars and Zero Cents", convertNumberToEnglishWords(100.0))
        assertEquals(
            "Three Hundred Forty-Five US Dollars and Zero Cents",
            convertNumberToEnglishWords(345.0),
        )
    }

    @Test
    fun `thousands millions and billions compound`() {
        assertEquals(
            "One Thousand US Dollars and Zero Cents",
            convertNumberToEnglishWords(1_000.0),
        )
        assertEquals(
            "Two Thousand Five US Dollars and Zero Cents",
            convertNumberToEnglishWords(2_005.0),
        )
        assertEquals(
            "One Million US Dollars and Zero Cents",
            convertNumberToEnglishWords(1_000_000.0),
        )
        assertEquals(
            "One Billion Two Hundred Million US Dollars and Zero Cents",
            convertNumberToEnglishWords(1_200_000_000.0),
        )
    }

    @Test
    fun `cents spell with singular and plural units`() {
        assertEquals(
            "Five US Dollars and One Cent",
            convertNumberToEnglishWords(5.01),
        )
        assertEquals(
            "One US Dollar and Fifty Cents",
            convertNumberToEnglishWords(1.50),
        )
    }

    @Test
    fun `currency codes adapt the units`() {
        assertEquals(
            "Ten Euros and Zero Cents",
            convertNumberToEnglishWords(10.0, "EUR"),
        )
        assertEquals(
            "Ten Pounds and Zero Pence",
            convertNumberToEnglishWords(10.0, "GBP"),
        )
        assertEquals(
            "One Pound and One Penny",
            convertNumberToEnglishWords(1.01, "gbp"),
        )
    }

    @Test
    fun `non-finite amounts degrade to zero`() {
        assertEquals(
            "Zero US Dollars and Zero Cents",
            convertNumberToEnglishWords(Double.NaN),
        )
        assertEquals(
            "Zero US Dollars and Zero Cents",
            convertNumberToEnglishWords(Double.POSITIVE_INFINITY),
        )
    }

    @Test
    fun `half cents round to the nearest owned cent`() {
        assertEquals(
            "Ten US Dollars and One Cent",
            convertNumberToEnglishWords(10.006),
        )
    }
}
