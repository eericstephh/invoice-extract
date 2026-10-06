package com.invoiceextract.domain.repository

import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for invoices (Phase 8.2).
 *
 * Pure Kotlin: no Room, no Android — the `:app` layer provides the Room-backed
 * implementation. `Result` carries failures (SQLite, mapping) without throwing,
 * so callers handle persistence errors as values. The list flow is cold and
 * reactive: Room re-emits on every committed write.
 */
interface InvoiceRepository {

    suspend fun saveInvoice(invoice: Invoice): Result<Unit>

    fun getInvoices(): Flow<List<Invoice>>

    suspend fun getInvoiceById(id: String): Invoice?

    suspend fun deleteInvoice(id: String): Result<Unit>
}
