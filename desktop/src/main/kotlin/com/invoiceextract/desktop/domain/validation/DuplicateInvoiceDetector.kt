package com.invoiceextract.desktop.domain.validation

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.domain.model.Invoice

/**
 * Why an archived invoice was flagged as a possible double entry.
 */
enum class DuplicateReason {
    /** Same invoice number from the same seller: the strongest signal. */
    EXACT_NUMBER,

    /** Same seller, same date and same total without a usable number to match on. */
    FINGERPRINT_MATCH,
}

/**
 * One archived invoice that looks like [DuplicateReason] of re-entering the invoice
 * under review.
 */
data class DuplicateMatch(
    val existingInvoice: Invoice,
    val reason: DuplicateReason,
)

/**
 * Double-entry guard: cross-references an invoice against the persistent archive
 * before it is saved or exported.
 *
 * Pure and side-effect-free — the archive arrives as a plain list, so the same inputs
 * always yield the same verdict on any thread and any dispatcher.
 *
 * Two passes, strongest signal first:
 * - **Rule A ([DuplicateReason.EXACT_NUMBER])**: a non-blank invoice number that
 *   matches an archived number from the same seller.
 * - **Rule B ([DuplicateReason.FINGERPRINT_MATCH])**: same seller, same date and same
 *   positive total, for the many invoices whose number was never extracted.
 *
 * Every comparison runs in match space: the shared [DesktopPersianNormalizer] unifies
 * letters and spacing, and [foldDigits] additionally folds Persian-Indic (`۰-۹`) and
 * Arabic-Indic (`٠-٩`) digits to ASCII — the normalizer deliberately never converts
 * numerals (an amount that moved is an amount that changed), but *comparison* must
 * treat `۱۰۰۱` and `1001` as the same number or the guard goes blind on half the
 * archive. Amounts compare with exact `Double` equality: both sides come out of the
 * same extraction path, so a true re-entry is bit-identical and anything else is a
 * different invoice.
 *
 * @param normalizer The shared Persian normalizer; matching runs through it on both
 *   the target and the archived side.
 */
class DuplicateInvoiceDetector(
    private val normalizer: DesktopPersianNormalizer,
) {

    /**
     * Finds the first archived invoice that looks like a re-entry of [target], or
     * `null` when the archive is clean.
     *
     * @param target The invoice under review.
     * @param existingInvoices The persistent archive to check against.
     * @param ignoreSelfId Archive rows with this id are skipped, so editing an
     *   already-saved invoice never flags itself. Defaults to the target's own id.
     */
    fun detectDuplicate(
        target: Invoice,
        existingInvoices: List<Invoice>,
        ignoreSelfId: String? = target.id,
    ): DuplicateMatch? {
        val candidates = existingInvoices.filter { it.id != ignoreSelfId }
        if (candidates.isEmpty()) return null

        findExactNumber(target, candidates)?.let { return it }
        return findFingerprint(target, candidates)
    }

    /** Rule A: same normalized number from the same normalized seller. */
    private fun findExactNumber(target: Invoice, candidates: List<Invoice>): DuplicateMatch? {
        val targetNumber = matchNumber(target.invoiceNumber) ?: return null
        val targetSeller = matchText(target.sellerName)

        return candidates.firstOrNull { existing ->
            matchNumber(existing.invoiceNumber) == targetNumber &&
                matchText(existing.sellerName) == targetSeller
        }?.let { DuplicateMatch(it, DuplicateReason.EXACT_NUMBER) }
    }

    /** Rule B: same seller, same date and same positive total. */
    private fun findFingerprint(target: Invoice, candidates: List<Invoice>): DuplicateMatch? {
        if (target.grandTotal <= 0.0) return null
        val targetSeller = matchText(target.sellerName)
        val targetDate = matchText(target.date)

        return candidates.firstOrNull { existing ->
            existing.grandTotal > 0.0 &&
                existing.grandTotal == target.grandTotal &&
                matchText(existing.sellerName) == targetSeller &&
                matchText(existing.date) == targetDate
        }?.let { DuplicateMatch(it, DuplicateReason.FINGERPRINT_MATCH) }
    }

    /**
     * A value in match space: normalized letters and spacing, folded digits, trimmed.
     * `null` becomes the empty string, so two unknown sellers still compare equal —
     * the number (Rule A) or the date-plus-amount (Rule B) carries the verdict there.
     */
    private fun matchText(value: String?): String =
        foldDigits(normalizer.normalize(value?.trim().orEmpty()))

    /**
     * An invoice number in match space, or `null` when there is no number to match on.
     */
    private fun matchNumber(value: String?): String? =
        matchText(value).takeIf { it.isNotBlank() }

    /**
     * Folds Persian-Indic and Arabic-Indic digits to ASCII so `۱۲۳` compares equal to
     * `123`. Letters pass through untouched — that half of the comparison belongs to
     * the normalizer, not to digit folding.
     */
    private fun foldDigits(value: String): String {
        if (value.isEmpty()) return value
        return buildString(value.length) {
            for (char in value) {
                append(
                    when (char.code) {
                        in PERSIAN_DIGIT_START..PERSIAN_DIGIT_END ->
                            ('0'.code + (char.code - PERSIAN_DIGIT_START)).toChar()
                        in ARABIC_INDIC_DIGIT_START..ARABIC_INDIC_DIGIT_END ->
                            ('0'.code + (char.code - ARABIC_INDIC_DIGIT_START)).toChar()
                        else -> char
                    },
                )
            }
        }
    }

    private companion object {
        const val PERSIAN_DIGIT_START = 0x06F0
        const val PERSIAN_DIGIT_END = 0x06F9
        const val ARABIC_INDIC_DIGIT_START = 0x0660
        const val ARABIC_INDIC_DIGIT_END = 0x0669
    }
}
