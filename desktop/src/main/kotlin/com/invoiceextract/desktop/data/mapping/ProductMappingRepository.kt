package com.invoiceextract.desktop.data.mapping

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * One warehouse row: the name an item carries on invoices mapped to the internal
 * warehouse code the accounting imports require.
 *
 * Serialized directly (rather than through a separate DTO) because this type lives in
 * the desktop data layer, which owns its JSON — unlike the domain models, which stay
 * serialization-free. Every property except the two names is defaulted, so a store
 * written by an older binary still decodes.
 *
 * @property id Stable row id, generated once per mapping.
 * @property rawItemName The item name exactly as the user typed or confirmed it.
 * @property normalizedItemName [rawItemName] through [DesktopPersianNormalizer], the
 *   match key: two spellings that normalize identically are the same good.
 * @property vendorName The seller this mapping is scoped to, or `null` for a global
 *   mapping that applies to every seller. Shown verbatim in the management dialog.
 * @property internalProductCode The warehouse code exported into the *کد کالا* column.
 * @property internalProductName The merchant's own name for the good, shown beside the
 *   code where space allows; `null` when the merchant only tracks codes.
 */
@Serializable
data class ProductMapping(
    val id: String = UUID.randomUUID().toString(),
    val rawItemName: String,
    val normalizedItemName: String,
    val vendorName: String? = null,
    val internalProductCode: String,
    val internalProductName: String? = null,
)

/**
 * File-backed product-mapping store for the desktop port, targeting
 * `%APPDATA%/InvoiceExtract/product_mappings.json` next to the invoice history.
 *
 * The same contract as [com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository],
 * deliberately smaller: a flat list of rows with no ordering requirement, so there is
 * no versioned envelope beyond the list itself — unknown keys are still ignored on
 * read, so tomorrow's row shape never breaks today's binary.
 *
 * **Matching.** [findMapping] implements the two-level priority the pipeline relies
 * on: a row whose normalized item name *and* normalized vendor both match wins; only
 * when no vendor-scoped row matches does a global row (or, with no global row, another
 * vendor's row) answer. Normalization on both sides is what makes `كالا` typed by the
 * user match `کالا` printed on the invoice.
 *
 * **Concurrency and crash safety.** Same recipe as the invoice store: one [Mutex]
 * around every read-modify-write, copy-on-write snapshots, and atomic temp-file
 * renames with fsync, so a power cut leaves the previous store intact.
 *
 * @param normalizer The shared Persian normalizer; matching runs through it on both
 *   the stored and the queried side.
 * @param json The serializer. Must ignore unknown keys; pretty-printing keeps the file
 *   human-inspectable.
 * @param storageDirectory The directory holding `product_mappings.json`. Defaults to
 *   `%APPDATA%/InvoiceExtract`; tests point it at a throwaway folder.
 */
