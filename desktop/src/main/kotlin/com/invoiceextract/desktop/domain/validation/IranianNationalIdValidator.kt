package com.invoiceextract.desktop.domain.validation

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.validation.IssueSeverity
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus

/**
 * Which identifier shape an input was judged as.
 */
enum class IdentifierType {
    /** 10-digit individual national code (کد ملی اشخاص حقیقی). */
    INDIVIDUAL_NATIONAL_CODE,

    /** 11-digit legal-entity national ID (شناسه ملی اشخاص حقوقی). */
    LEGAL_ENTITY_ID,

    /** Not 8–11 digits after normalization: no algorithm applies. */
    INVALID_FORMAT,
}

/**
 * Verdict of [IranianNationalIdValidator.validateIdentifier].
 */
sealed interface IdentifierValidationResult {

    /** The checksum holds for the judged shape. */
    data class Valid(val type: IdentifierType) : IdentifierValidationResult

    /**
     * @property type The shape the input was judged as — the judged shape even on
     *   failure, so callers can explain *what* failed, not just that it did.
     * @property reason A Persian sentence, suitable for audit warnings verbatim.
     */
    data class Invalid(val type: IdentifierType, val reason: String) : IdentifierValidationResult

    /** Nothing to judge: `null` or blank input. Callers hide their chip on this. */
    data object Empty : IdentifierValidationResult
}

/**
 * Checksum validator for Iranian national identifiers, 100% offline and pure.
 *
 * Covers the two shapes the civil registry defines, each with its official
 * Modulo-11 check:
 * - **Individual code (کد ملی, 10 digits):** `S = Σ digit[i]·(10−i)` over the first
 *   nine digits, `R = S mod 11`; the check digit must equal `R` when `R < 2` and
 *   `11 − R` otherwise. Inputs of 8–9 digits are left-padded with zeros first, and
 *   ten identical digits are rejected outright — no registry ever issues those.
 * - **Legal-entity ID (شناسه ملی, 11 digits):** `S = Σ (digit[i] + (digit[9]+2)) ·
 *   weights[i]` over the first ten digits with weights
 *   `29, 27, 23, 19, 17, 29, 27, 23, 19, 17`; `R = S mod 11` maps to check digit `0`
 *   when `R == 10` and `R` otherwise.
 *
 * Stateless by design, hence an `object` rather than an injectable: there is nothing
 * to configure and nothing to mock, so call sites invoke it directly.
 */
object IranianNationalIdValidator {

    /**
     * Validates one raw identifier string.
     *
     * Normalization first: everything that is not a digit (spaces, dashes, slashes)
     * is dropped, and Persian-Indic (`۰-۹`) plus Arabic-Indic (`٠-٩`) digits are folded
     * to ASCII — an identifier typed on a Persian keyboard must judge identically to
     * its Latin-typed twin.
     *
     * @param rawInput The identifier as typed or extracted; `null` and blank mean
     *   "absent", not "invalid".
     */
    fun validateIdentifier(rawInput: String?): IdentifierValidationResult {
        if (rawInput.isNullOrBlank()) return IdentifierValidationResult.Empty

        val digits = extractDigits(rawInput)
        if (digits.isEmpty()) return IdentifierValidationResult.Empty

        return when (digits.length) {
            NATIONAL_CODE_SHORT_MIN_LENGTH,
            NATIONAL_CODE_SHORT_MAX_LENGTH,
            NATIONAL_CODE_LENGTH,
            -> validateIndividual(digits.padStart(NATIONAL_CODE_LENGTH, '0'))
            LEGAL_ENTITY_ID_LENGTH -> validateLegalEntity(digits)
            else -> IdentifierValidationResult.Invalid(
                IdentifierType.INVALID_FORMAT,
                MSG_BAD_LENGTH,
            )
        }
    }

    /** Runs Algorithm 1 over a 10-digit string. */
    private fun validateIndividual(digits: String): IdentifierValidationResult {
        if (digits.all { it == digits.first() }) {
            return IdentifierValidationResult.Invalid(
                IdentifierType.INDIVIDUAL_NATIONAL_CODE,
                MSG_REPEATED_DIGITS,
            )
        }

        val values = digits.map { it - '0' }
        var sum = 0
        for (index in 0 until NATIONAL_CODE_BODY_LENGTH) {
            sum += values[index] * (NATIONAL_CODE_LENGTH - index)
        }
        val remainder = sum % MODULUS
        val expected = if (remainder < CHECK_THRESHOLD) remainder else MODULUS - remainder

        return if (values[NATIONAL_CODE_BODY_LENGTH] == expected) {
            IdentifierValidationResult.Valid(IdentifierType.INDIVIDUAL_NATIONAL_CODE)
        } else {
            IdentifierValidationResult.Invalid(
                IdentifierType.INDIVIDUAL_NATIONAL_CODE,
                MSG_CHECK_MISMATCH,
            )
        }
    }

    /** Runs Algorithm 2 over an 11-digit string. */
    private fun validateLegalEntity(digits: String): IdentifierValidationResult {
        val values = digits.map { it - '0' }
        val tenthFactor = values[LEGAL_ENTITY_BODY_LENGTH - 1] + TENTH_DIGIT_OFFSET

        var sum = 0
        for (index in 0 until LEGAL_ENTITY_BODY_LENGTH) {
            sum += (values[index] + tenthFactor) * LEGAL_ENTITY_WEIGHTS[index]
        }
        val remainder = sum % MODULUS
        val expected = if (remainder == MODULUS - 1) 0 else remainder

        return if (values[LEGAL_ENTITY_BODY_LENGTH] == expected) {
            IdentifierValidationResult.Valid(IdentifierType.LEGAL_ENTITY_ID)
        } else {
            IdentifierValidationResult.Invalid(
                IdentifierType.LEGAL_ENTITY_ID,
                MSG_CHECK_MISMATCH,
            )
        }
    }

