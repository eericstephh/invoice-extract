package com.invoiceextract.desktop.data.licensing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/** The license behind donation-ware: every install is fully activated. */

/**
 * The license behind donation-ware: every install is fully activated.
 *
 * InvoiceExtract Desktop is 100% free and unlimited — no trial, no quota, no
 * keys. This manager stays as the persistence seam (old stores still decode,
 * and any legacy unactivated record migrates to activated on load), but every
 * gate it ever owned now passes unconditionally.
 *
 * @property isActivated Always `true` for new installs; legacy stores flip
 *   to activated when read.
 * @property processedLifetimeCount Lifetime extraction odometer, kept for
 *   diagnostics only — nothing reads it as a balance anymore.
 * @property licenseKey The normalized accepted key, or `null`; retained so
 *   old stores keep decoding.
 */
@Serializable
data class LicenseState(
    val isActivated: Boolean = true,
    val processedLifetimeCount: Int = 0,
    val licenseKey: String? = null,
) {
    /** Unlimited, always: donation-ware has no quota to remain of. */
    val remainingQuota: Int
        get() = Int.MAX_VALUE

    /** Never exhausted: there is no quota to spend. */
    val isQuotaExhausted: Boolean
        get() = false
}

/**
 * File-backed license state for the desktop port, targeting
 * `%APPDATA%/InvoiceExtract/license_state.json` next to the invoice history.
 *
 * Same recipe as the invoice store: one JSON document rewritten atomically
 * behind a [Mutex] (temp file, fsync, same-directory rename), a lazily loaded
 * in-memory snapshot re-emitted as a [StateFlow], and reads that degrade to
 * defaults instead of crashing.
 *
 * Key validation is deliberately offline and format-based (the
 * `INV-PRO-XXXX-XXXX` shape plus the master key below); it survives as a
 * harmless no-op path now that every install is activated, so old callers and
 * old stores keep working unchanged.
 *
 * @param json The serializer. Lenient by design so a newer state layout never
 *   breaks an older binary.
 * @param storageDirectory The directory holding `license_state.json`. Defaults
 *   to `%APPDATA%/InvoiceExtract`; tests point it at a throwaway folder.
 */
class DesktopLicenseManager(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
    storageDirectory: File = defaultStorageDirectory(),
) {

    private val storageFile = File(storageDirectory, STORE_FILE_NAME)
    private val temporaryFile = File(storageDirectory, STORE_FILE_NAME + TEMP_FILE_SUFFIX)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val isLoaded = AtomicBoolean(false)

    private val _state = MutableStateFlow(LicenseState())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    init {
        scope.launch { ensureLoaded() }
    }

    /** Suspends until the first disk load has completed. */
    suspend fun awaitLoaded() {
        ensureLoaded()
    }

    /**
     * Always `true`: donation-ware processes everything. The parameter stays
     * so batch callers keep compiling unchanged.
     */
    fun canProcess(requestedCount: Int = 1): Boolean = true

    /**
     * Counts [count] successful extractions toward the lifetime odometer and
     * persists. Nothing reads it as a balance anymore; it survives as
     * diagnostics, and so old stores keep their shape.
     */
    suspend fun recordProcessed(count: Int = 1): Unit = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext
        mutex.withLock {
            val updated = _state.value.copy(
                processedLifetimeCount = _state.value.processedLifetimeCount + count,
            )
            persist(updated)
            _state.value = updated
        }
    }

    /**
     * Accepts [key] when it normalizes to the master unlimited key or the
     * `INV-PRO-XXXX-XXXX` shape (four alphanumerics per group, case
     * insensitive, surrounding whitespace ignored).
     *
     * @return success with `true` on activation; failure with a Persian reason
     *   otherwise, leaving the stored state untouched.
     */
    suspend fun activateLicense(key: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val normalized = key.trim().uppercase()
            val valid = normalized == MASTER_KEY || KEY_PATTERN.matches(normalized)
            if (!valid) {
                return@withContext Result.failure(
                    IllegalArgumentException(INVALID_KEY_MESSAGE),
                )
            }
            mutex.withLock {
                val updated = _state.value.copy(
                    isActivated = true,
                    licenseKey = normalized,
                )
                persist(updated)
                _state.value = updated
            }
            Result.success(true)
        }

    private suspend fun ensureLoaded() {
        if (isLoaded.get()) return
        mutex.withLock {
            if (isLoaded.get()) return
            _state.value = readFromDisk()
            isLoaded.set(true)
        }
    }

    private fun readFromDisk(): LicenseState {
        if (!storageFile.isFile) return LicenseState()
        return runCatching {
            val raw = storageFile.readText()
            if (raw.isBlank()) {
                LicenseState()
            } else {
                // Donation-ware migration: a legacy trial record decodes, then
                // flips to activated — the merchant keeps their odometer, and
                // no old install ever opens restricted.
                json.decodeFromString<LicenseState>(raw).copy(isActivated = true)
            }
        }.getOrDefault(LicenseState())
    }

    private fun persist(state: LicenseState) {
        if (!storageFile.parentFile.exists() && !storageFile.parentFile.mkdirs()) {
            throw IOException("Cannot create store directory: ${storageFile.parent}")
        }
        val payload = json.encodeToString(state)
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
        const val STORE_FILE_NAME = "license_state.json"
        const val TEMP_FILE_SUFFIX = ".tmp"

        /** The hash-verified-style master key; kept as a constant by design. */
        const val MASTER_KEY = "INV-PRO-2026-UNLIMITED"

        /** `INV-PRO-XXXX-XXXX`: four alphanumerics per group. */
        val KEY_PATTERN = Regex("INV-PRO-[A-Z0-9]{4}-[A-Z0-9]{4}")

        const val INVALID_KEY_MESSAGE = "کد فعال‌سازی نامعتبر است. لطفاً بررسی و مجدداً تلاش کنید."

        /**
         * `%APPDATA%/InvoiceExtract`, shared with both file stores; the home
         * directory is the fallback for any host that does not define `APPDATA`.
         */
        fun defaultStorageDirectory(): File {
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return File(base, STORE_DIRECTORY_NAME)
        }

        const val STORE_DIRECTORY_NAME = "InvoiceExtract"
    }
}
