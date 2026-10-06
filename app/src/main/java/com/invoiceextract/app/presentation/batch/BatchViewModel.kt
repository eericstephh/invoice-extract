package com.invoiceextract.app.presentation.batch

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.invoiceextract.app.data.batch.BatchExportManager
import com.invoiceextract.app.data.batch.BatchProcessingCoordinator
import com.invoiceextract.app.data.batch.model.BatchProgressState
import com.invoiceextract.app.data.batch.model.ItemStatus
import com.invoiceextract.app.data.file.FileMetadataHelper
import com.invoiceextract.app.presentation.review.ExportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Business logic of the Batch screen (Phase 10.2).
 *
 * Owns the lifecycle of one batch run: it stages the picked documents into the app cache
 * before the SAF grants can lapse, feeds the staged pairs to
 * [BatchProcessingCoordinator], and translates the coordinator's progress emissions into
 * [BatchUiState]. Export is a thin wrapper over [BatchExportManager] that reports the
 * outcome as a one-shot [ExportEvent].
 *
 * ### Threading and cancellation
 *
 * [BatchProcessingCoordinator.processBatch] is a cold flow that is strict about
 * structured concurrency: when the collecting scope is cancelled it rethrows the
 * [CancellationException] instead of letting the cancellation be swallowed into a
 * per-item failure. That is the correct behaviour — cancellation is the user hitting
 * "لغو عملیات", not a broken document — but it means `collect` throws, so the collection
 * is guarded: a cancellation lands in the catch and is *not* treated as a batch
 * failure, the run is simply reported as stopped at whatever item it reached.
 *
 * The [processingJob] is held so a second tap on the start action cancels the first run
 * instead of starting a competing one over the same files.
 *
 * ### Temp files
 *
 * Every staged file lives under the app's private cache. [reset] deletes them and drops
 * the references, so nothing the user imported outlives the batch screen — and
 * [onCleared] sweeps them too, which covers the case where the process dies while the
 * user is mid-batch.
 *
 * @property application Needed for [FileMetadataHelper], which resolves the content
 *   resolver and the cache directory.
 */
