package com.invoiceextract.domain.validation

/**
 * Severity of a [ValidationIssue].
 */
enum class IssueSeverity {
    /** Informational note; does not affect the usability of the invoice. */
    INFO,

    /** The invoice is usable but the user should review the flagged field. */
    WARNING,

    /** The invoice is unreliable and must not be persisted as a financial record. */
    CRITICAL,
}

/**
 * A single problem discovered while validating an
 * `com.invoiceextract.domain.model.Invoice`.
 *
 * @property field       Dotted path of the offending field, e.g. `"items[2].totalPrice"`.
 * @property description Human readable explanation, suitable to show to the end user.
 * @property severity    How much this issue matters. See [IssueSeverity].
 */
data class ValidationIssue(
    val field: String,
    val description: String,
    val severity: IssueSeverity,
)

/**
 * Outcome of validating an extracted invoice.
 *
 * Implemented as a sealed class so that `when` expressions over the three possible
 * states are exhaustive at compile time — the compiler guarantees every branch is
 * handled instead of silently falling through.
 */
sealed class ValidationStatus {

    /**
     * Every mandatory field was extracted and all arithmetic checks passed.
     */
    data object Valid : ValidationStatus()

    /**
     * The invoice is complete enough to be trusted, but some fields need a second look.
     *
     * @property reasons Non-empty list of issues, each with a severity of
     *                   [IssueSeverity.INFO] or [IssueSeverity.WARNING].
     */
    data class Warning(val reasons: List<ValidationIssue>) : ValidationStatus()

    /**
     * The invoice must not be treated as a financial record.
     *
     * @property criticalErrors Non-empty list of issues, each with a severity of
     *                           [IssueSeverity.CRITICAL].
     */
    data class Invalid(val criticalErrors: List<ValidationIssue>) : ValidationStatus()
}
