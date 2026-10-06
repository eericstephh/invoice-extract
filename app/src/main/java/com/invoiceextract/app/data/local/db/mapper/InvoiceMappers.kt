package com.invoiceextract.app.data.local.db.mapper

import com.invoiceextract.app.data.local.db.entity.InvoiceEntity
import com.invoiceextract.app.data.local.db.entity.InvoiceItemEntity
import com.invoiceextract.app.data.local.db.relation.InvoiceWithItems
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.IssueSeverity
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus

/**
 * Domain ↔ Room mappers (Phase 8.1).
 *
 * Nullability passes straight through: the domain leaves unknown header fields
 * `null` so validation can report them, and Room stores the same `null` — the
 * mapper never invents placeholders. Enum and status decoding are total functions:
 * corrupt or blank rows degrade to `UNKNOWN` / `Valid` instead of throwing, so one
 * bad row can never crash history.
 */

/** Joined row → domain aggregate. Blank status payload decodes to [ValidationStatus.Valid]. */
fun InvoiceWithItems.toDomain(): Invoice =
    Invoice(
        id = invoice.id,
        invoiceNumber = invoice.invoiceNumber,
        date = invoice.date,
        sellerName = invoice.sellerName,
        sellerTaxId = invoice.sellerTaxId,
        buyerName = invoice.buyerName,
        buyerTaxId = invoice.buyerTaxId,
        items = items.map { it.toDomain() },
        subtotal = invoice.subtotal,
        totalTax = invoice.totalTax,
        totalDiscount = invoice.totalDiscount,
        grandTotal = invoice.grandTotal,
        currency = invoice.currency.toCurrencyType(),
        rawOcrText = invoice.rawOcrText,
        validationStatus = ValidationStatusCodec.decode(invoice.validationStatusJson),
        createdAtEpochMs = invoice.createdAtEpochMs,
    )

/** Domain aggregate header → Room row. Items map separately via [InvoiceItem.toEntity]. */
fun Invoice.toEntity(): InvoiceEntity =
    InvoiceEntity(
        id = id,
        invoiceNumber = invoiceNumber,
        date = date,
        sellerName = sellerName,
        sellerTaxId = sellerTaxId,
        buyerName = buyerName,
        buyerTaxId = buyerTaxId,
        subtotal = subtotal,
        totalTax = totalTax,
        totalDiscount = totalDiscount,
        grandTotal = grandTotal,
        currency = currency.name,
        rawOcrText = rawOcrText,
        validationStatusJson = ValidationStatusCodec.encode(validationStatus),
        createdAtEpochMs = createdAtEpochMs,
    )

/** Domain line → Room row, stamped with its owner's [invoiceId] foreign key. */
fun InvoiceItem.toEntity(invoiceId: String): InvoiceItemEntity =
    InvoiceItemEntity(
        id = id,
        invoiceId = invoiceId,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        discount = discount,
        tax = tax,
        totalPrice = totalPrice,
        confidence = confidence,
        isSuspicious = isSuspicious,
    )

private fun InvoiceItemEntity.toDomain(): InvoiceItem =
    InvoiceItem(
        id = id,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        discount = discount,
        tax = tax,
        totalPrice = totalPrice,
        // Clamp instead of crashing: a legacy row outside 0..1 must still open.
        confidence = confidence.coerceIn(0f, 1f),
        isSuspicious = isSuspicious,
    )

private fun String.toCurrencyType(): CurrencyType =
    runCatching { CurrencyType.valueOf(this) }.getOrDefault(CurrencyType.UNKNOWN)

/**
 * Lean `ValidationStatus` string codec — no JSON library.
 *
 * Wire format uses ASCII control separators that can never occur in Persian
 * OCR text or issue descriptions:
 * - `\u001E` (record separator) between issues,
 * - `\u001F` (unit separator) between `field`, `severity`, `description`.
 *
 * Layout: `"VALID"`, or `"WARNING" + RS + issue...`, or `"INVALID" + RS + issue...`.
 * [decode] is total: blank, unknown-prefix, or fully-corrupt payloads all yield
 * `Valid`; partially-corrupt issue entries are skipped, and an empty remainder
 * after a `WARNING`/`INVALID` prefix also yields `Valid` rather than constructing
 * a status that violates its own non-empty-list contract.
 */
internal object ValidationStatusCodec {

    fun encode(status: ValidationStatus): String =
        when (status) {
            ValidationStatus.Valid -> PREFIX_VALID
            is ValidationStatus.Warning -> PREFIX_WARNING + status.reasons.joinToString(
                separator = RECORD_SEPARATOR.toString(),
                prefix = RECORD_SEPARATOR.toString(),
            ) { it.encode() }
            is ValidationStatus.Invalid -> PREFIX_INVALID + status.criticalErrors.joinToString(
                separator = RECORD_SEPARATOR.toString(),
                prefix = RECORD_SEPARATOR.toString(),
            ) { it.encode() }
        }

    fun decode(raw: String): ValidationStatus {
        if (raw.isBlank()) return ValidationStatus.Valid
        return when {
            raw == PREFIX_VALID -> ValidationStatus.Valid
            raw.startsWith(PREFIX_WARNING) -> {
                val reasons = raw
                    .removePrefix(PREFIX_WARNING)
                    .split(RECORD_SEPARATOR)
                    .filter { it.isNotEmpty() }
                    .mapNotNull { it.decodeIssue(fallback = IssueSeverity.WARNING) }
                if (reasons.isEmpty()) ValidationStatus.Valid else ValidationStatus.Warning(reasons)
            }
            raw.startsWith(PREFIX_INVALID) -> {
                val errors = raw
                    .removePrefix(PREFIX_INVALID)
                    .split(RECORD_SEPARATOR)
                    .filter { it.isNotEmpty() }
                    .mapNotNull { it.decodeIssue(fallback = IssueSeverity.CRITICAL) }
                if (errors.isEmpty()) ValidationStatus.Valid else ValidationStatus.Invalid(errors)
            }
            else -> ValidationStatus.Valid
        }
    }

    private fun ValidationIssue.encode(): String =
        listOf(field, severity.name, description).joinToString(UNIT_SEPARATOR.toString())

    private fun String.decodeIssue(fallback: IssueSeverity): ValidationIssue? {
        val parts = split(UNIT_SEPARATOR)
        if (parts.size != 3) return null
        val (field, severityRaw, description) = parts
        val severity = runCatching { IssueSeverity.valueOf(severityRaw) }.getOrDefault(fallback)
        return ValidationIssue(field = field, description = description, severity = severity)
    }

    private const val PREFIX_VALID = "VALID"
    private const val PREFIX_WARNING = "WARNING"
    private const val PREFIX_INVALID = "INVALID"
    private const val RECORD_SEPARATOR = '\u001E'
    private const val UNIT_SEPARATOR = '\u001F'
}
