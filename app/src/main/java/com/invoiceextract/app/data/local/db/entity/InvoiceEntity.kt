package com.invoiceextract.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for the invoice header (Phase 8.1).
 *
 * One row per extracted document. Line items live in [InvoiceItemEntity] and are
 * joined via [com.invoiceextract.app.data.local.db.relation.InvoiceWithItems].
 *
 * Complex domain types are stored as lean primitives on purpose:
 * - [currency] keeps [com.invoiceextract.domain.model.CurrencyType.name]; unknown or
 *   blank values decode to `UNKNOWN` in the mapper.
 * - [validationStatusJson] keeps the verdict encoded by
 *   `ValidationStatusCodec` (no JSON library): blank decodes to `Valid`.
 *
 * All monetary fields are `Double` to mirror the domain exactly — no scaling, no
 * rounding at the persistence boundary.
 */
@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey
    val id: String,
    val invoiceNumber: String?,
    val date: String?,
    val sellerName: String?,
    val sellerTaxId: String?,
    val buyerName: String?,
    val buyerTaxId: String?,
    val subtotal: Double,
    val totalTax: Double,
    val totalDiscount: Double,
    val grandTotal: Double,
    val currency: String,
    val rawOcrText: String,
    val validationStatusJson: String,
    val createdAtEpochMs: Long,
)
