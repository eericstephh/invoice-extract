package com.invoiceextract.desktop.presentation

import com.invoiceextract.domain.model.Invoice

/**
 * The one state the desktop window renders, mirroring the mobile app's screen states so
 * the two fronts read the same way.
 *
 * Sealed on purpose: `when` over the four states is exhaustive at compile time, so adding
 * a state is a build error everywhere it is rendered until every branch is covered. A
 * non-exhaustive switch here is what renders a blank screen instead of an error message.
 *
 * Deliberately **not** combined into a single data class with a nullable invoice plus
 * loading flags: that representation has legal but nonsensical combinations (an invoice
 * *and* an error, processing *and* ready) that this hierarchy makes unreachable.
 */
sealed interface DesktopUiState {

    /**
     * Nothing has been dropped yet. The window shows the drop zone and waits; there is no
     * invoice to display and nothing to export.
     */
    data object Idle : DesktopUiState

    /**
     * A document is being turned into an invoice right now.
     *
     * @property fileName Shown verbatim in the progress message so the user can see the
     *   file they dropped is the one being processed — important when they drop a file and
     *   the model takes tens of seconds to answer.
     */
    data class Processing(val fileName: String) : DesktopUiState

    /**
     * An invoice is on screen, ready to be edited, validated and exported.
     *
     * @property invoice The invoice as last extracted or edited. Its
     *   [Invoice.validationStatus] is always fresh: every edit re-runs the validator
     *   before this state is emitted, so the banner and the totals can never disagree with
     *   the table the user is looking at.
     * @property isDirty `true` when the invoice has been edited since it was extracted or
     *   since the last successful export. Used to stop the user closing a modified invoice
     *   without having saved it, the same affordance a document editor has.
     * @property isSaved `true` when the invoice on screen is persisted in the local store.
     *   Flipped by the save action only, so the window can tell "kept" from "seen": an
     *   edited invoice turns dirty *and* unsaved, and loading one back from history lands
     *   saved and clean.
     */
    data class Ready(
        val invoice: Invoice,
        val isDirty: Boolean = false,
        val isSaved: Boolean = false,
    ) : DesktopUiState

    /**
     * The pipeline failed and there is no invoice to show.
     *
     * @property message A Persian sentence that can be shown verbatim to the user. It comes
     *   straight from the failing stage's typed exception, so it already says whether the
     *   daemon is down, the model is missing or the PDF was scanned.
     */
    data class Error(val message: String) : DesktopUiState
}
