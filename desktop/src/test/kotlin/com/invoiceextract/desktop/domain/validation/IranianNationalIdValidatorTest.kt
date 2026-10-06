package com.invoiceextract.desktop.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for [IranianNationalIdValidator].
 *
 * The vectors below were computed by hand against the official Modulo-11 definitions
 * (see the KDoc for the worked sums), so the suite checks the implementation against
 * the algorithm rather than against itself. Every case is pure input-in-verdict-out:
 * no daemon, no store, no dispatcher.
 */
class IranianNationalIdValidatorTest {

    @Test
    fun `genuine individual codes pass`() {
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier("1234567891"),
        )
    }

    @Test
    fun `short codes pad with leading zeros before the check`() {
        // 9 digits → "0123456789", whose checksum holds.
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier("123456789"),
        )
        // 8 digits → "0012345679", whose checksum holds.
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier("12345679"),
        )
    }

    @Test
    fun `individual code with a broken check digit fails`() {
        val result = IranianNationalIdValidator.validateIdentifier("1234567892")

        assertTrue(result is IdentifierValidationResult.Invalid)
        assertEquals(
            IdentifierType.INDIVIDUAL_NATIONAL_CODE,
            (result as IdentifierValidationResult.Invalid).type,
        )
    }

    @Test
    fun `repeated-digit codes fail without reaching the checksum`() {
        // Ten identical digits can never be issued, whatever their checksum says.
        assertTrue(
            IranianNationalIdValidator.validateIdentifier("1111111111")
                is IdentifierValidationResult.Invalid,
        )
        assertTrue(
            IranianNationalIdValidator.validateIdentifier("0000000000")
                is IdentifierValidationResult.Invalid,
        )
    }

    @Test
    fun `genuine legal-entity ids pass`() {
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.LEGAL_ENTITY_ID),
            IranianNationalIdValidator.validateIdentifier("14008238754"),
        )
    }

    @Test
    fun `altered legal-entity ids fail`() {
        // Last digit 4 → 5: one keystroke off a genuine ID.
        val result = IranianNationalIdValidator.validateIdentifier("14008238755")

        assertTrue(result is IdentifierValidationResult.Invalid)
        assertEquals(
            IdentifierType.LEGAL_ENTITY_ID,
            (result as IdentifierValidationResult.Invalid).type,
        )
    }

    @Test
    fun `persian and arabic-indic digits convert and validate cleanly`() {
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier("۱۲۳۴۵۶۷۸۹۱"),
        )
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier("١٢٣٤٥٦٧٨٩١"),
        )
    }

    @Test
    fun `separators and surrounding noise are stripped, not judged`() {
        assertEquals(
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE),
            IranianNationalIdValidator.validateIdentifier(" 1234-567-891 "),
        )
    }

    @Test
    fun `empty and blank inputs report empty rather than invalid`() {
        assertEquals(
            IdentifierValidationResult.Empty,
            IranianNationalIdValidator.validateIdentifier(""),
        )
        assertEquals(
            IdentifierValidationResult.Empty,
            IranianNationalIdValidator.validateIdentifier("   "),
        )
        assertEquals(
            IdentifierValidationResult.Empty,
            IranianNationalIdValidator.validateIdentifier(null),
        )
    }

    @Test
    fun `lengths outside 8 to 11 digits report invalid format`() {
        val short = IranianNationalIdValidator.validateIdentifier("12345")
        assertTrue(short is IdentifierValidationResult.Invalid)
        assertEquals(
            IdentifierType.INVALID_FORMAT,
            (short as IdentifierValidationResult.Invalid).type,
        )

        val long = IranianNationalIdValidator.validateIdentifier("123456789012")
        assertTrue(long is IdentifierValidationResult.Invalid)
        assertEquals(
            IdentifierType.INVALID_FORMAT,
            (long as IdentifierValidationResult.Invalid).type,
        )
    }
}
