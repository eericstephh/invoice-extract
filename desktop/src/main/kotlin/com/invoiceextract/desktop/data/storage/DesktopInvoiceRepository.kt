package com.invoiceextract.desktop.data.storage

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.IssueSeverity
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * File-backed [InvoiceRepository] for the desktop port, targeting
 * `%APPDATA%/InvoiceExtract/invoices.json` on a standard Windows install.
 *
 * The Android app persists into Room; a desktop has no such expectation of a database
 * engine, so the whole history lives in one JSON document this class owns end to end.
 * The format (`StoredInvoiceList`) is a JSON object rather than a bare array and carries a
 * schema version, so tomorrow's store can grow new sections without today's binary
 * choking on them — the injected [Json] is lenient by design and drops unknown keys.
 *
 * **Concurrency.** Every read-modify-write passes through one [Mutex]: saves, deletes and
 * the lazy first load from coroutines that will never meet each other any other way. The
 * in-memory list is copy-on-write — a fresh immutable snapshot under the lock, never a
 * mutated shared list — so [getInvoices] subscribers see whole, consistent states and never
 * a half-edited one.
 *
 * **Crash safety.** The store is never written in place. The payload goes to
 * `invoices.json.tmp` in the *same* directory, is flushed and fsynced, and is then moved
 * over `invoices.json` in one atomic rename. A crash at any point leaves either the old
 * store or the new one, never a half-written file — there is no temp file to vacuum and no
 * journal to replay.
 *
 * **Errors.** Writes return `Result`: an unwritable disk becomes a typed
 * [DesktopPersistenceException] with a Persian message instead of a raw `IOException`.
 * Reads degrade instead: a missing or damaged store yields an empty history (the next save
 * overwrites the damage), because a corrupt history file must never stop the window from
 * opening. Cancellation is re-thrown, never reported as a failure.
 *
 * The domain stays serialization-free by design, so the wire DTOs (`StoredInvoice` and
 * friends) live here, next to the store that owns them — the same split the AI layer uses
 * between `OllamaInvoiceDto` and the domain aggregate.
 *
 * @param json The serializer. Must ignore unknown keys so a newer store layout never
 *   breaks an older binary; pretty-printing keeps the file human-inspectable. Wired in
 *   the desktop DI module as a dedicated instance — the shared one is tuned for Ollama
 *   envelopes, not for a file a user may open.
 * @param storageDirectory The directory holding `invoices.json`. Defaults to
 *   `%APPDATA%/InvoiceExtract`; the parameter exists so tests can point the store at a
 *   throwaway folder without touching the user's real history.
 */
