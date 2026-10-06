package com.invoiceextract.desktop.domain.validation

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.validation.IssueSeverity
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus

/**
 * Which western identifier shape an input was judged as.
 */
enum class GlobalTaxIdType {
    /** US Employer Identification Number, canonical `XX-XXXXXXX`. */
    US_EIN,

    /** European or UK VAT number, canonical `CC + body`. */
    EU_UK_VAT,

    /** Recognizably western but matching neither shape. */
    UNKNOWN,
}

/**
 * Verdict of [GlobalTaxIdValidator.validateTaxId].
 */
sealed interface GlobalTaxValidationResult {

    /**
     * @property formatted The canonical rendering: `XX-XXXXXXX` for EINs, the
     *   spaceless uppercase form for VAT numbers.
     */
    data class Valid(val type: GlobalTaxIdType, val formatted: String) : GlobalTaxValidationResult

    /**
     * @property type The shape the input was judged as — the judged shape even
     *   on failure, so callers can explain *what* failed, not just that it did.
     * @property reason A full sentence in the requested language, suitable for
     *   audit warnings and UI chips verbatim.
     */
    data class Invalid(val type: GlobalTaxIdType, val reason: String) : GlobalTaxValidationResult

    /** Nothing to judge: `null` or blank input. Callers hide their chip on this. */
    data object Empty : GlobalTaxValidationResult
}

/**
 * Algorithmic validator for US EINs and European/UK VAT numbers, 100% offline
 * and pure — the global counterpart to [IranianNationalIdValidator].
 *
 * The two validators split the world by shape, never by locale: [looksWesternTaxId]
 * is the single router both the audit pipeline and the editor chips use, so a
 * value is judged by exactly one of them. Pure digit strings stay Iranian
 * (a 9-digit code with no dash is a short national code, not an EIN); anything
 * carrying ASCII letters, or an EIN-shaped dash, goes global.
 *
 * Stateless by design, hence an `object`: nothing to configure, nothing to mock.
 */
object GlobalTaxIdValidator {

    /**
     * Validates one raw tax identifier.
     *
     * @param rawInput The identifier as typed or extracted; `null` and blank
     *   mean "absent", not "invalid".
     * @param isEnglish `true` for English verdict sentences, `false` for
     *   Persian. Defaults to English: the global workspace opens in English.
     */
    fun validateTaxId(rawInput: String?, isEnglish: Boolean = true): GlobalTaxValidationResult {
        if (rawInput.isNullOrBlank()) return GlobalTaxValidationResult.Empty
        val compact = rawInput.trim().uppercase().filterNot { it.isWhitespace() }

        // A VAT number always opens with its two-letter country code.
        val vatBody = VAT_SHAPE.matchEntire(compact.replace(VAT_SEPARATORS, ""))
        if (vatBody != null) return validateVat(vatBody, isEnglish)

        // An EIN is nine digits with at most one dash, normalized to canonical.
        val digits = compact.replace("-", "")
        if (digits.length == EIN_DIGITS && digits.all { it.isDigit() } &&
            compact.count { it == '-' } <= 1
        ) {
            return validateEin(digits, isEnglish)
        }

        return GlobalTaxValidationResult.Invalid(
            GlobalTaxIdType.UNKNOWN,
            if (isEnglish) MSG_UNRECOGNIZED_EN else MSG_UNRECOGNIZED_FA,
        )
    }

    /**
     * The shape router: `true` when [validateTaxId] owns the value.
     *
     * Western means an ASCII letter anywhere, an EIN-shaped dash, or a VAT
     * shape (two leading letters + alphanumerics). Everything else — pure
     * digit strings of any length — belongs to the Iranian validator, which is
     * what keeps `1234567890` a national code in both languages.
     */
    fun looksWesternTaxId(rawInput: String?): Boolean {
        if (rawInput.isNullOrBlank()) return false
        val normalized = rawInput.trim().uppercase()
        if (normalized.any { it in 'A'..'Z' }) return true
        if (EIN_DASHED.matches(normalized)) return true
        return VAT_SHAPE.matches(normalized.replace(VAT_SEPARATORS, ""))
    }

    private fun validateEin(digits: String, isEnglish: Boolean): GlobalTaxValidationResult {
        val prefix = digits.substring(0, 2)
        if (prefix in INVALID_EIN_PREFIXES) {
            return GlobalTaxValidationResult.Invalid(
                GlobalTaxIdType.US_EIN,
                if (isEnglish) {
                    "EIN prefix $prefix was never issued by the IRS."
                } else {
                    "پیش‌شماره $prefix توسط IRS صادر نشده است."
                },
            )
        }
        return GlobalTaxValidationResult.Valid(
            GlobalTaxIdType.US_EIN,
            digits.substring(0, 2) + "-" + digits.substring(2),
        )
    }

    private fun validateVat(
        match: MatchResult,
        isEnglish: Boolean,
    ): GlobalTaxValidationResult {
        val country = match.groupValues[1]
        val body = match.groupValues[2]
        if (country !in VAT_COUNTRIES) {
            return GlobalTaxValidationResult.Invalid(
                GlobalTaxIdType.UNKNOWN,
                if (isEnglish) {
                    "Unknown VAT country code \"$country\"."
                } else {
                    "کد کشوری \"$country\" در فهرست VAT شناخته نشد."
                },
            )
        }
        val pattern = VAT_BODY_PATTERNS[country]
        if (pattern != null && !pattern.matches(body)) {
            return GlobalTaxValidationResult.Invalid(
                GlobalTaxIdType.EU_UK_VAT,
                if (isEnglish) {
                    "VAT number does not match the $country format."
                } else {
                    "شماره VAT با قالب کشور $country مطابقت ندارد."
                },
            )
        }
        return GlobalTaxValidationResult.Valid(GlobalTaxIdType.EU_UK_VAT, country + body)
    }

