package com.invoiceextract.app.presentation.review

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invoiceextract.app.data.export.InvoiceExportManager
import com.invoiceextract.app.data.session.InvoiceSessionHolder
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One-shot signals from [ReviewViewModel] to the screen for export outcomes (Phase 9.2).
 *
 * Kept off [uiState] on purpose: a result is a transient confirmation, not part of the
 * screen's recurring content. Emitting it into state would either linger until the next
 * edit or force the UI to clear it, both of which flicker.
 */
sealed interface ExportEvent {

    /** The human-readable Persian result text; the screen shows it verbatim. */
    val message: String

    data class Success(override val message: String) : ExportEvent
    data class Failure(override val message: String) : ExportEvent
}

/**
 * Review & Edit business logic with live reactive re-validation (Phase 7.1),
 * atomic persistence (Phase 8.2) and SAF export (Phase 9.2).
 *
 * The screen never mutates an [Invoice] itself. It hands a field change to this
 * ViewModel, which rebuilds the affected state, **re-derives every invoice total from
 * the items**, runs the pure deterministic [InvoiceValidator], writes the result back
 * to [InvoiceSessionHolder], and emits it. The loop is synchronous and finishes in
 * microseconds over a handful of items, so validation lands on every keystroke with
 * no perceptible lag and no Compose dependency in this layer.
 *
 * Each edit is written back to the session, so the session always holds the user's
 * latest corrections and persistence reads one authoritative object. [saveInvoice]
 * atomically commits that object via [InvoiceRepository], clears the session so a
 * stale invoice can never be re-saved, and reports failures as one-shot
 * [saveErrors] without sticking an error into the recurring [uiState].
 *
 * Export hands the same object to [InvoiceExportManager]; the actual stream writing is
 * dispatched on `Dispatchers.IO` inside the manager, so neither [exportCsv] nor
 * [exportExcel] blocks the main thread.
 *
 * @property sessionHolder Process-scoped in-memory holder staging the active invoice.
 * @property invoiceValidator Pure Kotlin deterministic validator; stateless and safe
 *   to share.
 * @property invoiceRepository Room-backed persistence; IO dispatch lives inside it.
 * @property exportManager Zero-dependency CSV / Excel XML generator writing to a SAF Uri.
 */
