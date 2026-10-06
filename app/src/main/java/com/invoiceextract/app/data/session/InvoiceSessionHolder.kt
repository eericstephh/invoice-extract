package com.invoiceextract.app.data.session

import com.invoiceextract.domain.model.Invoice

/**
 * Thread-safe, in-memory holder for the invoice the user is currently working on.
 *
 * The pipeline produces an [Invoice] on the Home screen and the Review screen consumes
 * it. This holder is the bridge between the two: Home stores the extraction result,
 * Review reads it, and neither screen has to know the other exists. The invoice is
 * deliberately **not** passed as a navigation argument — it carries the full OCR text
 * and a list of items that only grows as the user edits, and pushing that through a
 * `Bundle` would hit the `TransactionTooLargeException` ceiling on the first multi-page
 * document.
 *
 * Scope is the process: the class is registered as a Koin `single`, so one instance lives
 * as long as the app does. It holds at most one invoice at a time — storing a new one
 * displaces the previous, and [clear] drops it outright — which keeps the memory cost
 * bounded and predictable, and stops a stale invoice from outliving the user's interest
 * in it. Every edit made on the Review screen is written back here, so "the active
 * invoice" stays truthful and Phase 8's persistence layer can read the user's final
 * corrections from one place.
 *
 * **Thread safety.** Reading and writing a single object reference is atomic on the JVM,
 * and `@Volatile` guarantees the most recent write is visible from any coroutine, so
 * [getActiveInvoice] needs no lock to return a consistent value. The `synchronized`
 * blocks are defensive: they keep the class correct if it ever grows a read-modify-write
 * operation, and on the JVM's biased-locking fast path they cost essentially nothing.
 */
class InvoiceSessionHolder {

    @Volatile
    private var activeInvoice: Invoice? = null

    private val lock = Any()

    /**
     * Stores [invoice] as the one active invoice, replacing any previous one.
     */
    fun setActiveInvoice(invoice: Invoice) {
        synchronized(lock) { activeInvoice = invoice }
    }

    /**
     * Returns the active invoice, or `null` when the user has not produced one in this
     * process — a deep link into Review, or a back-navigation race.
     */
    fun getActiveInvoice(): Invoice? = synchronized(lock) { activeInvoice }

    /**
     * Atomically transforms the active invoice.
     *
     * Read-modify-write happens under [lock], so concurrent editors cannot interleave
     * and lose each other's changes. Returns the updated invoice, or `null` when there
     * was no active invoice to transform — in which case nothing is stored.
     */
    fun updateActiveInvoice(transform: (Invoice) -> Invoice): Invoice? =
        synchronized(lock) {
            val current = activeInvoice ?: return@synchronized null
            val updated = transform(current)
            activeInvoice = updated
            updated
        }

    /**
     * Drops the active invoice. After this, Review has nothing to show until Home stages
     * a fresh extraction.
     */
    fun clear() {
        synchronized(lock) { activeInvoice = null }
    }
}
