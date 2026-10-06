package com.invoiceextract.app.ui.screens.review

import com.invoiceextract.domain.model.Invoice

/**
 * State of the Review screen.
 *
 * A single property carrying the whole [Invoice], rather than a flattened mirror of its
 * fields. Every edit routes through [ReviewViewModel], which re-derives the invoice's
 * totals, re-validates and re-emits a fresh [Invoice]; the Compose tree then recomposes
 * off that one instance. Because the verdict lives inside the model as
 * [Invoice.validationStatus], the banner and the numbers it was computed from can never
 * drift apart — they are one object, always consistent by construction.
 *
 * A sealed interface keeps the "nothing to review" case distinct from "loaded" at the
 * type level, so the screen's `when` is exhaustive and a half-initialized state is
 * unrepresentable.
 */
sealed interface ReviewUiState {

    /** An invoice is loaded; [invoice] is its latest, fully re-validated snapshot. */
    data class Active(val invoice: Invoice) : ReviewUiState

    /**
     * Reached with nothing staged: a deep link straight into Review, or a back-and-forth
     * that raced the session clear. The screen explains this and offers a way back
     * instead of rendering an empty, dead form.
     */
    data object Empty : ReviewUiState
}
