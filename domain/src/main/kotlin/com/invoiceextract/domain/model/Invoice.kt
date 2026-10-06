package com.invoiceextract.domain.model

import com.invoiceextract.domain.validation.ValidationStatus
import kotlin.math.roundToLong

/**
 * Currency used for every monetary field of the [Invoice].
 */
enum class CurrencyType {
    /** Day-to-day currency used by most Persian invoices (1 Toman = 10 Rial). */
    TOMAN,

    /** Official currency printed by the Iranian Tax Authority. */
    RIAL,

    /** US Dollar, for international receipts and service invoices. */
    USD,

    /** Euro, for international receipts and service invoices. */
    EUR,

    /** Tether (USDT), for crypto-denominated freelancer receipts. */
    USDT,

    /** Currency could not be determined reliably from the source document. */
    UNKNOWN,
}

/**
 * Display symbol of the currency: the Latin sign for foreign money, the
 * Persian name for local money, blank when unknown.
 */
val CurrencyType.symbol: String
    get() = when (this) {
        CurrencyType.TOMAN -> "تومان"
        CurrencyType.RIAL -> "ریال"
        CurrencyType.USD -> "$"
        CurrencyType.EUR -> "€"
        CurrencyType.USDT -> "₮"
        CurrencyType.UNKNOWN -> ""
    }

/** True for money that needs an exchange rate to read as Toman. */
val CurrencyType.isForeignCurrency: Boolean
    get() = this == CurrencyType.USD || this == CurrencyType.EUR || this == CurrencyType.USDT

/**
 * The currency a fresh manual entry opens with: USD in global (English) mode,
 * UNKNOWN otherwise.
 *
 * This is a *presentation* default, never an extraction verdict — the pipeline
 * still reports UNKNOWN when it cannot determine the currency, and no code
 * path rewrites a stored value through this. It exists so a user typing a new
 * invoice in the default English workspace starts from dollars instead of an
 * empty picker.
 */
fun defaultCurrencyFor(isEnglish: Boolean): CurrencyType =
    if (isEnglish) CurrencyType.USD else CurrencyType.UNKNOWN

/** True unless the invoice is [PaymentStatus.PAID]: the receivables predicate. */
val Invoice.isUnpaid: Boolean
    get() = paymentStatus != PaymentStatus.PAID

/**
 * Where an invoice stands in the agency's collection cycle.
 *
 * This is user-assigned bookkeeping — never extracted — like [Invoice.clientName]:
 * the extractor cannot know whether money has changed hands, so every record
 * starts [PENDING] until the user (or the follow-up flow) moves it.
 */
enum class PaymentStatus {
    /** The client has paid; excluded from every receivables aggregate. */
    PAID,

    /** Waiting for payment; counted as an open receivable. */
    PENDING,

    /** Past its [Invoice.dueDate] and still unpaid; counted and highlighted. */
    OVERDUE,
}

