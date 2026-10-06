package com.invoiceextract.desktop.data.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.invoiceextract.desktop.data.document.InvoiceFileFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/**
 * The split-view document preview engine: turns a source invoice file into the
 * [ImageBitmap] pages the preview pane draws.
 *
 * Images decode straight off disk; PDFs render on demand through PDFBox at print-ish
 * resolution — the same 150 DPI working point the OCR stage settled on, sharp enough
 * to proofread a dense invoice without rasterizing a poster per page. Navigation is
 * clamped, so mashing next on the last page is a no-op rather than an exception.
 *
 * **Threading and lifetime.** Every entry point that touches the filesystem or native
 * decoders hops to [Dispatchers.IO] internally, so the panel can call from the render
 * thread without freezing the window. The open [PDDocument] is guarded by one plain
 * monitor (never a coroutine mutex — [close] must also work from the non-suspending
 * window-teardown path), and every document-touching block runs inside it with no
 * suspension points, so a close racing a render serializes instead of tearing.
 * [close] is idempotent: loading a second document closes the first, and closing
 * twice is a quiet no-op.
 *
 * **Errors.** A missing, deleted or undecodable file never throws: [errorMessage]
 * carries the Persian fallback and the bitmap stays `null`, so the pane draws the
 * explanation instead of crashing the split view. Cancellation unwinds untouched.
 */
class DesktopDocumentPreviewManager {

    /** Rendered pages in the loaded document; `1` for a plain image, `0` when empty. */
    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    /** Zero-based page on screen, always within `0 until pageCount`. */
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    /** The bitmap the pane draws, or `null` while loading or on failure. */
    private val _currentBitmap = MutableStateFlow<ImageBitmap?>(null)
    val currentBitmap: StateFlow<ImageBitmap?> = _currentBitmap.asStateFlow()

    /** Persian fallback sentence when the source cannot be shown; `null` otherwise. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Guards [document] and [renderer]. A JVM monitor rather than a coroutine mutex
     * on purpose: [close] runs from window teardown without a scope, and every block
     * under it is short, non-suspending native I/O — nothing here can starve a
     * dispatcher.
     */
    private val guard = Any()

    /** The open PDF, or `null` for images and the empty state. Only touched under [guard]. */
    private var document: PDDocument? = null

    /** Renderer bound to [document]; rebuilt on every load, dropped on [close]. */
    private var renderer: PDFRenderer? = null

    /**
     * Loads [file] for preview, replacing whatever is currently open.
     *
     * Unsupported suffixes are reported, not thrown: the pane shows the fallback
     * sentence, which is friendlier than an error screen for a file the user picked
     * outside the normal entry points.
     */
    suspend fun loadDocument(file: File): Unit = withContext(Dispatchers.IO) {
        synchronized(guard) {
            closeLocked()
            _currentBitmap.value = null
            _errorMessage.value = null
            _currentPageIndex.value = 0
            _pageCount.value = 0

            if (!file.isFile) {
                _errorMessage.value = SOURCE_MISSING_MESSAGE
                return@withContext
            }

            try {
                when {
                    InvoiceFileFormats.isImage(file.extension) -> loadImageLocked(file)
                    file.extension.equals(InvoiceFileFormats.PDF, ignoreCase = true) ->
                        loadPdfLocked(file)
                    else -> _errorMessage.value = SOURCE_MISSING_MESSAGE
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                closeLocked()
                _errorMessage.value = SOURCE_MISSING_MESSAGE
                logCause(cause)
            }
        }
    }

    /**
     * Steps one page forward, clamped at the last page. No-op when nothing is loaded.
     */
    suspend fun nextPage(): Unit = withContext(Dispatchers.IO) {
        synchronized(guard) {
            val next = _currentPageIndex.value + 1
            if (next < _pageCount.value) {
                _currentPageIndex.value = next
                renderCurrentLocked()
            }
        }
    }

    /**
     * Steps one page back, clamped at the first page. No-op when nothing is loaded.
     */
    suspend fun prevPage(): Unit = withContext(Dispatchers.IO) {
        synchronized(guard) {
            val previous = _currentPageIndex.value - 1
            if (previous >= 0 && _pageCount.value > 0) {
                _currentPageIndex.value = previous
                renderCurrentLocked()
            }
        }
    }

    /**
     * Releases the open document and resets every flow to the empty state.
     *
     * Safe to call from any thread, including window teardown, and safe to repeat:
     * the second call finds nothing to close and only re-emits the already-empty
     * state.
     */
    fun close() {
        synchronized(guard) {
            closeLocked()
            _currentBitmap.value = null
            _errorMessage.value = null
            _currentPageIndex.value = 0
            _pageCount.value = 0
        }
    }

    /** Decodes a photo or scan straight into the single preview page. */
    private fun loadImageLocked(file: File) {
        val decoded = ImageIO.read(file)
        if (decoded == null) {
            _errorMessage.value = SOURCE_MISSING_MESSAGE
            return
        }
        _currentBitmap.value = decoded.toComposeImageBitmap()
        _pageCount.value = 1
    }

    /** Opens a PDF and renders its first page; navigation renders the rest on demand. */
    private fun loadPdfLocked(file: File) {
        val loaded = Loader.loadPDF(file)
        val count = loaded.numberOfPages
        if (count <= 0) {
            runCatching { loaded.close() }
            _errorMessage.value = SOURCE_MISSING_MESSAGE
            return
        }
        document = loaded
        renderer = PDFRenderer(loaded)
        _pageCount.value = count
        renderCurrentLocked()
    }

    /** Renders [_currentPageIndex] of the open document. Only call under [guard]. */
    private fun renderCurrentLocked() {
        val active = renderer ?: return
        try {
            _currentBitmap.value =
                active.renderImageWithDPI(_currentPageIndex.value, PREVIEW_DPI)
                    .toComposeImageBitmap()
            _errorMessage.value = null
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            _currentBitmap.value = null
            _errorMessage.value = SOURCE_MISSING_MESSAGE
            logCause(cause)
        }
    }

    /** Closes the open document, if any. Only call under [guard]. */
    private fun closeLocked() {
        runCatching { document?.close() }
        document = null
        renderer = null
    }

    /**
     * Keeps the terminal log honest: the pane shows the friendly Persian sentence, so
     * the engineering cause goes to stderr instead of vanishing.
     */
    private fun logCause(cause: Exception) {
        cause.printStackTrace()
    }

    private companion object {
        /** Preview resolution: proofread-sharp without poster-sized rasters. */
        const val PREVIEW_DPI = 150f

        const val SOURCE_MISSING_MESSAGE =
            "فایل مبدأ سند در دسترس نیست یا حذف شده است."
    }
}
