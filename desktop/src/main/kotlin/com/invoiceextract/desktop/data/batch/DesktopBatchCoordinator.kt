package com.invoiceextract.desktop.data.batch

import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.repository.InvoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a dropped set of invoice files through the extraction pipeline, one after
 * another, and reports the run as a stream of [DesktopBatchProgress] snapshots.
 *
 * **Sequential, never parallel.** Local LLM inference saturates the machine on its own;
 * two concurrent extractions would thrash the CPU, starve the render thread and roughly
 * halve neither's throughput. Files are processed strictly in drop order on
 * [Dispatchers.Default] — the use case already moves its own blocking work onto IO —
 * so the window stays responsive while the queue drains.
 *
 * **Fault isolation.** Each file is its own transaction: a corrupt PDF, a missing model
 * or a failed store write marks that one item [BatchItemStatus.FAILED] with the
 * localized reason and the loop moves on. A single bad file can never abort the batch,
 * and it can never poison a sibling's result — items only ever transition forward.
 *
 * **Persistence.** A successful extraction is saved to the [InvoiceRepository]
 * immediately, before the next file starts. The batch and the history archive therefore
 * agree at every instant: killing the app mid-batch loses only the files not yet
 * reached, never the ones already reported as successful. A save failure is itself a
 * file failure, reported with the store's message.
 *
 * **Cancellation.** [CancellationException] is re-thrown at every catch site instead of
 * being folded into a FAILED item, and the flow is cancellable between emissions — so
 * closing the window or pressing cancel unwinds the run instead of filing one more
 * failure the user never asked about.
 *
 * @param processInvoiceUseCase The single-file extract → structure → validate pipeline.
 * @param invoiceRepository The persistent history each success is written to.
 */
class DesktopBatchCoordinator(
    private val processInvoiceUseCase: DesktopProcessInvoiceUseCase,
    private val invoiceRepository: InvoiceRepository,
) {

    /**
     * Processes [files] in order, emitting a snapshot after every state change.
     *
     * The first emission is the queue as accepted (everything
     * [BatchItemStatus.PENDING]); the last has [DesktopBatchProgress.isFinished] set.
     * In between, each file produces exactly two emissions — [BatchItemStatus.PROCESSING]
     * when it starts, its terminal state when it lands — which is what drives the
     * panel's per-row transitions without polling.
     *
     * Cold: nothing runs until collected, and collecting twice runs the batch twice.
     */
    fun processBatch(files: List<File>): Flow<DesktopBatchProgress> = flow {
        var progress = DesktopBatchProgress(
            total = files.size,
            items = files.map { DesktopBatchItem(file = it) },
        )
        emit(progress)

        files.forEachIndexed { index, file ->
            progress = progress.withItem(index) { it.copy(status = BatchItemStatus.PROCESSING) }
            emit(progress)

            progress = progress.withItem(index) { processOne(file) }
            emit(progress)
        }

        emit(progress.copy(isFinished = true))
    }.flowOn(Dispatchers.Default)

    /**
     * Runs one file end to end and returns its terminal item.
     *
     * The use case already returns [Result], so the only thing that can *throw* out of
     * it is cancellation (or a contract-breaking bug, which is correctly fatal-adjacent
     * here: it becomes a FAILED item with its message rather than killing the batch).
     * Cancellation unwinds; everything else is isolated to this file.
     */
    private suspend fun processOne(file: File): DesktopBatchItem {
        val outcome: Result<Invoice> = try {
            processInvoiceUseCase.processInvoice(file)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            Result.failure(cause)
        }

        return outcome.fold(
            onSuccess = { invoice -> persistSuccess(file, invoice) },
            onFailure = { cause ->
                DesktopBatchItem(
                    file = file,
                    status = BatchItemStatus.FAILED,
                    errorMessage = cause.message ?: cause.javaClass.simpleName,
                )
            },
        )
    }

    /**
     * Writes a successful extraction to the store before the next file starts.
     *
     * A store failure demotes the item to FAILED — reporting SUCCESS for an invoice that
     * is not actually kept would lie to both the panel and the later ledger export,
     * which only ever sees persisted successes.
     */
    private suspend fun persistSuccess(file: File, invoice: Invoice): DesktopBatchItem {
        return try {
            invoiceRepository.saveInvoice(invoice).fold(
                onSuccess = {
                    DesktopBatchItem(
                        file = file,
                        status = BatchItemStatus.SUCCESS,
                        invoice = invoice,
                    )
                },
                onFailure = { cause ->
                    DesktopBatchItem(
                        file = file,
                        status = BatchItemStatus.FAILED,
                        errorMessage = cause.message ?: cause.javaClass.simpleName,
                    )
                },
            )
        } catch (cause: CancellationException) {
            throw cause
        }
    }

    /**
     * Rebuilds [DesktopBatchProgress] with item [index] replaced by [transform]'s result,
     * recounting the terminal tallies from the new list so the counts can never drift
     * from the rows they summarize.
     */
    private suspend fun DesktopBatchProgress.withItem(
        index: Int,
        transform: suspend (DesktopBatchItem) -> DesktopBatchItem,
    ): DesktopBatchProgress {
        val updated = items.mapIndexed { i, item ->
            if (i == index) transform(item) else item
        }
        val successes = updated.count { it.status == BatchItemStatus.SUCCESS }
        val failures = updated.count { it.status == BatchItemStatus.FAILED }
        return copy(
            items = updated,
            successCount = successes,
            failureCount = failures,
            completed = successes + failures,
        )
    }
}