class DesktopInvoiceRepository(
    private val json: Json,
    storageDirectory: File = defaultStorageDirectory(),
) : InvoiceRepository {

    private val storageFile = File(storageDirectory, STORE_FILE_NAME)
    private val temporaryFile = File(storageDirectory, STORE_FILE_NAME + TEMP_FILE_SUFFIX)

    /** Serializes every read-modify-write; the lazy load takes it too, exactly once. */
    private val mutex = Mutex()

    /** The whole history, copy-on-write under [mutex]. */
    private val invoices = MutableStateFlow<List<Invoice>>(emptyList())

    /** Whether [invoices] has been seeded from disk yet. Checked without the lock, set in it. */
    private val isLoaded = AtomicBoolean(false)

    /**
     * The history as a cold, reactive flow, newest first.
     *
     * The first collector triggers the one and only disk read; every later save and delete
     * re-emits through the same in-memory list, so there is no re-read per emission and no
     * polling. Ordering is guaranteed at emission: any insertion order still comes out
     * `createdAtEpochMs` descending, which is also what the Android DAO query does.
     */
    override fun getInvoices(): Flow<List<Invoice>> = flow {
        ensureLoaded()
        emitAll(invoices)
    }.map { saved -> saved.sortedByDescending(Invoice::createdAtEpochMs) }
        .flowOn(Dispatchers.IO)

    /** Reads off the in-memory snapshot; a `StateFlow` value read is itself thread-safe. */
    override suspend fun getInvoiceById(id: String): Invoice? = withContext(Dispatchers.IO) {
        ensureLoaded()
        invoices.value.firstOrNull { it.id == id }
    }

    /**
     * Upserts [invoice] by id and fsyncs the store.
     *
     * Same id twice is a rewrite, not a duplicate — that is what makes the window's save
     * action idempotent: pressing it again after editing updates the one record instead of
     * stacking history entries.
     */
    override suspend fun saveInvoice(invoice: Invoice): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureLoaded()
                mutex.withLock {
                    val updated = invoices.value.filterNot { it.id == invoice.id } + invoice
                    persist(updated)
                    invoices.value = updated
                }
            }.recoverCatching { cause ->
                if (cause.isCancellation()) throw cause
                throw DesktopPersistenceException(MSG_SAVE_FAILED, cause)
            }
        }

    /** Removes [id] when present; an unknown id is a no-op success, not an error. */
    override suspend fun deleteInvoice(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureLoaded()
                mutex.withLock {
                    val updated = invoices.value.filterNot { it.id == id }
                    if (updated.size == invoices.value.size) return@runCatching
                    persist(updated)
                    invoices.value = updated
                }
            }.recoverCatching { cause ->
                if (cause.isCancellation()) throw cause
                throw DesktopPersistenceException(MSG_DELETE_FAILED, cause)
            }
        }

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
            invoices.value = readFromDisk()
            isLoaded.set(true)
        }
    }

    /**
     * Re-reads the store from disk, replacing the in-memory list unconditionally.
     *
     * The restore path calls this after swapping the store files, so the window
     * picks up the restored history without a restart — the [StateFlow] emission
     * refreshes every collector instantly. Reads degrade exactly like the first
     * load: a missing file reads as empty, never as a crash.
     */
    suspend fun reload() {
        mutex.withLock {
            invoices.value = readFromDisk()
            isLoaded.set(true)
        }
    }

    /**
     * Reads the store into domain invoices, or an empty list when there is nothing to read.
     *
     * A damaged file degrades to empty rather than throwing — the file stays on disk for
     * inspection, the window still opens, and the next successful save overwrites the
     * damage. Starting the history from nothing is always safe; crashing the app over it
     * never is.
     */
    private fun readFromDisk(): List<Invoice> {
        if (!storageFile.isFile) return emptyList()

        return runCatching {
            val raw = storageFile.readText()
            if (raw.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<StoredInvoiceList>(raw).invoices.map { it.toDomain() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Writes [invoices] to the store atomically.
     *
     * The temp file sits next to the store by construction (same `File` parent), so the
     * final move is a same-directory rename — atomic on NTFS — applied over the old store
     * in one step. The payload is flushed and fsynced *before* the rename, so a power cut
     * between the write and the move leaves the previous store intact instead of promoting a
     * half-written one.
     */
    private fun persist(invoices: List<Invoice>) {
        if (!storageFile.parentFile.exists() && !storageFile.parentFile.mkdirs()) {
            throw IOException("Cannot create store directory: ${storageFile.parent}")
        }

        val payload = json.encodeToString(StoredInvoiceList(invoices = invoices.map { it.toStored() }))

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
        const val STORE_FILE_NAME = "invoices.json"
        const val TEMP_FILE_SUFFIX = ".tmp"

        const val MSG_SAVE_FAILED = "ذخیره فاکتور ناموفق بود."
        const val MSG_DELETE_FAILED = "حذف فاکتور ناموفق بود."

        /**
         * `%APPDATA%/InvoiceExtract`, the per-user roaming store on Windows; the home
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
 * The single typed failure the desktop store can report.
 *
 * Mirrors the AI layer's `LocalExtractionException` in one respect that matters: the
 * message is Persian because the window may show it verbatim, while the technical cause
 * rides along for logging.
 */
class DesktopPersistenceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The whole store on disk: a schema version plus the invoice list.
 *
 * The version is read but currently unused — it exists so a future store layout can be
 * detected and migrated instead of misread.
 */
@Serializable
internal data class StoredInvoiceList(
    val version: Int = STORE_SCHEMA_VERSION,
    val invoices: List<StoredInvoice> = emptyList(),
)

/**
 * One history record as stored, field-for-field off the domain aggregate except for the
 * verdict: [ValidationStatus] is a sealed domain type, so it persists as a [StoredValidation]
 * beside the payload instead of pretending to be a plain field.
 */
@Serializable
internal data class StoredInvoice(
    val id: String,
    val invoiceNumber: String? = null,
    val date: String? = null,
    val sellerName: String? = null,
    val sellerTaxId: String? = null,
    val buyerName: String? = null,
    val buyerTaxId: String? = null,
    // Defaulted so history files written before national-ID auditing decode unchanged.
    val sellerNationalId: String? = null,
    val buyerNationalId: String? = null,
    // Defaulted so history files written before client/project tagging decode unchanged.
    val clientName: String? = null,
    val projectName: String? = null,
    val items: List<StoredInvoiceItem> = emptyList(),
    val subtotal: Double = 0.0,
    val totalTax: Double = 0.0,
    val totalDiscount: Double = 0.0,
    val grandTotal: Double = 0.0,
    val currency: String = CurrencyType.UNKNOWN.name,
    // Defaulted so history files written before multi-currency support decode unchanged.
    val exchangeRate: Double? = null,
    val originalForeignAmount: Double? = null,
    val rawOcrText: String = "",
    val validation: StoredValidation = StoredValidation(),
    val createdAtEpochMs: Long = 0L,
    // Defaulted so history files written before the split-view preview decode unchanged.
    val sourceFilePath: String? = null,
    // Defaulted so history files written before payment tracking decode unchanged:
    // every legacy record reopens as a pending receivable.
    val paymentStatus: String = PaymentStatus.PENDING.name,
    // Defaulted alongside the status: no deadline was ever recorded for it.
    val dueDate: String? = null,
)

@Serializable
internal data class StoredInvoiceItem(
    val id: String,
    val name: String,
    val quantity: Double = 0.0,
    val unitPrice: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val totalPrice: Double = 0.0,
    val confidence: Float = 0f,
    val isSuspicious: Boolean = false,
    // Defaulted so stores written before product-code mapping decode unchanged.
    val productCode: String? = null,
)

/** The verdict, stored natively in JSON rather than as an opaque string blob. */
@Serializable
internal data class StoredValidation(
    val status: String = STATUS_VALID,
    val issues: List<StoredValidationIssue> = emptyList(),
)

@Serializable
internal data class StoredValidationIssue(
    val field: String,
    val description: String,
    val severity: String = IssueSeverity.WARNING.name,
)

private fun Invoice.toStored(): StoredInvoice = StoredInvoice(
    id = id,
    invoiceNumber = invoiceNumber,
    date = date,
    sellerName = sellerName,
    sellerTaxId = sellerTaxId,
    buyerName = buyerName,
    buyerTaxId = buyerTaxId,
    sellerNationalId = sellerNationalId,
    buyerNationalId = buyerNationalId,
    clientName = clientName,
    projectName = projectName,
    items = items.map { it.toStored() },
    subtotal = subtotal,
    totalTax = totalTax,
    totalDiscount = totalDiscount,
    grandTotal = grandTotal,
    currency = currency.name,
    exchangeRate = exchangeRate,
    originalForeignAmount = originalForeignAmount,
    rawOcrText = rawOcrText,
    validation = validationStatus.toStored(),
    createdAtEpochMs = createdAtEpochMs,
    sourceFilePath = sourceFilePath,
    paymentStatus = paymentStatus.name,
    dueDate = dueDate,
)

private fun InvoiceItem.toStored(): StoredInvoiceItem = StoredInvoiceItem(
    id = id,
    name = name,
    quantity = quantity,
    unitPrice = unitPrice,
    discount = discount,
    tax = tax,
    totalPrice = totalPrice,
    confidence = confidence,
    isSuspicious = isSuspicious,
    productCode = productCode,
)

private fun StoredInvoice.toDomain(): Invoice = Invoice(
    id = id,
    invoiceNumber = invoiceNumber,
    date = date,
    sellerName = sellerName,
    sellerTaxId = sellerTaxId,
    buyerName = buyerName,
    buyerTaxId = buyerTaxId,
    sellerNationalId = sellerNationalId?.ifBlank { null },
    buyerNationalId = buyerNationalId?.ifBlank { null },
    clientName = clientName?.ifBlank { null },
    projectName = projectName?.ifBlank { null },
    items = items.map { it.toDomain() },
    subtotal = subtotal,
    totalTax = totalTax,
    totalDiscount = totalDiscount,
    grandTotal = grandTotal,
    // Unknown names degrade instead of throwing, mirroring the Room mapper: one bad row
    // must never crash the whole history.
    currency = runCatching { CurrencyType.valueOf(currency) }.getOrDefault(CurrencyType.UNKNOWN),
    exchangeRate = exchangeRate?.takeIf { it.isFinite() && it > 0.0 },
    originalForeignAmount = originalForeignAmount?.takeIf { it.isFinite() },
    rawOcrText = rawOcrText,
    validationStatus = validation.toDomain(),
    createdAtEpochMs = createdAtEpochMs,
    sourceFilePath = sourceFilePath?.ifBlank { null },
    // Unknown names degrade to PENDING instead of throwing, mirroring the
    // currency mapping above: one hand-edited row must never crash history.
    paymentStatus = runCatching { PaymentStatus.valueOf(paymentStatus) }
        .getOrDefault(PaymentStatus.PENDING),
    dueDate = dueDate?.ifBlank { null },
)

private fun StoredInvoiceItem.toDomain(): InvoiceItem = InvoiceItem(
    id = id,
    name = name,
    quantity = quantity,
    unitPrice = unitPrice,
    discount = discount,
    tax = tax,
    totalPrice = totalPrice,
    // Clamp, mirroring the Room mapper: a hand-edited store outside 0..1 must still open.
    confidence = confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
    isSuspicious = isSuspicious,
    productCode = productCode?.ifBlank { null },
)

private fun ValidationStatus.toStored(): StoredValidation = when (this) {
    ValidationStatus.Valid -> StoredValidation(status = STATUS_VALID)
    is ValidationStatus.Warning -> StoredValidation(
        status = STATUS_WARNING,
        issues = reasons.map { it.toStored() },
    )
    is ValidationStatus.Invalid -> StoredValidation(
        status = STATUS_INVALID,
        issues = criticalErrors.map { it.toStored() },
    )
}

private fun StoredValidation.toDomain(): ValidationStatus {
    val issues = issues.map { it.toDomain() }
    return when (status) {
        // An empty remainder after a WARNING/INVALID marker yields Valid, never a status
        // that violates its own non-empty-list contract — the same rule the Android codec uses.
        STATUS_WARNING -> if (issues.isEmpty()) ValidationStatus.Valid else ValidationStatus.Warning(issues)
        STATUS_INVALID -> if (issues.isEmpty()) ValidationStatus.Valid else ValidationStatus.Invalid(issues)
        else -> ValidationStatus.Valid
    }
}

private fun ValidationIssue.toStored(): StoredValidationIssue = StoredValidationIssue(
    field = field,
    description = description,
    severity = severity.name,
)

private fun StoredValidationIssue.toDomain(): ValidationIssue = ValidationIssue(
    field = field,
    description = description,
    severity = runCatching { IssueSeverity.valueOf(severity) }.getOrDefault(IssueSeverity.WARNING),
)

/** True only for coroutine cancellation, which must never become a user-facing failure. */
private fun Throwable.isCancellation(): Boolean = this is CancellationException

private const val STORE_SCHEMA_VERSION = 1

private const val STATUS_VALID = "VALID"
private const val STATUS_WARNING = "WARNING"
private const val STATUS_INVALID = "INVALID"

private const val MIN_CONFIDENCE = 0f
private const val MAX_CONFIDENCE = 1f