class ReviewViewModel(
    private val sessionHolder: InvoiceSessionHolder,
    private val invoiceValidator: InvoiceValidator,
    private val invoiceRepository: InvoiceRepository,
    private val exportManager: InvoiceExportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    /**
     * One-shot save failures (e.g. SQLite errors). Kept out of [uiState] so an
     * error toast never sticks around as recurring state across recompositions.
     */
    private val _saveErrors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val saveErrors: SharedFlow<String> = _saveErrors.asSharedFlow()

    /**
     * One-shot export results. `replay = 0` so a result is delivered to whoever is
     * collecting at the moment and never replayed at a later recomposition.
     */
    private val _exportEvents = MutableSharedFlow<ExportEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    init {
        val active = sessionHolder.getActiveInvoice()
        _uiState.value = if (active == null) {
            ReviewUiState.Empty
        } else {
            // Re-validate on arrival even though the pipeline already did so: the screen
            // must never display a status it did not itself compute. validate() is a
            // pure idempotent function, so this costs nothing.
            ReviewUiState.Content(invoiceValidator.validate(active))
        }
    }

    /**
     * Updates the invoice-level identity fields: seller, invoice number and date.
     *
     * Items and totals are preserved; only completeness rules can change here, so an
     * edit that fills a missing invoice number or date can flip the banner from
     * Warning to Valid on its own. The result is re-validated, persisted to the
     * session, and emitted as the new [ReviewUiState.Content].
     */
    fun updateMetadata(sellerName: String, invoiceNumber: String, date: String) {
        val current = currentInvoice() ?: return
        val updated = current.copy(
            sellerName = sellerName.trim().ifBlank { null },
            invoiceNumber = invoiceNumber.trim().ifBlank { null },
            date = date.trim().ifBlank { null },
        )
        publish(updated)
    }

    /**
     * Rewrites one line item from its raw components with live re-validation.
     *
     * The line's `totalPrice` is *derived* as `quantity * unitPrice - discount + tax`
     * rather than accepted as input, so a line can never carry a total that disagrees
     * with its own parts. Invoice totals are then re-derived from the lines:
     * - `subtotal` = Σ `quantity * unitPrice`
     * - `totalTax` = Σ `tax`
     * - `totalDiscount` = Σ `discount`
     * - `grandTotal` = Σ `totalPrice`
     *
     * An out-of-range [index] is ignored: it can only come from a stale list position
     * racing an edit, and silently dropping it is correct — the state flow already
     * holds the authoritative list.
     */
    fun updateItem(
        index: Int,
        name: String,
        quantity: Double,
        unitPrice: Double,
        discount: Double,
        tax: Double,
    ) {
        val current = currentInvoice() ?: return
        if (index !in current.items.indices) return

        val updatedItem = current.items[index].copy(
            name = name.trim(),
            quantity = quantity,
            unitPrice = unitPrice,
            discount = discount,
            tax = tax,
            // Recomputed here so the line is internally consistent before it is summed.
            totalPrice = (quantity * unitPrice) - discount + tax,
        )
        val updatedItems = current.items.toMutableList().also { it[index] = updatedItem }

        publish(
            current.copy(
                items = updatedItems,
                subtotal = updatedItems.sumOf { it.quantity * it.unitPrice },
                totalTax = updatedItems.sumOf { it.tax },
                totalDiscount = updatedItems.sumOf { it.discount },
                grandTotal = updatedItems.sumOf { it.totalPrice },
            ),
        )
    }

    /**
     * Validates [invoice], persists it to the session, and emits it as
     * [ReviewUiState.Content].
     */
    private fun publish(invoice: Invoice) {
        val validated = invoiceValidator.validate(invoice)
        sessionHolder.setActiveInvoice(validated)
        _uiState.value = ReviewUiState.Content(invoice = validated)
    }

    /**
     * Atomically persists the reviewed invoice and hands control back to the caller
     * (Phase 8.2).
     *
     * The invoice is read straight from [uiState] — it already carries the user's
     * corrections and the latest validation verdict, so nothing is re-derived here.
     * The write goes through [InvoiceRepository.saveInvoice], which wraps the
     * header+lines insert in one Room transaction on `Dispatchers.IO`.
     *
     * Success clears [InvoiceSessionHolder] so the just-saved invoice can never be
     * re-saved by a stale entry point, then invokes [onSuccess]. [viewModelScope] is
     * main-dispatched, so [onSuccess] runs on the main thread and may navigate
     * directly. Failure flips `isSaving` back off and emits a one-shot message via
     * [saveErrors]; the invoice stays on screen, un-cleared, so the user can retry.
     */
    fun saveInvoice(onSuccess: () -> Unit) {
        val content = _uiState.value as? ReviewUiState.Content ?: return
        if (content.isSaving) return

        _uiState.value = content.copy(isSaving = true)

        viewModelScope.launch {
            val result = invoiceRepository.saveInvoice(content.invoice)

            result.onSuccess {
                sessionHolder.clear()
                onSuccess()
            }.onFailure { error ->
                _uiState.value = content.copy(isSaving = false)
                _saveErrors.tryEmit(error.message ?: SAVE_ERROR_GENERIC)
            }
        }
    }

    private fun currentInvoice(): Invoice? =
        (_uiState.value as? ReviewUiState.Content)?.invoice

    /**
     * Exports the reviewed invoice as Persian-compatible CSV into [uri], a location the
     * user just picked through SAF.
     *
     * The invoice comes from [uiState] — the same object the screen is rendering, so an
     * export can never diverge from what the user is looking at. [InvoiceExportManager]
     * does the writing on `Dispatchers.IO`; this only launches the coroutine and maps
     * the outcome to a one-shot [ExportEvent].
     */
    fun exportCsv(uri: Uri) {
        val invoice = currentInvoice() ?: return
        viewModelScope.launch {
            exportManager.exportCsv(uri, invoice)
                .onSuccess { _exportEvents.emit(ExportEvent.Success(EXPORT_CSV_SUCCESS)) }
                .onFailure { _exportEvents.emit(ExportEvent.Failure(EXPORT_CSV_FAILURE)) }
        }
    }

    /**
     * Exports the reviewed invoice as an Excel XML Spreadsheet into [uri]. Same contract
     * as [exportCsv]; the generator emits RTL columns natively.
     */
    fun exportExcel(uri: Uri) {
        val invoice = currentInvoice() ?: return
        viewModelScope.launch {
            exportManager.exportExcel(uri, invoice)
                .onSuccess { _exportEvents.emit(ExportEvent.Success(EXPORT_EXCEL_SUCCESS)) }
                .onFailure { _exportEvents.emit(ExportEvent.Failure(EXPORT_EXCEL_FAILURE)) }
        }
    }

    /**
     * A filesystem-safe default name for the SAF "create file" dialog.
     *
     * Persian invoice numbers can carry digits, slashes and spaces; anything outside a
     * conservative safe set collapses to `_` so the suggested name is always a valid
     * filename across every storage provider. Falls back to `unnamed` when the invoice
     * has no number.
     */
    fun getSuggestedFileName(extension: String): String {
        val invoice = currentInvoice()
        val safeNumber = (invoice?.invoiceNumber ?: FALLBACK_NAME)
            .replace(UNSAFE_FILE_CHARS, "_")
        return "invoice_$safeNumber.$extension"
    }

    private companion object {
        const val SAVE_ERROR_GENERIC = "خطا در ذخیره‌سازی فاکتور"
        const val EXPORT_CSV_SUCCESS = "فایل CSV با موفقیت ذخیره شد"
        const val EXPORT_CSV_FAILURE = "خطا در ایجاد فایل CSV"
        const val EXPORT_EXCEL_SUCCESS = "فایل اکسل با موفقیت ذخیره شد"
        const val EXPORT_EXCEL_FAILURE = "خطا در ایجاد فایل اکسل"
        const val FALLBACK_NAME = "unnamed"
        val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9_-]")
    }
}