    /**
     * Keeps ASCII digits and folds Persian/Arabic-Indic ones; everything else
     * (spaces, dashes, slashes, letters) is dropped. Letters are dropped rather than
     * rejected because identifiers arrive glued to field labels (`کدملی: ...`) after
     * OCR, and the length gate below still rejects anything that is not 8–11 digits.
     */
    private fun extractDigits(rawInput: String): String = buildString(rawInput.length) {
        for (char in rawInput) {
            when (char.code) {
                in ASCII_DIGIT_START..ASCII_DIGIT_END -> append(char)
                in PERSIAN_DIGIT_START..PERSIAN_DIGIT_END ->
                    append(('0'.code + (char.code - PERSIAN_DIGIT_START)).toChar())
                in ARABIC_INDIC_DIGIT_START..ARABIC_INDIC_DIGIT_END ->
                    append(('0'.code + (char.code - ARABIC_INDIC_DIGIT_START)).toChar())
            }
        }
    }

    private const val MODULUS = 11
    private const val CHECK_THRESHOLD = 2
    private const val TENTH_DIGIT_OFFSET = 2

    private const val NATIONAL_CODE_LENGTH = 10
    private const val NATIONAL_CODE_BODY_LENGTH = 9
    private const val NATIONAL_CODE_SHORT_MIN_LENGTH = 8
    private const val NATIONAL_CODE_SHORT_MAX_LENGTH = 9
    private const val LEGAL_ENTITY_ID_LENGTH = 11
    private const val LEGAL_ENTITY_BODY_LENGTH = 10

    private val LEGAL_ENTITY_WEIGHTS =
        intArrayOf(29, 27, 23, 19, 17, 29, 27, 23, 19, 17)

    private const val ASCII_DIGIT_START = 0x30
    private const val ASCII_DIGIT_END = 0x39
    private const val PERSIAN_DIGIT_START = 0x06F0
    private const val PERSIAN_DIGIT_END = 0x06F9
    private const val ARABIC_INDIC_DIGIT_START = 0x0660
    private const val ARABIC_INDIC_DIGIT_END = 0x0669

    private const val MSG_BAD_LENGTH =
        "طول شناسه باید ۱۰ رقم (کد ملی) یا ۱۱ رقم (شناسه ملی حقوقی) باشد."
    private const val MSG_REPEATED_DIGITS =
        "کد ملی با ارقام تکراری معتبر نیست."
    private const val MSG_CHECK_MISMATCH =
        "رقم کنترل با الگوریتم شناسه مطابقت ندارد."
}

/**
 * Merges national-ID audit warnings into an already-validated [Invoice].
 *
 * Each present, non-blank national ID that fails [IranianNationalIdValidator] becomes
 * one WARNING issue — `Valid` escalates to `Warning`, `Warning` grows its reasons. An
 * [ValidationStatus.Invalid] invoice is returned untouched: it is already flagged, and
 * audit warnings must never be relabelled as critical errors.
 *
 * Shared by the pipeline and the edit path so a freshly extracted invoice and a
 * hand-corrected one are judged by exactly the same rule.
 */
internal fun Invoice.withNationalIdAudit(): Invoice {
    val issues = buildList {
        auditNationalId(SELLER_FIELD_KEY, SELLER_FIELD_LABEL, sellerNationalId)?.let(::add)
        auditNationalId(BUYER_FIELD_KEY, BUYER_FIELD_LABEL, buyerNationalId)?.let(::add)
    }
    if (issues.isEmpty()) return this

    return when (val status = validationStatus) {
        ValidationStatus.Valid -> copy(validationStatus = ValidationStatus.Warning(issues))
        is ValidationStatus.Warning -> copy(validationStatus = ValidationStatus.Warning(status.reasons + issues))
        is ValidationStatus.Invalid -> this
    }
}

/** Audits one national-ID field, or returns `null` when there is nothing to flag. */
private fun auditNationalId(
    fieldKey: String,
    fieldLabel: String,
    rawInput: String?,
): ValidationIssue? {
    if (rawInput.isNullOrBlank()) return null
    // Western shapes belong exclusively to the global audit — judging them here
    // too would flag the same keystrokes twice under two verdicts.
    if (GlobalTaxIdValidator.looksWesternTaxId(rawInput)) return null
    return when (IranianNationalIdValidator.validateIdentifier(rawInput)) {
        is IdentifierValidationResult.Valid,
        IdentifierValidationResult.Empty,
        -> null
        is IdentifierValidationResult.Invalid -> ValidationIssue(
            field = fieldKey,
            description = "شناسه/کد ملی $fieldLabel ($rawInput) از نظر الگوریتمی نامعتبر است.",
            severity = IssueSeverity.WARNING,
        )
    }
}

private const val SELLER_FIELD_KEY = "sellerNationalId"
private const val BUYER_FIELD_KEY = "buyerNationalId"
private const val SELLER_FIELD_LABEL = "فروشنده"
private const val BUYER_FIELD_LABEL = "خریدار"