/**
 * A fully (or partially) extracted invoice.
 *
 * This is the aggregate root of the domain layer and the single structure shared by
 * the OCR pipeline, the AI extraction engine and the local database. Fields that the
 * extraction engine cannot determine are left `null` rather than filled with
 * placeholders, so that downstream validation can report them explicitly.
 *
 * @property id              Stable identifier (UUID recommended) and database primary key.
 * @property invoiceNumber   Official invoice number as printed, e.g. `"10234567890"`.
 * @property date            Date string **as printed on the document**, expected to be a
 *                           Persian/Solar (Jalali) date such as `"1403/05/20"`. It is kept
 *                           as a raw string on purpose: converting it to an epoch is a
 *                           lossy, locale-sensitive operation that belongs to a dedicated
 *                           `JalaliDate` utility, not to the domain model.
 * @property sellerName      Name of the seller / service provider.
 * @property sellerTaxId     Iranian tax identification number of the seller.
 * @property buyerName       Name of the buyer / customer.
 * @property buyerTaxId      Iranian tax identification number of the buyer.
 * @property clientName      The client (کارفرما) this invoice is billed to, as tagged
 *                           by the user in the desktop editor. Billing attribution for
 *                           agencies and freelancers — never extracted, only assigned.
 * @property projectName     The project or campaign (پروژه/کمپین) this invoice belongs
 *                           to. Same authorship contract as [clientName].
 * @property sellerNationalId National identifier of the seller as printed: the
 *                           10-digit individual code or the 11-digit legal-entity
 *                           ID. Kept verbatim (never re-digitized) so the checksum
 *                           audit reads exactly what the document says.
 * @property buyerNationalId  National identifier of the buyer, same contract.
 * @property items           Line items belonging to this invoice. Defaults to empty.
 * @property subtotal        Sum of line totals before tax and overall discount.
 * @property totalTax        Total tax (VAT) of the invoice.
 * @property totalDiscount   Total discount applied at the invoice level.
 * @property grandTotal      Final amount payable by the buyer.
 * @property currency        Currency of every monetary field. See [CurrencyType].
 * @property exchangeRate    Day rate into Toman for foreign invoices (e.g. `95000.0`
 *                           for USD→Toman). `null` means no rate was given; foreign
 *                           math then falls back to `1.0`. Unused for local money.
 * @property originalForeignAmount The document's own foreign total, kept verbatim
 *                           when the source printed one. `null` means the foreign
 *                           amount is [grandTotal] itself.
 * @property rawOcrText      Verbatim text produced by the OCR engine. Retained for
 *                           auditing, re-extraction and debugging at zero extra cost.
 * @property validationStatus Outcome of validating this invoice. Defaults to
 *                           [ValidationStatus.Valid] until a validator has run.
 * @property createdAtEpochMs Wall-clock creation time in milliseconds since the Unix
 *                           epoch. Defaults to the moment the instance is constructed.
 * @property sourceFilePath  Absolute path of the document this invoice was extracted
 *                           from, stamped by the desktop pipeline. Saved invoices carry
 *                           it so the split-view preview can reopen the origin file.
 *                           `null` means unknown (e.g. legacy records, synthetic test
 *                           fixtures) — the preview pane simply stays unavailable.
 * @property paymentStatus   Collection state of this invoice. Defaults to
 *                           [PaymentStatus.PENDING]; user-assigned, never extracted.
 * @property dueDate         Payment deadline as written or agreed (`"1403/06/31"` or
 *                           any Gregorian string). Kept as a raw string like [date]:
 *                           parsing it is a presentation concern, not a domain one.
 *                           `null` means no deadline was set.
 */
data class Invoice(
    val id: String,
    val invoiceNumber: String? = null,
    val date: String? = null,
    val sellerName: String? = null,
    val sellerTaxId: String? = null,
    val buyerName: String? = null,
    val buyerTaxId: String? = null,
    val sellerNationalId: String? = null,
    val buyerNationalId: String? = null,
    val clientName: String? = null,
    val projectName: String? = null,
    val items: List<InvoiceItem> = emptyList(),
    val subtotal: Double = 0.0,
    val totalTax: Double = 0.0,
    val totalDiscount: Double = 0.0,
    val grandTotal: Double = 0.0,
    val currency: CurrencyType = CurrencyType.UNKNOWN,
    val exchangeRate: Double? = null,
    val originalForeignAmount: Double? = null,
    val rawOcrText: String = "",
    val validationStatus: ValidationStatus = ValidationStatus.Valid,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val sourceFilePath: String? = null,
    val paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    val dueDate: String? = null,
) {
    /**
     * The invoice's worth in Toman — the single unit every ledger, statement and
     * dashboard aggregates in, so foreign and local money never mix:
     * - Toman (and unknown, which the pipeline already treats as local): [grandTotal].
     * - Rial: a tenth of [grandTotal] (1 Toman = 10 Rial).
     * - USD/EUR/USDT: the foreign amount ([originalForeignAmount], else [grandTotal])
     *   times [exchangeRate] (else `1.0`), rounded — Toman has no subunit.
     *
     * Non-finite inputs degrade to `0L` rather than leaking `NaN` into a sum.
     */
    val effectiveTomanTotal: Long
        get() = when {
            currency.isForeignCurrency -> {
                val foreign = originalForeignAmount ?: grandTotal
                val rate = exchangeRate ?: 1.0
                if (!foreign.isFinite() || !rate.isFinite()) 0L else (foreign * rate).roundToLong()
            }
            currency == CurrencyType.RIAL -> {
                if (!grandTotal.isFinite()) 0L else (grandTotal / RIAL_PER_TOMAN).toLong()
            }
            else -> {
                if (!grandTotal.isFinite()) 0L else grandTotal.toLong()
            }
        }
}

private const val RIAL_PER_TOMAN = 10.0