    /** Nine digits, at most one dash, dash only in the canonical slot. */
    private val EIN_DASHED = Regex("""\d{2}-\d{7}""")

    private const val EIN_DIGITS = 9

    /**
     * EIN prefixes the IRS never issued: no-campus and reserved blocks. A
     * prefix outside this set is *issued*, which is all an offline check can
     * say — issue is not existence, and the docs say so.
     */
    private val INVALID_EIN_PREFIXES = setOf(
        "00", "07", "08", "09", "17", "18", "19", "28", "29",
        "49", "69", "70", "78", "79", "89",
    )

    /** Two letters plus 2–12 alphanumerics, separators stripped first. */
    private val VAT_SHAPE = Regex("""([A-Z]{2})([A-Z0-9]{2,12})""")
    private val VAT_SEPARATORS = Regex("""[\s.\-_]""")

    /**
     * EU members plus Great Britain, Northern Ireland (`XI`) and the legacy
     * Greek `EL` prefix — every issuer a client invoice can name.
     */
    private val VAT_COUNTRIES = setOf(
        "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES",
        "FI", "FR", "GB", "GR", "HR", "HU", "IE", "IT", "LT", "LU",
        "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "XI",
    )

    /**
     * Body patterns for the majors; any other member accepts any 2–12
     * alphanumerics once its country code checks out.
     */
    private val VAT_BODY_PATTERNS = mapOf(
        "DE" to Regex("""\d{9}"""),
        "GB" to Regex("""(\d{9}|\d{12})"""),
        "FR" to Regex("""[A-Z0-9]{2}\d{9}"""),
        "IT" to Regex("""\d{11}"""),
        "ES" to Regex("""[A-Z0-9]\d{7}[A-Z0-9]"""),
        "NL" to Regex("""\d{9}B\d{2}"""),
    )

    private const val MSG_UNRECOGNIZED_EN = "Not a recognized US EIN or EU/UK VAT number."
    private const val MSG_UNRECOGNIZED_FA = "قالب EIN آمریکا یا VAT اروپا/بریتانیا شناسایی نشد."
}

/**
 * Merges global tax-ID audit warnings into an already-validated [Invoice].
 *
 * Judges the two tax-ID fields plus both national-ID fields, but only the
 * values [GlobalTaxIdValidator.looksWesternTaxId] claims — digit strings stay
 * exclusively with [withNationalIdAudit], so no value is ever flagged twice
 * for the same keystrokes. Each western invalid becomes one WARNING issue;
 * `Valid` escalates to `Warning`, `Warning` grows, `Invalid` passes through
 * untouched, exactly like the national audit.
 *
 * Shared by the pipeline and the edit path so a freshly extracted invoice and
 * a hand-corrected one are judged by exactly the same rule.
 */
internal fun Invoice.withGlobalTaxIdAudit(): Invoice {
    val issues = buildList {
        auditGlobalTaxId(TAX_SELLER_FIELD_KEY, SELLER_FIELD_LABEL, sellerTaxId)?.let(::add)
        auditGlobalTaxId(TAX_BUYER_FIELD_KEY, BUYER_FIELD_LABEL, buyerTaxId)?.let(::add)
        auditGlobalTaxId(NATIONAL_SELLER_FIELD_KEY, SELLER_FIELD_LABEL, sellerNationalId)?.let(::add)
        auditGlobalTaxId(NATIONAL_BUYER_FIELD_KEY, BUYER_FIELD_LABEL, buyerNationalId)?.let(::add)
    }
    if (issues.isEmpty()) return this

    return when (val status = validationStatus) {
        ValidationStatus.Valid -> copy(validationStatus = ValidationStatus.Warning(issues))
        is ValidationStatus.Warning -> copy(validationStatus = ValidationStatus.Warning(status.reasons + issues))
        is ValidationStatus.Invalid -> this
    }
}

/** Audits one field globally, or returns `null` when there is nothing to flag. */
private fun auditGlobalTaxId(
    fieldKey: String,
    fieldLabel: String,
    rawInput: String?,
): ValidationIssue? {
    if (rawInput.isNullOrBlank()) return null
    if (!GlobalTaxIdValidator.looksWesternTaxId(rawInput)) return null
    return when (GlobalTaxIdValidator.validateTaxId(rawInput)) {
        is GlobalTaxValidationResult.Valid,
        GlobalTaxValidationResult.Empty,
        -> null
        is GlobalTaxValidationResult.Invalid -> ValidationIssue(
            field = fieldKey,
            description = "شناسه مالیاتی $fieldLabel ($rawInput) قالب معتبر EIN آمریکا یا VAT اروپا/بریتانیا ندارد.",
            severity = IssueSeverity.WARNING,
        )
    }
}

private const val TAX_SELLER_FIELD_KEY = "sellerTaxId"
private const val TAX_BUYER_FIELD_KEY = "buyerTaxId"
private const val NATIONAL_SELLER_FIELD_KEY = "sellerNationalId"
private const val NATIONAL_BUYER_FIELD_KEY = "buyerNationalId"
private const val SELLER_FIELD_LABEL = "فروشنده"
private const val BUYER_FIELD_LABEL = "خریدار"
