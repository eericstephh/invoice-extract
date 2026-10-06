package com.invoiceextract.app.ui.screens.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.invoiceextract.app.R
import com.invoiceextract.app.data.file.FileMetadataHelper
import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase
import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase.ProcessingException
import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase.ProcessingStage
import com.invoiceextract.domain.quota.QuotaExceededException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the Home screen state.
 *
 * Uses [AndroidViewModel] because import errors must be turned into *localized*
 * Persian messages, which requires string resources and therefore an
 * [Application] context. No other Android dependency is needed here.
 */
class HomeViewModel(
    application: Application,
    private val fileHelper: FileMetadataHelper,
    private val processInvoiceUseCase: ProcessInvoiceUseCase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * The staged copy behind the current selection, remembered independently of the UI
     * state so the temp file can be cleaned up from *any* state — including
     * [HomeUiState.Processing], [HomeUiState.Success] and [HomeUiState.Error], which do
     * not all carry a [SelectedInvoiceFile] themselves.
     *
     * Written from the IO dispatcher and read from [onCleared]'s main-thread call, hence
     * [Volatile].
     */
    @Volatile
    private var stagedFile: File? = null

    /**
     * Processes a Uri returned by either the Photo Picker or the document picker.
     *
     * Runs on [Dispatchers.IO] since the call copies up to 15 MB from a content
     * provider and must never block the main thread. The transient read
     * permission for the Uri is only valid for the duration of the activity, so
     * the whole copy completes inside this call.
     */
    fun onFileSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = HomeUiState.Idle

            val result = fileHelper.importInvoice(uri)

            _uiState.value = result.fold(
                onSuccess = { staged ->
                    stagedFile = staged.file
                    HomeUiState.Selected(
                        SelectedInvoiceFile(
                            file = staged.file,
                            originalName = staged.originalName,
                            sizeFormatted = FileMetadataHelper.formatSize(staged.sizeBytes),
                            isPdf = staged.isPdf,
                        )
                    )
                },
                onFailure = { error ->
                    HomeUiState.Error(messageFor(error))
                },
            )
        }
    }

    /**
     * Runs the extraction pipeline over the staged document.
     *
     * The state flips to [HomeUiState.Processing] with
     * [ProcessingStage.PREPARING_IMAGE] *synchronously*, before the coroutine is even
     * started, so a double tap on the action can never launch two overlapping
     * pipelines: by the time the second tap arrives the state is no longer
     * [HomeUiState.Selected] and the call bails out.
     *
     * Progress arrives through the use case's stage callback and is published straight
     * into [uiState]; the final outcome replaces it with [HomeUiState.Success] or
     * [HomeUiState.Error]. Collecting in [viewModelScope] scopes the whole run to the
     * ViewModel: leaving the screen cancels it and frees the pipeline's bitmaps.
     */
    fun startProcessing() {
        val selected = _uiState.value as? HomeUiState.Selected ?: return
        val fileInfo = selected.fileInfo

        _uiState.value = HomeUiState.Processing(fileInfo, ProcessingStage.PREPARING_IMAGE)

        viewModelScope.launch {
            processInvoiceUseCase(
                file = fileInfo.file,
                isPdf = fileInfo.isPdf,
                onProgress = { updateStage(it) },
            ).collect { result ->
                _uiState.value = result.fold(
                    onSuccess = { invoice -> HomeUiState.Success(fileInfo, invoice) },
                    onFailure = { error -> HomeUiState.Error(messageFor(error)) },
                )
            }
        }
    }

    /**
     * Advances the visible stage without dropping the file the run belongs to.
     *
     * A no-op once the state is no longer [HomeUiState.Processing], so a stage callback
     * that races with a terminal outcome — or lands after the user backed out — can
     * never resurrect a progress screen over a result.
     */
    private fun updateStage(stage: ProcessingStage) {
        val processing = _uiState.value as? HomeUiState.Processing ?: return
        _uiState.value = processing.copy(stage = stage)
    }

    /**
     * Returns to [HomeUiState.Selected] with the same staged file, without re-importing
     * it. The back-out path from a finished or in-flight run: the document is already on
     * disk, so the user should not have to pick and copy it again just to retry.
     */
    fun resetToSelected() {
        val fileInfo = when (val current = _uiState.value) {
            is HomeUiState.Selected -> current.fileInfo
            is HomeUiState.Processing -> current.fileInfo
            is HomeUiState.Success -> current.fileInfo
            else -> return
        }
        _uiState.value = HomeUiState.Selected(fileInfo)
    }

    /**
     * Deletes the staged temp file and returns to [HomeUiState.Idle], the "start over
     * with a different document" path. Also the cleanup path for a failed run: the
     * pipeline has given up on this file, so nothing it left on disk should outlive it.
     */
    fun resetToIdle() {
        deleteStagedFile()
        _uiState.value = HomeUiState.Idle
    }

    /**
     * Deletes the staged file if one is still around, then forgets it. A single small
     * cache-file delete, so it is also safe from [onCleared]'s main-thread call.
     */
    private fun deleteStagedFile() {
        stagedFile?.let { file ->
            runCatching { file.delete() }
            stagedFile = null
        }
    }

    private fun messageFor(error: Throwable): String {
        val res = getApplication<Application>().resources
        return when (error) {
            // The quota gate refuses before any stage runs, so it is not a stage failure:
            // the exception already carries the ready-to-show sentence the contract defines.
            is QuotaExceededException -> error.message ?: res.getString(R.string.error_quota_exceeded)
            // Pipeline failures report the stage, which tells the user where it broke.
            is ProcessingException -> when (error.stage) {
                ProcessingStage.PREPARING_IMAGE -> res.getString(R.string.error_preparing_failed)
                ProcessingStage.RUNNING_OCR -> res.getString(R.string.error_ocr_failed)
                ProcessingStage.PARSING_STRUCTURE -> res.getString(R.string.error_parsing_failed)
            }
            is FileMetadataHelper.ImportError.EmptySelection ->
                res.getString(R.string.error_empty_selection)
            is FileMetadataHelper.ImportError.UnsupportedFormat ->
                res.getString(R.string.error_unsupported_format)
            is FileMetadataHelper.ImportError.TooLarge ->
                res.getString(R.string.error_file_too_large)
            is FileMetadataHelper.ImportError.MetadataFailed ->
                res.getString(R.string.error_metadata_failed)
            is FileMetadataHelper.ImportError.CopyFailed ->
                res.getString(R.string.error_copy_failed)
            else -> res.getString(R.string.error_copy_failed)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Never leave orphaned temp files behind if the user navigates away —
        // including mid-pipeline or after a successful extraction.
        deleteStagedFile()
    }
}