class ProductMappingRepository(
    private val normalizer: DesktopPersianNormalizer,
    private val json: Json,
    storageDirectory: File = defaultStorageDirectory(),
) {

    private val storageFile = File(storageDirectory, STORE_FILE_NAME)
    private val temporaryFile = File(storageDirectory, STORE_FILE_NAME + TEMP_FILE_SUFFIX)

    /** Serializes every read-modify-write; the lazy load takes it too, exactly once. */
    private val mutex = Mutex()

    /** The whole table, copy-on-write under [mutex]. */
    private val mappings = MutableStateFlow<List<ProductMapping>>(emptyList())

    /** Whether [mappings] has been seeded from disk yet. Checked without the lock, set in it. */
    private val isLoaded = AtomicBoolean(false)

    /**
     * The table as a cold, reactive flow, in insertion order.
     *
     * The first collector triggers the one and only disk read; every later save and
     * delete re-emits through the same in-memory list.
     */
    fun getMappings(): Flow<List<ProductMapping>> = flow {
        ensureLoaded()
        emitAll(mappings)
    }.flowOn(Dispatchers.IO)

    /**
     * Inserts or replaces the mapping for ([rawItemName], [vendorName]).
     *
     * Upsert by normalized key, never append-blindly: typing a code character by
     * character in the invoice table must rewrite one row, not stack one row per
     * keystroke. Blank names or codes are rejected silently — the callers already gate
     * on them, and the store is the wrong place for a second opinion dialog.
     */
    suspend fun saveMapping(
        rawItemName: String,
        vendorName: String?,
        internalCode: String,
        internalName: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        if (rawItemName.isBlank() || internalCode.isBlank()) return@withContext

        runCatching {
            ensureLoaded()
            mutex.withLock {
                val row = ProductMapping(
                    rawItemName = rawItemName.trim(),
                    normalizedItemName = normalizer.normalize(rawItemName.trim()),
                    vendorName = vendorName?.trim().takeIf { it -> !it.isNullOrBlank() },
                    internalProductCode = internalCode.trim(),
                    internalProductName = internalName?.trim().takeIf { it -> !it.isNullOrBlank() },
                )
                val updated = mappings.value.filterNot { it.matchesKey(row) } + row
                persist(updated)
                mappings.value = updated
            }
        }.recoverCatching { cause ->
            if (cause is CancellationException) throw cause
            throw MappingPersistenceException(MSG_SAVE_FAILED, cause)
        }
    }

    /**
     * Resolves the mapping for one extracted line item, or `null` when unmapped.
     *
     * Priority: a row matching the normalized item name *and* the normalized vendor
     * first; then a global row for the item name; then another vendor's row as a last
     * resort rather than nothing. A blank item name never matches — without a name
     * there is no good to map.
     */
    suspend fun findMapping(itemName: String, vendorName: String?): ProductMapping? =
        withContext(Dispatchers.IO) {
            ensureLoaded()

            val normalizedItem = normalizer.normalize(itemName.trim())
            if (normalizedItem.isBlank()) return@withContext null
            val normalizedVendor = vendorName?.trim()?.let { normalizer.normalize(it) }

            val candidates = mappings.value.filter { it.normalizedItemName == normalizedItem }
            if (candidates.isEmpty()) return@withContext null

            if (!normalizedVendor.isNullOrBlank()) {
                candidates.firstOrNull { it.normalizedVendor() == normalizedVendor }
                    ?: candidates.firstOrNull { it.vendorName == null }
                    ?: candidates.firstOrNull()
            } else {
                candidates.firstOrNull { it.vendorName == null } ?: candidates.firstOrNull()
            }
        }

    /** Removes [id] when present; an unknown id is a no-op success, not an error. */
    suspend fun deleteMapping(id: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                val updated = mappings.value.filterNot { it.id == id }
                if (updated.size == mappings.value.size) return@runCatching
                persist(updated)
                mappings.value = updated
            }
        }.recoverCatching { cause ->
            if (cause is CancellationException) throw cause
            throw MappingPersistenceException(MSG_DELETE_FAILED, cause)
        }
    }

    /** Two rows describe the same mapping when item and vendor normalize identically. */
    private fun ProductMapping.matchesKey(other: ProductMapping): Boolean =
        normalizedItemName == other.normalizedItemName &&
            normalizedVendor() == other.normalizedVendor()

    /** The vendor in match space; `null` stays `null` so globals compare equal. */
    private fun ProductMapping.normalizedVendor(): String? =
        vendorName?.let { normalizer.normalize(it.trim()).takeIf { normalized -> normalized.isNotBlank() } }

    /**
     * Seeds the in-memory list from disk, at most once per instance.
     *
     * The unlocked fast path keeps every emission after the first one lock-free; the
     * double-checked lock keeps two simultaneous first reads from loading the file twice.
     */
    private suspend fun ensureLoaded() {
        if (isLoaded.get()) return
        mutex.withLock {
            if (isLoaded.get()) return
            mappings.value = readFromDisk()
            isLoaded.set(true)
        }
    }

    /**
     * Re-reads the table from disk, replacing the in-memory list unconditionally.
     *
     * The restore path calls this after swapping the store files, so the window
     * picks up the restored mappings without a restart — the [StateFlow] emission
     * refreshes every collector instantly. Reads degrade exactly like the first
     * load: a missing file reads as empty, never as a crash.
     */
    suspend fun reload() {
        mutex.withLock {
            mappings.value = readFromDisk()
            isLoaded.set(true)
        }
    }

    /**
     * Reads the table, or an empty list when there is nothing to read.
     *
     * A damaged file degrades to empty rather than throwing — the file stays on disk
     * for inspection, the window still opens, and the next successful save overwrites
     * the damage.
     */
    private fun readFromDisk(): List<ProductMapping> {
        if (!storageFile.isFile) return emptyList()

        return runCatching {
            val raw = storageFile.readText()
            if (raw.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<StoredProductMappingList>(raw).mappings
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Writes [rows] atomically: temp file next to the store (same-directory rename on
     * NTFS), flushed and fsynced before the move, so a crash promotes either the old
     * store or the new one, never a half-written file.
     */
    private fun persist(rows: List<ProductMapping>) {
        if (!storageFile.parentFile.exists() && !storageFile.parentFile.mkdirs()) {
            throw IOException("Cannot create store directory: ${storageFile.parent}")
        }

        val payload = json.encodeToString(StoredProductMappingList(mappings = rows))

        temporaryFile.outputStream().use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }

        Files.move(
            temporaryFile.toPath(),
            storageFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        const val STORE_FILE_NAME = "product_mappings.json"
        const val TEMP_FILE_SUFFIX = ".tmp"

        const val MSG_SAVE_FAILED = "ذخیره نگاشت کالا ناموفق بود."
        const val MSG_DELETE_FAILED = "حذف نگاشت کالا ناموفق بود."

        /**
         * `%APPDATA%/InvoiceExtract`, shared with the invoice history; the home
         * directory is the fallback for any host that does not define `APPDATA`.
         */
        fun defaultStorageDirectory(): File {
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return File(base, STORE_DIRECTORY_NAME)
        }

        const val STORE_DIRECTORY_NAME = "InvoiceExtract"
    }
}

/**
 * The single typed failure the mapping store can report, mirroring
 * [com.invoiceextract.desktop.data.storage.DesktopPersistenceException]: Persian for
 * the window, cause kept for logging.
 */
class MappingPersistenceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The table on disk. Unknown keys are ignored on read, so a future row shape never
 * breaks today's binary.
 */
@Serializable
internal data class StoredProductMappingList(
    val mappings: List<ProductMapping> = emptyList(),
)
