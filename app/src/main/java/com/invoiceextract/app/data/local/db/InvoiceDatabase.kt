package com.invoiceextract.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.invoiceextract.app.data.local.db.dao.InvoiceDao
import com.invoiceextract.app.data.local.db.entity.InvoiceEntity
import com.invoiceextract.app.data.local.db.entity.InvoiceItemEntity

/**
 * SQLite database for persisted invoices (Phase 8.1).
 *
 * Version 1 ships two tables — `invoices` and `invoice_items` — joined through
 * [com.invoiceextract.app.data.local.db.relation.InvoiceWithItems]. `exportSchema`
 * stays `false` until a migration is needed; v1 has no migration path because there
 * is no shipped data to migrate yet.
 */
@Database(
    entities = [InvoiceEntity::class, InvoiceItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class InvoiceDatabase : RoomDatabase() {

    abstract fun invoiceDao(): InvoiceDao
}
