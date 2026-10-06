package com.invoiceextract.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for one invoice line item (Phase 8.1).
 *
 * Owned by exactly one [InvoiceEntity]: the [ForeignKey] with
 * `onDelete = CASCADE` guarantees no orphaned lines survive an invoice delete,
 * and the index on [invoiceId] keeps the 1-to-N join and the
 * `DELETE ... WHERE invoiceId = :invoiceId` in the DAO off full-table scans.
 */
@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("invoiceId")],
)
data class InvoiceItemEntity(
    @PrimaryKey
    val id: String,
    val invoiceId: String,
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val discount: Double,
    val tax: Double,
    val totalPrice: Double,
    val confidence: Float,
    val isSuspicious: Boolean,
)
