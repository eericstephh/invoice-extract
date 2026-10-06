package com.invoiceextract.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invoiceextract.app.data.session.InvoiceSessionHolder
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One-shot signals from [ReviewViewModel] to the screen, for things that should happen
 * once per action rather than being part of the recurring state.
 */
sealed interface ReviewEvent {

    /**
     * The user asked to persist. Carries the final, validated [invoice] so the screen can
     * hand it to Phase 8's Room layer without re-reading the session.
     */
    data class SaveReady(val invoice: Invoice) : ReviewEvent
}

/**
 * Owns the Review screen state and the edit → re-validate loop.
 *
 * The screen never mutates an [Invoice] itself. It hands a field change to this
 * ViewModel, which rebuilds the affected line, **re-derives every invoice total from the
 * items** (subtotal, total tax, total discount and grand total are *computed*, never
 * free-form fields, so the summary can never disagree with the lines it summarizes),
 * runs the [InvoiceValidator] and emits the result. The whole loop is pure and finishes
 * in microseconds over a handful of items, so it runs on the calling dispatcher and the
 * recomposition that follows is a single [ReviewUiState.Active] emission — which is why
 * validation lands on every keystroke with no perceptible lag.
 *
 * Each edit is also written back to [InvoiceSessionHolder], so the session always holds
 * the user's latest corrections and Phase 8 persistence reads one authoritative object.
 */
class ReviewViewModel(
    private val sessionHolder: InvoiceSessionHolder,
    private val invoiceValidator: InvoiceValidator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Empty)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReviewEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val events: SharedFlow<ReviewEvent> = _events.asSharedFlow()

    init {
        // Hydrate from the session: Home staged the invoice there before navigating.
        // Re-validated on arrival even though the pipeline already did so, because the
        // screen must never display a status it did not itself compute — and validate()
        // is idempotent, so this costs nothing.
        sessionHolder.getActiveInvoice()?.let { publish(it, persist = false) }
    }

    /**
     * Updates the invoice-level identity fields: seller, invoice number and date.
     *
     * Items and totals are untouched; only completeness rules can change here, so an edit
     * that fills a missing invoice number or date can flip the banner from Warning to
     * Valid on its own.
     */
    fun updateInvoiceMetadata(
        seller: String,
        invoiceNumber: String,
        date: String,
    ) {
        val current = currentInvoice() ?: return
        publish(
            current.copy(
                sellerName = seller.trim().ifBlank { null },
                invoiceNumber = invoiceNumber.trim().ifBlank { null },
                date = date.trim().ifBlank { null },
            ),
        )
    }

    /**
     * Rewrites one line item from its raw components.
     *
     * The caller supplies name, quantity, unit price, discount and tax; the line's
     * [com.invoiceextract.domain.model.InvoiceItem.totalPrice] is *derived* as
     * `quantity * unitPrice - discount + tax` rather than accepted as a separate input,
     * so a line can never carry a total that disagrees with its own parts. Invoice totals
     * are then re-derived from the lines.
     *
     * An out-of-range [index] is ignored: it can only come from a stale recomposition
     * racing an edit, and silently dropping it is correct — the state flow already holds
     * the authoritative list.
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
            totalPrice = quantity * unitPrice - discount + tax,
        )

        publish(current.copy(items = current.items.replaceAt(index, updatedItem)))
    }

    /**
     * Hand-off point for Phase 8 Room persistence. The invoice is already the validated,
     * user-corrected object in [InvoiceSessionHolder]; this signals the screen that the
     * record is final, carrying it along so the persistence layer has a seam to attach to.
     */
    fun saveInvoice() {
        val invoice = currentInvoice() ?: return
        viewModelScope.launch { _events.emit(ReviewEvent.SaveReady(invoice)) }
    }

    /**
     * Applies [invoice] to the screen: re-derives every invoice total from its lines,
     * validates the result, and emits it as the new [ReviewUiState.Active].
     *
     * @param persist When `true`, the result is written back to [InvoiceSessionHolder].
     *   Skipped during the initial hydrate, where the holder *is* the source and writing
     *   back would only cycle the same object through it.
     */
    private fun publish(invoice: Invoice, persist: Boolean = true) {
        val withTotals = invoice.copy(
            subtotal = invoice.items.sumOf { it.quantity * it.unitPrice },
            totalTax = invoice.items.sumOf { it.tax },
            totalDiscount = invoice.items.sumOf { it.discount },
            grandTotal = invoice.items.sumOf { it.totalPrice },
        )
        val validated = invoiceValidator.validate(withTotals)

        if (persist) sessionHolder.setActiveInvoice(validated)
        _uiState.value = ReviewUiState.Active(validated)
    }

    private fun currentInvoice(): Invoice? = (uiState.value as? ReviewUiState.Active)?.invoice

    /** A copy of this list with the element at [index] replaced by [element]. */
    private fun <T> List<T>.replaceAt(index: Int, element: T): List<T> =
        toMutableList().also { it[index] = element }.toList()
}
