package com.invoiceextract.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.invoiceextract.app.data.local.db.entity.InvoiceEntity
import com.invoiceextract.app.data.local.db.entity.InvoiceItemEntity
import com.invoiceextract.app.data.local.db.relation.InvoiceWithItems
import kotlinx.coroutines.flow.Flow

/**
 * Persistence gateway for invoices and their line items (Phase 8.1).
 *
 * All multi-statement writes go through [insertCompleteInvoice], which runs in a
 * single SQLite transaction: upsert the header, wipe the previous lines for that
 * invoice id, then insert the fresh lines. A crash mid-write therefore leaves the
 * previous complete invoice — never a new header with stale lines or vice versa.
 *
 * Reads that join the 1-to-N relation are `@Transaction` so Room materializes the
 * header and its items from one consistent snapshot.
 */
@Dao
interface InvoiceDao {

    @Upsert
    suspend fun upsertInvoice(invoice: InvoiceEntity)

    @Upsert
    suspend fun upsertItems(items: List<InvoiceItemEntity>)

    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteItemsForInvoice(invoiceId: String)

    /**
     * Atomically replaces the complete invoice aggregate.
     *
     * Delete-then-insert (rather than diffing lines) is deliberate: line ids are
     * regenerated on re-extraction, so matching old to new rows would be guessing.
     * Wiping by [invoiceId] first keeps edited invoices from accumulating ghost lines.
     */
    @Transaction
    suspend fun insertCompleteInvoice(invoice: InvoiceEntity, items: List<InvoiceItemEntity>) {
        upsertInvoice(invoice)
        deleteItemsForInvoice(invoice.id)
        if (items.isNotEmpty()) {
            upsertItems(items)
        }
    }

    @Transaction
    @Query("SELECT * FROM invoices ORDER BY createdAtEpochMs DESC")
    fun getAllInvoicesFlow(): Flow<List<InvoiceWithItems>>

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun getInvoiceById(id: String): InvoiceWithItems?

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteInvoiceById(id: String)
}
