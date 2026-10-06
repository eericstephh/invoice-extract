package com.invoiceextract.desktop.data.batch

import com.invoiceextract.domain.model.Invoice
import java.io.File

/**
 * Lifecycle of one file inside a batch run.
 *
 * Four states, in order, no shortcuts: a file waits ([PENDING]), is being extracted
 * ([PROCESSING]), and lands in exactly one terminal state — [SUCCESS] with its invoice
 * attached, or [FAILED] with the localized reason. The panel switches on this enum, so
 * adding a state is a compile error in the UI until it gets a badge.
 */
enum class BatchItemStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
}

/**
 * One dropped file and everything the batch run learns about it.
 *
 * @property file The invoice document as dropped; never mutated, only read.
 * @property status Where this file stands in its lifecycle. See [BatchItemStatus].
 * @property invoice The extracted invoice, present only on [BatchItemStatus.SUCCESS].
 * @property errorMessage The localized failure reason, present only on
 *   [BatchItemStatus.FAILED]. Comes straight from the pipeline's typed exceptions, so it
 *   is a Persian sentence the panel can show verbatim.
 */
data class DesktopBatchItem(
    val file: File,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val invoice: Invoice? = null,
    val errorMessage: String? = null,
)

/**
 * A snapshot of a running — or finished — batch.
 *
 * Emitted by the coordinator after every state change, so the panel recomposes on each
 * file's transition instead of polling. `completed` always equals
 * `successCount + failureCount`: there is no third terminal state for a file to hide in.
 *
 * @property total Files accepted into the batch. Fixed at the first emission.
 * @property completed Files in a terminal state so far.
 * @property successCount Files that extracted and persisted.
 * @property failureCount Files that failed at any stage, including the store write.
 * @property items Per-file detail, in drop order.
 * @property isFinished `true` once every file is terminal. Only then are the
 *   consolidated exports offered.
 */
data class DesktopBatchProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val items: List<DesktopBatchItem> = emptyList(),
    val isFinished: Boolean = false,
)
