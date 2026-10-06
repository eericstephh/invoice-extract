package com.invoiceextract.desktop.domain.validation

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.validation.ValidationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [GlobalTaxIdValidator] and [Invoice.withGlobalTaxIdAudit].
 *
 * Pure functions in, verdicts out — no store, no daemon. EIN cases use real
 * IRS-issued prefixes; VAT cases use structurally valid numbers (offline
 * checks judge shape and prefix, never registry existence).
 */
class GlobalTaxIdValidatorTest {

    @Test
    fun `genuine eins pass and normalize to canonical form`() {
        listOf("12-3456789", "123456789", "20-1234567", " 52-9876543 ").forEach { raw ->
            val result = GlobalTaxIdValidator.validateTaxId(raw)
            assertTrue("$raw should pass", result is GlobalTaxValidationResult.Valid)
            val valid = result as GlobalTaxValidationResult.Valid
            assertEquals(GlobalTaxIdType.US_EIN, valid.type)
            assertTrue(valid.formatted.matches(Regex("""\d{2}-\d{7}""")))
        }
    }

    @Test
    fun `bogus ein prefixes fail`() {
        listOf("00-1234567", "07-1234567", "09-1234567", "17-1234567", "29-1234567", "78-1234567")
            .forEach { raw ->
                val result = GlobalTaxIdValidator.validateTaxId(raw)
                assertTrue("$raw should fail", result is GlobalTaxValidationResult.Invalid)
                assertEquals(
                    GlobalTaxIdType.US_EIN,
                    (result as GlobalTaxValidationResult.Invalid).type,
                )
            }
    }

    @Test
    fun `malformed eins fail without a prefix verdict`() {
        listOf("12345", "12-345678", "1234567890", "AB-1234567").forEach { raw ->
            val result = GlobalTaxIdValidator.validateTaxId(raw)
            assertTrue("$raw should fail", result is GlobalTaxValidationResult.Invalid)
        }
    }

    @Test
    fun `valid eu and uk vat numbers pass`() {
        mapOf(
            "DE813456789" to "DE813456789",
            "GB123456789" to "GB123456789",
            "GB123456789012" to "GB123456789012",
            "FRAB123456789" to "FRAB123456789",
            "IT01234567890" to "IT01234567890",
            "ESX1234567X" to "ESX1234567X",
            "NL123456789B01" to "NL123456789B01",
            "de813456789" to "DE813456789",
            "DE 813 456 789" to "DE813456789",
        ).forEach { (raw, canonical) ->
            val result = GlobalTaxIdValidator.validateTaxId(raw)
            assertTrue("$raw should pass", result is GlobalTaxValidationResult.Valid)
            val valid = result as GlobalTaxValidationResult.Valid
            assertEquals(GlobalTaxIdType.EU_UK_VAT, valid.type)
            assertEquals(canonical, valid.formatted)
        }
    }

    @Test
    fun `invalid vat numbers fail`() {
        // Unknown country, short German body, short British body, long French body.
        listOf("XX123456789", "DE12345678", "GB12345678", "FRAB1234567890").forEach { raw ->
            val result = GlobalTaxIdValidator.validateTaxId(raw)
            assertTrue("$raw should fail", result is GlobalTaxValidationResult.Invalid)
        }
    }

    @Test
    fun `reasons speak the requested language`() {
        val en = GlobalTaxIdValidator.validateTaxId("00-1234567", isEnglish = true)
            as GlobalTaxValidationResult.Invalid
        val fa = GlobalTaxIdValidator.validateTaxId("00-1234567", isEnglish = false)
            as GlobalTaxValidationResult.Invalid
        assertTrue(en.reason.contains("IRS"))
        assertTrue(fa.reason.contains("IRS"))
        assertTrue(en.reason != fa.reason)
    }

    @Test
    fun `empty and blank inputs return empty`() {
        assertTrue(GlobalTaxIdValidator.validateTaxId(null) is GlobalTaxValidationResult.Empty)
        assertTrue(GlobalTaxIdValidator.validateTaxId("") is GlobalTaxValidationResult.Empty)
        assertTrue(GlobalTaxIdValidator.validateTaxId("   ") is GlobalTaxValidationResult.Empty)
    }

    @Test
    fun `the router splits the world by shape`() {
        assertTrue(GlobalTaxIdValidator.looksWesternTaxId("12-3456789"))
        assertTrue(GlobalTaxIdValidator.looksWesternTaxId("DE813456789"))
        assertTrue(GlobalTaxIdValidator.looksWesternTaxId("GB123456789"))
        assertFalse(GlobalTaxIdValidator.looksWesternTaxId("1234567890"))
        assertFalse(GlobalTaxIdValidator.looksWesternTaxId("14008238774"))
        assertFalse(GlobalTaxIdValidator.looksWesternTaxId(null))
        assertFalse(GlobalTaxIdValidator.looksWesternTaxId("   "))
    }

    @Test
    fun `global audit flags western invalids and spares digit ids`() {
        val flagged = Invoice(
            id = "w",
            sellerTaxId = "00-1234567",
            buyerNationalId = "14008238774",
        ).withGlobalTaxIdAudit()

        val status = flagged.validationStatus
        assertTrue(status is ValidationStatus.Warning)
        val reasons = (status as ValidationStatus.Warning).reasons
        assertEquals(1, reasons.size)
        assertEquals("sellerTaxId", reasons.single().field)

        val clean = Invoice(
            id = "c",
            sellerTaxId = "12-3456789",
            buyerNationalId = "14008238774",
        ).withGlobalTaxIdAudit()
        assertTrue(clean.validationStatus is ValidationStatus.Valid)
    }

    @Test
    fun `national audit ignores western shapes`() {
        // "DE987654321" strips to nine digits the Iranian checksum rejects —
        // without the router guard the national audit would flag it
        // INVALID_FORMAT-adjacent while the global pass clears it. One judge.
        val invoice = Invoice(id = "w", sellerNationalId = "DE987654321")
            .withNationalIdAudit()
            .withGlobalTaxIdAudit()

        assertTrue(invoice.validationStatus is ValidationStatus.Valid)
    }
}
