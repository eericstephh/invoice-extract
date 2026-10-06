package com.invoiceextract.app.ui.screens.home

import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase.ProcessingStage
import com.invoiceextract.domain.model.Invoice
import java.io.File

/**
 * A document the user picked and that the app has safely staged into its private
 * cache. All URI-level permissions are gone by the time this exists; downstream
 * pipeline stages work off [file] alone.
 *
 * @property file         The staged copy under cacheDir/invoices/.
 * @property originalName Display name as reported by the picker, e.g. "فاکتور ۱۲۳.pdf".
 * @property sizeFormatted Human readable size, e.g. "1.2 MB".
 * @property isPdf        Whether the document is a PDF (drives the preview icon).
 */
data class SelectedInvoiceFile(
    val file: File,
    val originalName: String,
    val sizeFormatted: String,
    val isPdf: Boolean,
)

/**
 * State of the Home screen.
 *
 * A sealed interface (rather than a set of nullable fields) makes "selected",
 * "processing", "success" and "error" mutually exclusive at the type level, so the
 * Compose `when` is exhaustive and no invalid combination is representable.
 *
 * [SelectedInvoiceFile] is threaded through [Selected], [Processing] and [Success] so
 * the staged document stays reachable for the whole lifetime of one scan: the UI can
 * render it under the progress indicator, the success card can hand it to the Phase 7
 * review flow, and a retry can go back to [Selected] without making the user re-pick
 * and re-copy the file. [Error] deliberately carries no file: once the pipeline has
 * failed the user is expected to start a fresh import.
 */
sealed interface HomeUiState {

    /** Nothing staged yet; the two import actions are shown. */
    data object Idle : HomeUiState

    /** A document is staged and ready for the extraction pipeline. */
    data class Selected(val fileInfo: SelectedInvoiceFile) : HomeUiState

    /** The pipeline is running over [fileInfo]; [stage] is the step currently in flight. */
    data class Processing(
        val fileInfo: SelectedInvoiceFile,
        val stage: ProcessingStage,
    ) : HomeUiState

    /** Extraction of [fileInfo] succeeded; [invoice] is ready to be reviewed and validated. */
    data class Success(
        val fileInfo: SelectedInvoiceFile,
        val invoice: Invoice,
    ) : HomeUiState

    /** Import failed; [message] is already localized and user-facing. */
    data class Error(val message: String) : HomeUiState
}
