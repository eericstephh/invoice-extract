package com.invoiceextract.app.data.repository

import com.invoiceextract.app.data.local.db.dao.InvoiceDao
import com.invoiceextract.app.data.local.db.mapper.toDomain
import com.invoiceextract.app.data.local.db.mapper.toEntity
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.repository.InvoiceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [InvoiceRepository] (Phase 8.2).
 *
 * Threading: writes and deletes shift to [ioDispatcher] ([Dispatchers.IO]) inside
 * `runCatching`, so callers get a `Result` and never block the main thread.
 * Reads are Room main-safe by construction — the DAO suspend/flow methods dispatch
 * internally — so `getInvoices`/`getInvoiceById` map directly without context hops.
 * The header+lines write stays atomic via [InvoiceDao.insertCompleteInvoice].
 */
class InvoiceRepositoryImpl(
    private val invoiceDao: InvoiceDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InvoiceRepository {

    override suspend fun saveInvoice(invoice: Invoice): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                invoiceDao.insertCompleteInvoice(
                    invoice = invoice.toEntity(),
                    items = invoice.items.map { it.toEntity(invoice.id) },
                )
            }
        }

    override fun getInvoices(): Flow<List<Invoice>> =
        invoiceDao.getAllInvoicesFlow().map { list -> list.map { it.toDomain() } }

    override suspend fun getInvoiceById(id: String): Invoice? =
        invoiceDao.getInvoiceById(id)?.toDomain()

    override suspend fun deleteInvoice(id: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching { invoiceDao.deleteInvoiceById(id) }
        }
}
