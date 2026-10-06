package com.invoiceextract.app.presentation.batch

import com.invoiceextract.app.data.batch.model.BatchProgressState
import java.io.File

/**
 * State of the Batch screen (Phase 10.2).
 *
 * A sealed interface rather than a bag of nullable fields, so "idle", "selected",
 * "processing" and "completed" are mutually exclusive *at the type level*: the
 * Compose `when` over this is exhaustive, and an in-between state like "selected but
 * already running" is unrepresentable instead of merely checked for.
 *
 * The staged [File]s ride along from [Selected] through [Processing] into [Completed]
 * for the whole lifetime of one run. The SAF read grant that produced them dies with
 * the activity, so once they are staged into the app cache the originals are
 * unreachable — losing them here would mean re-picking and re-copying the whole set.
 *
 * Progress is embedded rather than referenced: [Processing] and [Completed] each carry
 * the full [BatchProgressState], so the screen renders one source of truth and can
 * never observe a counter that disagrees with the item list under it.
 */
sealed interface BatchUiState {

    /** Nothing staged; the two multi-select actions are shown. */
    data object Idle : BatchUiState

    /**
     * A set of documents is staged and ready to run.
     *
     * @property files The staged copies, each paired with its `isPdf` flag — exactly the
     *   shape [com.invoiceextract.app.data.batch.BatchProcessingCoordinator.processBatch]
     *   consumes, so starting the run is a straight hand-off with no re-mapping.
     */
    data class Selected(val files: List<Pair<File, Boolean>>) : BatchUiState

    /**
     * A batch is in flight; [progress] is the coordinator's latest emission.
     *
     * Reached only while the processing job is active. Cancelling the job transitions
     * out of this state — never into a phantom [Processing] with a dead job behind it.
     */
    data class Processing(val progress: BatchProgressState) : BatchUiState

    /**
     * The queue has drained; the summary and the export actions are shown.
     *
     * @property progress   Final tally, carrying every item's outcome.
     * @property isExporting `true` while a consolidated export write is in flight, so the
     *   buttons can disable themselves and the export cannot be launched twice.
     */
    data class Completed(
        val progress: BatchProgressState,
        val isExporting: Boolean = false,
    ) : BatchUiState
}
