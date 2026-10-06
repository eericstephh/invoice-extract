package com.invoiceextract.app.data.extractor

import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.round

/**
 * Local, offline stand-in for the network-backed invoice extractor (Cloudflare /
 * Gemini), which is deliberately deferred to the final phase.
 *
 * Its purpose is to unblock everyone downstream: the use case, the UI states and the
 * Phase 6 validation engine all need a *well-formed* [Invoice] to develop against, and
 * they need it without a network, an API key or a waiting request. It returns a fixed
 * Persian fixture after a short simulated latency so that progress indicators and the
 * stage-by-stage UI can be exercised exactly as they will behave in production.
 *
 * **The fixture is content-independent by design.** It ignores the semantics of
 * [ocrText] apart from echoing it back into [Invoice.rawOcrText], so the UI, the
 * persistence layer and the validator are developed against data that is known, stable
 * and reviewable in source control. The real extractor will replace this class — and
 * only this class — without a single change to its callers.
 *
 * The invoice totals (subtotal, tax, discount, grand total) are *computed* from the
 * line items rather than hardcoded, so the fixture can never drift out of internal
 * consistency: the Phase 6 validator cross-checks each item's `totalPrice` against
 * `quantity * unitPrice - discount + tax` and the invoice totals against the sum of
 * the items, and this fixture passes those checks by construction.
 */
class StubInvoiceAiExtractor : InvoiceAiExtractor {

    /**
     * Simulates the round trip of a real extraction service.
     *
     * @return [Result.success] with a realistic Persian [Invoice] whose
     *   [Invoice.rawOcrText] is the verbatim [ocrText] passed in.
     */
    override suspend fun extractFromText(ocrText: String): Result<Invoice> {
        delay(SIMULATED_EXTRACTION_MS)
        return Result.success(buildInvoice(ocrText))
    }

    /**
     * Assembles the fixture invoice.
     */
    private fun buildInvoice(ocrText: String): Invoice {
        val items = buildList {
            // High-confidence line: the validator accepts this without remark.
            add(
                lineItem(
                    name = "هدفون بی‌سیم استودیویی",
                    quantity = 1.0,
                    unitPrice = 2_850_000.0,
                    discount = 150_000.0,
                    confidence = CONFIDENCE_HIGH,
                ),
            )
            add(
                lineItem(
                    name = "پاوربانک ۲۰٬۰۰۰ میلی‌آمپر",
                    quantity = 2.0,
                    unitPrice = 1_180_000.0,
                    confidence = CONFIDENCE_GOOD,
                ),
            )
            // Borderline-confidence line: deliberately flagged so the Phase 6 validation
            // engine and the review UI have a real suspicious item to surface.
            add(
                lineItem(
                    name = "کابل شارژ سریع USB-C",
                    quantity = 3.0,
                    unitPrice = 320_000.0,
                    discount = 60_000.0,
                    confidence = CONFIDENCE_BORDERLINE,
                    isSuspicious = true,
                ),
            )
        }

        return Invoice(
            id = UUID.randomUUID().toString(),
            invoiceNumber = INVOICE_NUMBER,
            date = JALALI_DATE,
            sellerName = SELLER_NAME,
            sellerTaxId = SELLER_TAX_ID,
            buyerName = BUYER_NAME,
            buyerTaxId = BUYER_TAX_ID,
            items = items,
            // Derived, never hardcoded, so the fixture stays arithmetically consistent.
            subtotal = items.sumOf { it.quantity * it.unitPrice },
            totalDiscount = items.sumOf { it.discount },
            totalTax = items.sumOf { it.tax },
            grandTotal = items.sumOf { it.totalPrice },
            currency = CurrencyType.TOMAN,
            rawOcrText = ocrText,
        )
    }

    /**
     * One line item, with its tax and total derived from the other fields. Iranian VAT
     * is 9%; the result is rounded to a whole Toman so the fixture prints cleanly.
     */
    private fun lineItem(
        name: String,
        quantity: Double,
        unitPrice: Double,
        discount: Double = 0.0,
        confidence: Float,
        isSuspicious: Boolean = false,
    ): InvoiceItem {
        val taxedBase = quantity * unitPrice - discount
        val tax = round(taxedBase * VAT_RATE)
        return InvoiceItem(
            id = UUID.randomUUID().toString(),
            name = name,
            quantity = quantity,
            unitPrice = unitPrice,
            discount = discount,
            tax = tax,
            totalPrice = taxedBase + tax,
            confidence = confidence,
            isSuspicious = isSuspicious,
        )
    }

    private companion object {
        /** Simulated extraction latency, long enough to make every stage visible. */
        private const val SIMULATED_EXTRACTION_MS = 700L

        /** Iranian value-added tax rate, applied per line item. */
        private const val VAT_RATE = 0.09

        /** Above the validation threshold: accepted without review. */
        private const val CONFIDENCE_HIGH = 0.95f

        /** Comfortably extracted, but not exemplary. */
        private const val CONFIDENCE_GOOD = 0.88f

        /** Beneath the suspicious threshold: feeds the Phase 6 validation engine. */
        private const val CONFIDENCE_BORDERLINE = 0.65f

        private const val SELLER_NAME = "شرکت دیجی‌کالا"
        private const val BUYER_NAME = "علی محمدی"

        /** Jalali (Solar Hijri) date, kept as printed on the document. */
        private const val JALALI_DATE = "1403/07/15"

        private const val INVOICE_NUMBER = "INV-1403-9821"

        /** Iranian tax identification numbers are 11 digits. */
        private const val SELLER_TAX_ID = "14008238774"
        private const val BUYER_TAX_ID = "14007654321"
    }
}
