package com.invoiceextract.app.presentation.review

import com.invoiceextract.domain.model.Invoice

/**
 * State of the Review & Edit screen (Phase 7.1).
 *
 * The screen is a pure function of this state. Every edit routes through
 * [ReviewViewModel], which re-derives totals, re-validates via the deterministic
 * domain validator, writes back to the session, and re-emits a fresh [Content].
 * Because the verdict lives inside [Invoice.validationStatus], the banner and the
 * numbers it was computed from can never drift apart.
 *
 * A sealed interface keeps "loading", "nothing to review" and "loaded" distinct at
 * the type level, so the UI's `when` is exhaustive and a half-initialized state is
 * unrepresentable. No Compose dependencies live here — this is pure business state.
 */
sealed interface ReviewUiState {

    /**
     * Initial transient state before the session has been read. The ViewModel
     * resolves this to [Empty] or [Content] synchronously in `init`, so the UI
     * only ever observes this for a single frame.
     */
    data object Loading : ReviewUiState

    /**
     * No invoice staged in [com.invoiceextract.app.data.session.InvoiceSessionHolder]:
     * a deep link straight into Review, or a back-navigation race that cleared it.
     */
    data object Empty : ReviewUiState

    /**
     * An invoice is loaded; [invoice] is its latest, fully re-validated snapshot.
     *
     * @property invoice The validated invoice to render and edit.
     * @property isSaving `true` while a Phase 8 persistence write is in flight.
     *   Reserved by Phase 7.1 for the save affordance; edits always emit with
     *   `isSaving = false`.
     */
    data class Content(
        val invoice: Invoice,
        val isSaving: Boolean = false,
    ) : ReviewUiState
}