class BatchViewModel(
    application: Application,
    private val coordinator: BatchProcessingCoordinator,
    private val exportManager: BatchExportManager,
    private val fileHelper: FileMetadataHelper,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<BatchUiState>(BatchUiState.Idle)
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    /**
     * One-shot export results. Kept off [uiState] on purpose: a success message is a
     * transient confirmation, and putting it in state would make it linger across
     * recompositions or force the UI to clear it. `replay = 0` so a result is delivered
     * to whoever is collecting *now* and never replayed on a later recomposition.
     */
    private val _exportEvents = MutableSharedFlow<ExportEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    /**
     * The in-flight batch run, if any. Held so [startProcessing] can cancel a live run
     * before starting another, and so [cancelProcessing] can stop the whole queue.
     */
    private var processingJob: Job? = null

    /**
     * Staged documents behind the current screen state, kept independently of the state
     * itself. [reset] and [onCleared] delete them from *any* state, including
     * [BatchUiState.Processing] and [BatchUiState.Completed], which do not carry the list
     * in a form [reset] can iterate.
     *
     * Written from the IO dispatcher during staging and read on the main thread, hence
     * [Volatile].
     */
    @Volatile
    private var stagedFiles: List<Pair<File, Boolean>> = emptyList()

    /**
     * Stages the picked documents into the app cache and flips to [BatchUiState.Selected].
     *
     * The pickers grant only a transient read permission that dies with the activity, so
     * every Uri is copied into the private cache *now*, while the grant is still hot,
     * exactly as [FileMetadataHelper] does for the single-file flow. The import runs on
     * `Dispatchers.IO` inside the helper, so this call never blocks the main thread.
     *
     * An empty selection is a no-op rather than an error: the picker returns an empty
     * list when the user backs out of the sheet, and showing an error for that would be
     * scolding the user for changing their mind. A selection in which *some* documents
     * failed to import is still accepted — the successes are staged and the failures are
     * reported, so one oversized PDF never blocks the rest of the batch.
     *
     * @param uris The documents the system picker handed back; may be empty.
     */
    fun onUrisSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val staged = uris.mapNotNull { uri ->
                val result = fileHelper.importInvoice(uri)
                if (result.isFailure) {
                    val cause = result.exceptionOrNull()
                    android.util.Log.e(TAG, "Could not stage a picked document", cause)
                }
                result.getOrNull()?.let { staged -> staged.file to staged.isPdf }
            }

            if (staged.isEmpty()) {
                _exportEvents.emit(ExportEvent.Failure(MESSAGE_IMPORT_FAILED))
                return@launch
            }

            stagedFiles = staged
            _uiState.value = BatchUiState.Selected(staged)
        }
    }

    /**
     * Starts the batch over the staged documents.
     *
     * A still-running job is cancelled first: the queue is sequential and the files are
     * the same, so two concurrent runs over them would double-process the same document
     * and race the Room writes.
     *
     * The state flips to [BatchUiState.Processing] *synchronously*, before the coroutine
     * is started, so a double tap can never launch two overlapping runs: by the time the
     * second tap arrives the state is no longer [BatchUiState.Selected].
     *
     * A state without staged files is a programming error rather than a user path, so it
     * throws here instead of silently doing nothing — the screen never calls this from
     * any state but [BatchUiState.Selected].
     */
    fun startProcessing() {
        val files = stagedFiles
        check(files.isNotEmpty()) { "startProcessing called without staged files" }

        processingJob?.cancel()

        _uiState.value = BatchUiState.Processing(BatchProgressState(totalCount = files.size))

        processingJob = viewModelScope.launch {
            try {
                coordinator.processBatch(files).collect { progress ->
                    _uiState.value = if (progress.isFinished) {
                        BatchUiState.Completed(progress)
                    } else {
                        BatchUiState.Processing(progress)
                    }
                }
            } catch (cancel: CancellationException) {
                // The user cancelled, or the ViewModel was cleared. Not a batch failure:
                // the coordinator already left every un-processed item at PENDING, and
                // reporting the run as "completed" would claim items it never tried. The
                // last emitted [BatchUiState.Processing] stays on screen with its own
                // counters, which is an honest picture of where the run stopped.
                throw cancel
            }
        }
    }

    /**
     * Stops the in-flight batch.
     *
     * Cancelling the job propagates into the coordinator's flow, which stops the queue at
     * the current item and frees that file's bitmaps. The state is left as the last
     * emitted [BatchUiState.Processing]: its counters already reflect every item the run
     * actually finished, so the user sees precisely how far it got rather than a fake
     * summary. Nothing further is needed — the cancelled job does not emit again.
     */
    fun cancelProcessing() {
        processingJob?.cancel()
    }

    /**
     * Writes a consolidated CSV of every successfully extracted invoice into [uri].
     *
     * The invoices are collected from the completed state rather than re-read from the
     * database: the screen is showing exactly these invoices, so the export can never
     * diverge from what the user is looking at. An empty list is refused with a message
     * instead of writing a header-only file, which would look like a broken export.
     */
    fun exportBatchCsv(uri: Uri) {
        val invoices = successfulInvoices() ?: return
        if (invoices.isEmpty()) {
            viewModelScope.launch { _exportEvents.emit(ExportEvent.Failure(MESSAGE_NOTHING_TO_EXPORT)) }
            return
        }

        markExporting()

        viewModelScope.launch {
            exportManager.exportBatchCsv(uri, invoices)
                .onSuccess { _exportEvents.emit(ExportEvent.Success(MESSAGE_CSV_SUCCESS)) }
                .onFailure { _exportEvents.emit(ExportEvent.Failure(MESSAGE_CSV_FAILURE)) }
                .also { markExportingDone() }
        }
    }

    /** The SpreadsheetML counterpart of [exportBatchCsv]; same contract. */
    fun exportBatchExcel(uri: Uri) {
        val invoices = successfulInvoices() ?: return
        if (invoices.isEmpty()) {
            viewModelScope.launch { _exportEvents.emit(ExportEvent.Failure(MESSAGE_NOTHING_TO_EXPORT)) }
            return
        }

        markExporting()

        viewModelScope.launch {
            exportManager.exportBatchExcel(uri, invoices)
                .onSuccess { _exportEvents.emit(ExportEvent.Success(MESSAGE_EXCEL_SUCCESS)) }
                .onFailure { _exportEvents.emit(ExportEvent.Failure(MESSAGE_EXCEL_FAILURE)) }
                .also { markExportingDone() }
        }
    }

    /**
     * Deletes the staged files and returns to [BatchUiState.Idle].
     *
     * The "start over" path: every imported document is removed from the cache, the
     * references are dropped, and the screen is ready for a fresh selection. Safe to call
     * from any state, including mid-run — the processing job is cancelled first, so the
     * coordinator never reads a file that has just been deleted from under it.
     */
    fun reset() {
        processingJob?.cancel()
        deleteStagedFiles()
        stagedFiles = emptyList()
        _uiState.value = BatchUiState.Idle
    }

    /**
     * The invoices worth exporting: those that the batch actually extracted and persisted.
     *
     * Returns `null` when the screen is not in a completed state, which is the guard that
     * makes the export entry points safe to call from a stale recomposition.
     */
    private fun successfulInvoices(): List<com.invoiceextract.domain.model.Invoice>? {
        val completed = _uiState.value as? BatchUiState.Completed ?: return null
        return completed.progress.items
            .filter { it.status == ItemStatus.SUCCESS }
            .mapNotNull { it.invoice }
    }

    /** Flips [BatchUiState.Completed.isExporting] on, disabling the export buttons. */
    private fun markExporting() {
        val completed = _uiState.value as? BatchUiState.Completed ?: return
        _uiState.value = completed.copy(isExporting = true)
    }

    /** Flips [BatchUiState.Completed.isExporting] back off. */
    private fun markExportingDone() {
        val completed = _uiState.value as? BatchUiState.Completed ?: return
        _uiState.value = completed.copy(isExporting = false)
    }

    /** Deletes every staged file if any are still around, then forgets them. */
    private fun deleteStagedFiles() {
        stagedFiles.forEach { (file, _) -> runCatching { file.delete() } }
    }

    override fun onCleared() {
        super.onCleared()
        // Never leave orphaned cache files behind if the user navigates away — including
        // mid-batch. The job is cancelled by viewModelScope teardown automatically; the
        // files are this ViewModel's responsibility.
        deleteStagedFiles()
    }

    private companion object {
        private const val TAG = "BatchViewModel"

        private const val MESSAGE_IMPORT_FAILED =
            "خطا در کپی برخی فایل‌ها. لطفاً دوباره تلاش کنید."

        private const val MESSAGE_NOTHING_TO_EXPORT =
            "فاکتور استخراج‌شده‌ای برای خروجی وجود ندارد."

        private const val MESSAGE_CSV_SUCCESS = "فایل CSV تجمیعی با موفقیت ذخیره شد"
        private const val MESSAGE_CSV_FAILURE = "خطا در ایجاد فایل CSV تجمیعی"
        private const val MESSAGE_EXCEL_SUCCESS = "فایل اکسل تجمیعی با موفقیت ذخیره شد"
        private const val MESSAGE_EXCEL_FAILURE = "خطا در ایجاد فایل اکسل تجمیعی"
    }
}
