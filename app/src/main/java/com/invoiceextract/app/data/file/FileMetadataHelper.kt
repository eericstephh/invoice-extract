package com.invoiceextract.app.data.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

/**
 * Result of a successful import. Carries everything the UI and the next pipeline
 * stages need, so they never have to touch the (now possibly stale) [Uri] again.
 */
data class StagedInvoiceFile(
    val file: File,
    val originalName: String,
    val sizeBytes: Long,
    val isPdf: Boolean,
)

/**
 * Imports a user-picked invoice document into the app's private cache.
 *
 * Both the Photo Picker and SAF grant only a *transient* read permission for the
 * returned [Uri]: it dies with the activity, and on Android 11+ it cannot even be
 * persisted. Any later re-open of that Uri (e.g. from a background OCR job) would
 * throw [SecurityException]. This class therefore resolves and **copies** the
 * stream synchronously into [Context.getCacheDir]/invoices/ while the permission is
 * still hot, and hands back a plain [File] that the app owns outright.
 *
 * No runtime storage permissions are required: every path below is app-private.
 */
class FileMetadataHelper(private val context: Context) {

    /**
     * Reads metadata and stages [uri] into the private cache.
     *
     * @return [Result.success] with a [StagedInvoiceFile], or [Result.failure]
     *   carrying a descriptive [ImportError] when the MIME type is unsupported,
     *   the document exceeds [MAX_SIZE_BYTES], or the stream cannot be read.
     */
    fun importInvoice(uri: Uri): Result<StagedInvoiceFile> {
        if (uri == Uri.EMPTY) return Result.failure(ImportError.EmptySelection)

        val resolver = context.contentResolver

        val mimeType = resolver.getType(uri)?.lowercase(Locale.ROOT)
        val supported = mimeType in SUPPORTED_MIME_TYPES
        if (!supported) return Result.failure(ImportError.UnsupportedFormat)

        val metadata = readMetadata(uri, resolver)
            ?: return Result.failure(ImportError.MetadataFailed)

        if (metadata.size > MAX_SIZE_BYTES) return Result.failure(ImportError.TooLarge)

        val staged = stageIntoCache(uri, resolver, metadata) {
            if (it.length() > MAX_SIZE_BYTES) {
                it.delete()
                ImportError.TooLarge
            } else {
                null
            }
        } ?: return Result.failure(ImportError.CopyFailed)

        return Result.success(staged)
    }

    /**
     * Column projection for DISPLAY_NAME and SIZE.
     */
    private fun readMetadata(uri: Uri, resolver: ContentResolver): Metadata? {
        return try {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null

                val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)

                val name = cursor.getString(nameIndex).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
                // SIZE is nullable in the contract; treat blank as unknown rather than 0.
                val size = cursor.getLong(sizeIndex)

                Metadata(name, size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies the stream into the invoices cache directory, invoking [onStaged] for a
     * final size check before the result is trusted.
     */
    private fun stageIntoCache(
        uri: Uri,
        resolver: ContentResolver,
        metadata: Metadata,
        onStaged: (File) -> ImportError?,
    ): StagedInvoiceFile? {
        val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

        // Phase 16 — the cached file is never named after the user-supplied filename.
        // DISPLAY_NAME is fully attacker-controlled: a "name" of "../../lib/x" or
        // "a/../../b" would otherwise escape CACHE_DIR_NAME on File(dir, name), letting
        // an imported document overwrite an arbitrary app-private file. The on-disk name
        // is an opaque UUID; only the extension is carried over, and only after being
        // whitelisted, so it cannot contribute path segments either.
        val safeName = "staged_${UUID.randomUUID()}${sanitizedExtension(metadata.name)}"
        val target = File(dir, safeName)

        return try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) return null

                target.outputStream().use { output ->
                    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break

                        output.write(buffer, 0, read)

                        // Abort early so an oversized file cannot fill the cache.
                        if (target.length() > MAX_SIZE_BYTES) {
                            target.delete()
                            return null
                        }
                    }
                    output.flush()
                }
            }

            // Re-check after the stream is fully materialized.
            onStaged(target)?.let { return null }

            StagedInvoiceFile(
                file = target,
                originalName = metadata.name,
                sizeBytes = if (target.length() > 0) target.length() else metadata.size,
                isPdf = isPdfType(metadata.name),
            )
        } catch (_: IOException) {
            target.delete()
            null
        }
    }

    private fun isPdfType(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "pdf"

    /**
     * Whitelists the extension of [displayName] as `.<ext>` or returns an empty string.
     *
     * The extension is the only part of the user-supplied name that reaches the
     * filesystem, so it is reduced to the MIME types this helper already accepts and
     * nothing else. Anything containing a path separator, a dot run, or an unexpected
     * suffix is dropped rather than trusted.
     */
    private fun sanitizedExtension(displayName: String): String {
        val raw = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (raw in SAFE_EXTENSIONS) ".$raw" else ""
    }

    private data class Metadata(val name: String, val size: Long)

    /** Typed failures, surfaced to the UI as localized messages. */
    sealed class ImportError(message: String) : Exception(message) {
        object EmptySelection : ImportError("No file was selected")
        object UnsupportedFormat : ImportError("Unsupported MIME type")
        object TooLarge : ImportError("File exceeds size limit")
        object MetadataFailed : ImportError("Could not read file metadata")
        object CopyFailed : ImportError("Could not stage the file")
    }

    companion object {
        /** 15 MB, the largest document the extraction pipeline will accept. */
        const val MAX_SIZE_BYTES: Long = 15L * 1024L * 1024L

        private const val CACHE_DIR_NAME = "invoices"
        private const val TRANSFER_BUFFER_BYTES = 8 * 1024

        /**
         * The only extensions that may appear on a staged file. Mirrors
         * [SUPPORTED_MIME_TYPES]: a name is kept only if it is a plain image or PDF
         * suffix, so no user-supplied string can smuggle a path segment into the name.
         */
        private val SAFE_EXTENSIONS = setOf("jpg", "jpeg", "png", "pdf")

        private val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "application/pdf",
        )

        private val PROJECTION = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
        )

        /**
         * Human readable size, e.g. "1.2 MB". Used by the UI state so the
         * Compose layer never has to know about byte math.
         */
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
                .coerceAtMost(units.lastIndex)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return DecimalFormat("#,##0.#", DecimalFormatSymbols(Locale.US)).format(value) + " " + units[digitGroups]
        }
    }
}
