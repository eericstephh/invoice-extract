package com.invoiceextract.domain.validation

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import kotlin.math.abs

/**
 * Verifies the arithmetic and completeness of a freshly extracted [Invoice].
 *
 * The extractor is a probabilistic pipeline: OCR can misread a digit, a language model
 * can drop a line, and nothing in the upstream stages guarantees that the numbers on an
 * [Invoice] actually add up. This class is the financial-integrity gate between
 * "we extracted something" and "this is a record worth persisting". Its output,
 * [Invoice.validationStatus], is what the UI and the persistence layer branch on.
 *
 * **Deterministic by contract.** [validate] is a pure function: it reads only [invoice],
 * writes only the returned copy, and never touches the clock, the network, a random
 * source or any locale-sensitive formatting. The same input always yields the
 * byte-identical output, which is what makes the validator unit-testable to 100% and
 * what makes a stored [ValidationStatus] reproducible months later. The only clock
 * access in the whole flow is [Invoice.createdAtEpochMs], which `copy` preserves as-is.
 *
 * **Non-short-circuiting.** Every rule is evaluated for every invoice, even after a
 * CRITICAL issue is found, so the returned issue list is always the complete picture.
 * The user gets to see everything wrong with the document at once instead of fixing
 * issues one render at a time.
 *
 * **Suspicion only ever escalates.** An item already flagged by the extractor keeps its
 * [InvoiceItem.isSuspicious] flag even when the arithmetic happens to pass here; the
 * validator only ever sets it to `true`, never clears it. Downgrading a suspicion based
 * on a checksum would invert the trust direction of a financial system.
 *
 * Tolerances are absolute rather than relative: Iranian invoices are quoted in Toman,
 * where a single line is routinely in the millions, so a percentage-based epsilon would
 * tolerate errors larger than most invoices. One Toman of slack absorbs only the
 * half-Double rounding the extraction layer introduces, and nothing more.
 *
 * @see ValidationStatus
 * @see InvoiceItem.expectedTotal
 */
class InvoiceValidator {

    /**
     * Runs every rule over [invoice] and returns a copy carrying the verdict.
     *
     * The input is never mutated: the flagged items and the assigned
     * [ValidationStatus] are produced with [Invoice.copy] and [InvoiceItem.copy], so the
     * caller's reference stays exactly as the extraction layer left it — the audit trail
     * of what the pipeline actually produced stays intact next to what validation made
     * of it.
     *
     * @param invoice The invoice as extracted. Required fields may be missing and the
     *   arithmetic may be inconsistent; that is precisely what this call detects.
     * @return A copy of [invoice] whose items carry their final [InvoiceItem
     *   .isSuspicious] flags and whose [Invoice.validationStatus] is the verdict.
     */
    fun validate(invoice: Invoice): Invoice {
        val issues = mutableListOf<ValidationIssue>()

        // (a) Per-line checks. Runs before the totals cross-check on purpose: a single
        // badly misread line usually propagates into the invoice totals as well, and
        // surfacing the line-level cause first makes the report readable top-down.
        val validatedItems = invoice.items.mapIndexed { index, item ->
            val arithmeticMismatch = checkItemArithmetic(index, item, issues)
            val lowConfidence = checkItemConfidence(index, item, issues)
            if (arithmeticMismatch || lowConfidence) item.copy(isSuspicious = true) else item
        }

        // (b) Invoice-level arithmetic.
        checkTotals(invoice, issues)

        // (c) Structural completeness. Assessed last because these are the issues that
        // make the record unusable, and they should sit at the top of the report.
        checkCompleteness(invoice, issues)

        return invoice.copy(
            items = validatedItems,
            validationStatus = statusFor(issues),
        )
    }

    /**
     * Rule (a1): the printed line total must equal its own components.
     *
     * `expectedTotal = quantity * unitPrice - discount + tax`, compared against
     * [InvoiceItem.totalPrice] with [TOTAL_TOLERANCE] of slack. A divergence means the
     * extractor misread at least one of the four input fields — or the total itself —
     * and the printed value cannot be trusted.
     *
     * @return `true` when the item is flagged, so the caller can flip
     *   [InvoiceItem.isSuspicious] without re-evaluating the condition.
     */
    private fun checkItemArithmetic(
        index: Int,
        item: InvoiceItem,
        issues: MutableList<ValidationIssue>,
    ): Boolean {
        if (abs(item.expectedTotal - item.totalPrice) > TOTAL_TOLERANCE) {
            issues.add(
                ValidationIssue(
                    field = "items[$index].totalPrice",
                    description = ITEM_ARITHMETIC_MISMATCH,
                    severity = IssueSeverity.WARNING,
                ),
            )
            return true
        }
        return false
    }

