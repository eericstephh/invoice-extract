package com.invoiceextract.app.data.batch.model

import com.invoiceextract.domain.model.Invoice
import java.io.File

/**
 * Lifecycle of one file inside a batch run (Phase 10.1).
 *
 * The state machine is strictly forward: `PENDING → PROCESSING → SUCCESS | FAILED`.
 * No state ever moves backwards, which is what makes a partially-finished batch
 * safe to render and, later, to retry — an item that is still `PROCESSING` is in
 * flight, an item that is `FAILED` is permanently done and will never be touched
 * again by the current run.
 */
enum class ItemStatus {
    /** Queued and not yet started. */
    PENDING,

    /** Currently inside the extraction pipeline. Exactly one item per batch run. */
    PROCESSING,

    /** Extracted, validated and persisted. Carries the resulting [BatchItemResult.invoice]. */
    SUCCESS,

    /** Terminated with an error; [BatchItemResult.errorMessage] explains why. */
    FAILED,
}

/**
 * The outcome slot for a single file in the batch.
 *
 * Deliberately holds *both* the input ([file], [isPdf]) and the output
 * ([invoice], [errorMessage]): a batch run is a long, user-visible operation, and the
 * UI needs to show a per-file row that pairs "what we asked for" with "what we got"
 * without joining two collections by index. Keeping the input on the result also makes
 * a retry trivial — the coordinator is handed this same pair again.
 *
 * `SUCCESS` always implies a non-null [invoice]; `FAILED` always implies a non-null
 * [errorMessage]. The two are mutually exclusive, and rather than encoding that with a
 * sealed type the fields stay nullable so a `copy(status = ...)` transition stays a
 * one-line update that the UI can diff field by field.
 *
 * @property file         The staged document under the app's private cache.
 * @property isPdf        `true` for PDF, `false` for an image.
 * @property status       Where this file sits in the run; see [ItemStatus].
 * @property invoice      The extracted invoice when [status] is [ItemStatus.SUCCESS].
 * @property errorMessage Persian, user-facing failure reason when [status] is
 *                        [ItemStatus.FAILED]. Empty while not failed.
 */
data class BatchItemResult(
    val file: File,
    val isPdf: Boolean,
    val status: ItemStatus = ItemStatus.PENDING,
    val invoice: Invoice? = null,
    val errorMessage: String? = null,
)

/**
 * The complete, self-contained progress of a batch run at one point in time (Phase 10.1).
 *
 * Emitted by [com.invoiceextract.app.data.batch.BatchProcessingCoordinator] on every
 * transition, this is the *only* object the UI ever sees for a batch — it carries the
 * counters, the currently-active file, the full per-item list and the finished flag
 * together, so a recomposition can never observe a counter that disagrees with the item
 * list it is rendering. Derived counts are recomputed from [items] on every emission
 * rather than accumulated by hand, which makes a stale or drifted counter impossible by
 * construction.
 *
 * @property totalCount      Size of the batch. Reaches its final value on the first
 *                           emission, so the UI can render "از N" immediately.
 * @property completedCount  Files that have terminated either way: `SUCCESS + FAILED`.
 * @property successCount    Files that extracted and persisted cleanly.
 * @property failureCount    Files that failed; displayed as a warning badge.
 * @property currentItemName Name of the file the pipeline is working on right now, `null`
 *                           before the run starts and after it ends.
 * @property items           Every file with its current [ItemStatus], in run order.
 * @property isFinished      `true` only on the final emission after the whole queue
 *                           drained. The UI uses it to swap the progress bar for a
 *                           summary and the export buttons.
 */
data class BatchProgressState(
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val currentItemName: String? = null,
    val items: List<BatchItemResult> = emptyList(),
    val isFinished: Boolean = false,
)
