package com.invoiceextract.domain.model

/**
 * A single line item of an [Invoice].
 *
 * All monetary fields are expressed in the currency declared by the owning
 * invoice (see [Invoice.currency]). Persian invoices are frequently quoted in
 * Toman while the official tax authority prints Rial; currency normalization
 * is the responsibility of the extraction layer, not of this model.
 *
 * @property id            Stable identifier of the line (UUID recommended). Serves as
 *                         the primary key of the local database row.
 * @property name          Human readable description of the good or service.
 * @property quantity      Number of units. [Double] to accommodate fractional units
 *                         (e.g. 1.5 meters) that are common on Iranian service invoices.
 * @property unitPrice     Price of a single unit, before discount and tax.
 * @property discount      Total discount applied to this line. Defaults to `0.0`.
 * @property tax           Total tax (currently 9% VAT in Iran) applied to this line.
 *                         Defaults to `0.0`.
 * @property totalPrice    Final line price, i.e. `quantity * unitPrice - discount + tax`.
 * @property confidence    Extraction confidence reported by the OCR/AI pipeline,
 *                         constrained to the inclusive range `0.0..1.0`.
 * @property isSuspicious  `true` when the extraction engine flagged this line for human
 *                         review (low confidence, arithmetic mismatch, …).
 * @property productCode   The merchant's internal warehouse code for this good, resolved
 *                         from the product-mapping store after extraction. `null` means
 *                         unmapped: accounting exports fall back to the line sequence
 *                         number for the *کد کالا* column. Defaults to `null` so every
 *                         existing construction site and every stored record without the
 *                         field keeps compiling and decoding unchanged.
 */
data class InvoiceItem(
    val id: String,
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val totalPrice: Double,
    val confidence: Float = 0f,
    val isSuspicious: Boolean = false,
    val productCode: String? = null,
) {

    init {
        require(confidence in MIN_CONFIDENCE..MAX_CONFIDENCE) {
            "confidence must be in [$MIN_CONFIDENCE, $MAX_CONFIDENCE] but was $confidence"
        }
    }

    /**
     * Line total recomputed from its parts. Used to cross-check [totalPrice] during
     * validation: a divergence beyond the configured tolerance marks the item as
     * suspicious. This property is deliberately **not** used as the source of truth —
     * [totalPrice] preserves whatever the source document actually printed.
     */
    val expectedTotal: Double
        get() = quantity * unitPrice - discount + tax

    private companion object {
        const val MIN_CONFIDENCE = 0.0f
        const val MAX_CONFIDENCE = 1.0f
    }
}
