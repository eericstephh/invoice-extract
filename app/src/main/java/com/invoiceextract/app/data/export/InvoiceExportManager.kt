package com.invoiceextract.app.data.export

import android.content.Context
import android.net.Uri
import com.invoiceextract.domain.export.InvoiceCsvExporter
import com.invoiceextract.domain.export.InvoiceXmlSpreadsheetExporter
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Single entry point for exporting an invoice to a user-chosen file (Phase 9.1).
 *
 * Holds the [Context] so the two pure generators stay Android-free and individually
 * testable against a plain [java.io.ByteArrayOutputStream]. The [Uri] comes from SAF's
 * `CreateDocument`, whose write grant is transient and tied to the activity, so both
 * exports complete inside the call instead of handing the Uri off to a background job.
 *
 * Every failure — a null stream, a revoked permission, a full disk — surfaces as a
 * typed [ExportException] inside [Result.failure], so the UI can show a Persian message
 * and the user keeps the invoice on screen.
 *
 * @property appContext Application context; the resolver is process-scoped, so this
 *   never leaks an activity or a view.
 */
class InvoiceExportManager(private val appContext: Context) {

    /**
     * Writes [invoice] as a UTF-8-BOM CSV document into [uri].
     *
     * @return [Result.success], or [Result.failure] carrying [ExportException].
     */
    suspend fun exportCsv(uri: Uri, invoice: Invoice): Result<Unit> =
        write(uri) { stream ->
            InvoiceCsvExporter.export(stream, invoice)
        }

    /**
     * Writes [invoice] as an Excel XML Spreadsheet into [uri]. Opens in Excel and
     * LibreOffice with RTL columns already laid out.
     *
     * @return [Result.success], or [Result.failure] carrying [ExportException].
     */
    suspend fun exportExcel(uri: Uri, invoice: Invoice): Result<Unit> =
        write(uri) { stream ->
            InvoiceXmlSpreadsheetExporter.export(stream, invoice)
        }

    /**
     * Dispatches [block] onto [Dispatchers.IO] and owns the stream lifecycle: the
     * `use` block closes it even when the generator throws, and the stream is flushed
     * before close so nothing is truncated on a slow flash write.
     */
    private suspend fun write(
        uri: Uri,
        block: (java.io.OutputStream) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val stream = resolver.openOutputStream(uri)
                ?: throw ExportException.StreamUnavailable(uri)

            stream.use {
                block(it)
                it.flush()
            }
        }.recoverCatching { cause ->
            // Fold unrelated throwables into the typed failure so callers match on one
            // sealed hierarchy instead of duck-typing exception messages.
            if (cause is ExportException) throw cause
            throw ExportException.WriteFailed(cause)
        }
    }

    /** Typed export failures, surfaced to the UI as localized messages. */
    sealed class ExportException(message: String, cause: Throwable? = null) :
        IOException(message, cause) {

        /** The provider would not hand over a writable stream for [uri]. */
        class StreamUnavailable(uri: Uri) :
            ExportException("Cannot open output stream for ${uri.path ?: uri}")

        /** The generator failed mid-write; the file is left incomplete. */
        class WriteFailed(cause: Throwable) :
            ExportException("Export failed: ${cause.message ?: cause.javaClass.simpleName}", cause)
    }
}
