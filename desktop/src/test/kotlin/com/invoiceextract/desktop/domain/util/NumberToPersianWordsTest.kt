package com.invoiceextract.desktop.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hermetic tests for [convertNumberToWords]: every magnitude from zero to the
 * trillions, plus the teens, hundreds and compound joins in between.
 */
class NumberToPersianWordsTest {

    @Test
    fun `zero reads as sefr toman`() {
        assertEquals("صفر تومان", convertNumberToWords(0L))
    }

    @Test
    fun `single digits`() {
        assertEquals("یک تومان", convertNumberToWords(1L))
        assertEquals("نه تومان", convertNumberToWords(9L))
    }

    @Test
    fun `teens`() {
        assertEquals("ده تومان", convertNumberToWords(10L))
        assertEquals("یازده تومان", convertNumberToWords(11L))
        assertEquals("نوزده تومان", convertNumberToWords(19L))
    }

    @Test
    fun `tens join with ones`() {
        assertEquals("بیست تومان", convertNumberToWords(20L))
        assertEquals("بیست و یک تومان", convertNumberToWords(21L))
        assertEquals("نود و نه تومان", convertNumberToWords(99L))
    }

    @Test
    fun `hundreds`() {
        assertEquals("یکصد تومان", convertNumberToWords(100L))
        assertEquals("دویست تومان", convertNumberToWords(200L))
        assertEquals("سیصد و چهل و پنج تومان", convertNumberToWords(345L))
    }

    @Test
    fun `thousands skip empty groups`() {
        assertEquals("یک هزار تومان", convertNumberToWords(1_000L))
        assertEquals("دو هزار و پنج تومان", convertNumberToWords(2_005L))
        assertEquals("دوازده هزار تومان", convertNumberToWords(12_000L))
    }

    @Test
    fun `millions compound correctly`() {
        assertEquals(
            "یک میلیون و چهل و چهار هزار تومان",
            convertNumberToWords(1_044_000L),
        )
        assertEquals("پنج میلیون تومان", convertNumberToWords(5_000_000L))
    }

    @Test
    fun `billions and trillions`() {
        assertEquals("یک میلیارد تومان", convertNumberToWords(1_000_000_000L))
        assertEquals(
            "دو میلیارد و پانصد میلیون تومان",
            convertNumberToWords(2_500_000_000L),
        )
        assertEquals("یک تریلیون تومان", convertNumberToWords(1_000_000_000_000L))
    }

    @Test
    fun `negative amounts carry the manfi prefix`() {
        assertEquals("منفی پنج تومان", convertNumberToWords(-5L))
    }
}