    /**
     * Rule (a2): the extractor's own confidence in the line must clear the bar.
     *
     * A line below [MIN_CONFIDENCE] is not necessarily wrong — it may well be perfectly
     * legible — but the model was not sure enough to be trusted unsupervised. INFO
     * severity: it informs the review queue without implying the invoice is broken.
     */
    private fun checkItemConfidence(
        index: Int,
        item: InvoiceItem,
        issues: MutableList<ValidationIssue>,
    ): Boolean {
        if (item.confidence < MIN_CONFIDENCE) {
            issues.add(
                ValidationIssue(
                    field = "items[$index].confidence",
                    description = LOW_CONFIDENCE,
                    severity = IssueSeverity.INFO,
                ),
            )
            return true
        }
        return false
    }

    /**
     * Rule (b): the grand total must reconcile with subtotal, tax and discount.
     *
     * `expectedGrandTotal = subtotal + totalTax - totalDiscount`. Note the sign
     * convention: the discount *reduces* the payable amount, the tax *increases* it. A
     * mismatch means the extraction dropped, duplicated or misaligned a field somewhere
     * between the line items and the invoice footer — the classic OCR failure on a
     * dense Persian invoice grid.
     */
    private fun checkTotals(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        val expectedGrandTotal = invoice.subtotal + invoice.totalTax - invoice.totalDiscount
        if (abs(expectedGrandTotal - invoice.grandTotal) > TOTAL_TOLERANCE) {
            issues.add(
                ValidationIssue(
                    field = "grandTotal",
                    description = GRAND_TOTAL_MISMATCH,
                    severity = IssueSeverity.WARNING,
                ),
            )
        }
    }

    /**
     * Rule (c): the fields without which the document is not an invoice.
     *
     * Missing identity fields ([Invoice.invoiceNumber], [Invoice.date]) are WARNINGs:
     * the record is still arithmetically sound and can be repaired by hand. A missing
     * item list or a non-positive grand total is CRITICAL: the first means there is
     * nothing to invoice, the second means the amount is meaningless, and neither may
     * ever be persisted as a financial record.
     */
    private fun checkCompleteness(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        if (invoice.items.isEmpty()) {
            issues.add(
                ValidationIssue(
                    field = "items",
                    description = MISSING_ITEMS,
                    severity = IssueSeverity.CRITICAL,
                ),
            )
        }

        if (invoice.grandTotal <= 0.0) {
            issues.add(
                ValidationIssue(
                    field = "grandTotal",
                    description = INVALID_GRAND_TOTAL,
                    severity = IssueSeverity.CRITICAL,
                ),
            )
        }

        if (invoice.invoiceNumber.isNullOrBlank()) {
            issues.add(
                ValidationIssue(
                    field = "invoiceNumber",
                    description = MISSING_INVOICE_NUMBER,
                    severity = IssueSeverity.WARNING,
                ),
            )
        }

        if (invoice.date.isNullOrBlank()) {
            issues.add(
                ValidationIssue(
                    field = "date",
                    description = MISSING_DATE,
                    severity = IssueSeverity.WARNING,
                ),
            )
        }
    }

    /**
     * Reduces the complete issue list to a single verdict.
     *
     * [ValidationStatus.Invalid] carries only the CRITICAL entries, matching the
     * documented contract of that class, so a caller displaying `criticalErrors` never
     * has to re-filter by severity. When nothing critical was found, every remaining
     * issue is INFO or WARNING by construction, so [ValidationStatus.Warning] can take
     * the list verbatim.
     */
    private fun statusFor(issues: List<ValidationIssue>): ValidationStatus {
        val criticalErrors = issues.filter { it.severity == IssueSeverity.CRITICAL }
        return when {
            criticalErrors.isNotEmpty() -> ValidationStatus.Invalid(criticalErrors)
            issues.isNotEmpty() -> ValidationStatus.Warning(issues)
            else -> ValidationStatus.Valid
        }
    }

    private companion object {
        /** Absolute slack on every monetary comparison, in the invoice's currency. */
        private const val TOTAL_TOLERANCE = 1.0

        /** Confidence floor for an unreviewed line item. */
        private const val MIN_CONFIDENCE = 0.70f

        private const val ITEM_ARITHMETIC_MISMATCH =
            "عدم تطابق مبلغ سطر با حاصل‌ضرب تعداد در قیمت"

        private const val LOW_CONFIDENCE = "دقت استخراج پایین برای این سطر"

        private const val GRAND_TOTAL_MISMATCH =
            "مغایرت مبلغ کل فاکتور با مجموع اقلام، مالیات و تخفیف"

        private const val MISSING_ITEMS = "فاکتور فاقد اقلام کالا می‌باشد"

        private const val INVALID_GRAND_TOTAL = "مبلغ کل فاکتور نامعتبر یا صفر است"

        private const val MISSING_INVOICE_NUMBER = "شماره فاکتور یافت نشد"

        private const val MISSING_DATE = "تاریخ فاکتور ثبت نشده است"
    }
}
