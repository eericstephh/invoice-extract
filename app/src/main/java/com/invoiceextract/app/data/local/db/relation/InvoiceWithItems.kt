package com.invoiceextract.app.data.local.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.invoiceextract.app.data.local.db.entity.InvoiceEntity
import com.invoiceextract.app.data.local.db.entity.InvoiceItemEntity

/**
 * 1-to-N join of one invoice header with its line items (Phase 8.1).
 *
 * Read with `@Transaction` so the header and its items come from a single atomic
 * snapshot — never a header from before an edit paired with items from after it.
 */
data class InvoiceWithItems(
    @Embedded
    val invoice: InvoiceEntity,
    @Relation(parentColumn = "id", entityColumn = "invoiceId")
    val items: List<InvoiceItemEntity>,
)
