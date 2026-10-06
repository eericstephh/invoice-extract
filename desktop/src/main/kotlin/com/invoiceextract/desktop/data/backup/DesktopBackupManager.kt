package com.invoiceextract.desktop.data.backup

import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The backup envelope written as `manifest.json` into every archive.
 *
 * @property version The envelope layout; a restore refuses anything else, so a
 *   future layout can never be misread as today's.
 * @property appVersion The product version that wrote the backup, for support.
 * @property timestamp When the backup was taken, Jalali date plus Gregorian
 *   date, e.g. `"1404/07/10 (2026-10-02)"`.
 * @property invoiceCount Archived invoices packed into the backup.
 * @property mappingCount Warehouse-code mappings packed into the backup.
 */
@Serializable
data class BackupManifest(
    val version: Int = BACKUP_VERSION,
    val appVersion: String = APP_VERSION,
    val timestamp: String,
    val invoiceCount: Int,
    val mappingCount: Int,
)

/** The outcome of [DesktopBackupManager.createBackup]. */
data class BackupResult(
    val file: File,
    val manifest: BackupManifest,
)

/** The outcome of [DesktopBackupManager.restoreBackup]. */
data class RestoreResult(
    val manifest: BackupManifest,
    val restoredInvoices: Int,
    val restoredMappings: Int,
)

/**
 * Transactional ZIP backup and restore over the desktop file stores.
 *
 * One archive holds the whole merchant state: `manifest.json` (envelope with
 * counts and timestamp), `invoices.json` and `product_mappings.json` copied
 * byte-for-byte, so a backup is a faithful snapshot rather than a
 * re-serialization that could drift from the on-disk format.
 *
 * **Backup** stages the archive in a temp file beside the target and promotes
 * it with one atomic move, fsynced first — a crash leaves either the previous
 * file or the new one, never a half-written zip.
 *
 * **Restore** is equally guarded: the archive is validated in memory first
 * (all three entries present, manifest version supported, both payloads
 * well-formed JSON), staged to temp files, and only then promoted over the
 * live stores with fsync — after which both repositories reload, so the window
 * refreshes without a restart. A corrupt or foreign zip aborts before touching
 * a single live byte, and every failure surfaces as a [Result] with a Persian
 * message the window may show verbatim.
 *
 * @param invoiceRepository The invoice history, reloaded after a restore.
 * @param mappingRepository The mapping table, reloaded after a restore.
 * @param storageDirectory The directory holding the live store files. Must be
 *   the same directory both repositories were constructed with; defaults to
 *   `%APPDATA%/InvoiceExtract` like they do.
 * @param json The serializer for the manifest envelope. Lenient by design so a
 *   newer envelope with extra keys still restores on this binary.
 */
class DesktopBackupManager(
    private val invoiceRepository: DesktopInvoiceRepository,
    private val mappingRepository: ProductMappingRepository,
    private val storageDirectory: File = defaultStorageDirectory(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {

    /**
     * Packs the live stores into [targetZipFile].
     *
     * Missing store files pack as canonical empty documents rather than failing
     * the backup: a fresh install with nothing saved yet still deserves a
     * restorable archive.
     */
    suspend fun createBackup(targetZipFile: File): Result<BackupResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val invoices = invoiceRepository.getInvoices().first()
                val mappings = mappingRepository.getMappings().first()

                val manifest = BackupManifest(
                    timestamp = backupTimestamp(),
                    invoiceCount = invoices.size,
                    mappingCount = mappings.size,
                )

                val invoicesBytes = readStoreBytes(INVOICES_FILE_NAME, EMPTY_INVOICES_JSON)
                val mappingsBytes = readStoreBytes(MAPPINGS_FILE_NAME, EMPTY_MAPPINGS_JSON)
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

                writeZipAtomically(
                    targetZipFile,
                    mapOf(
                        MANIFEST_ENTRY to manifestBytes,
                        INVOICES_ENTRY to invoicesBytes,
                        MAPPINGS_ENTRY to mappingsBytes,
                    ),
                )

                BackupResult(file = targetZipFile, manifest = manifest)
            }
        }

    /**
     * Validates [sourceZipFile] and, only when it is a sound backup, promotes
     * its payloads over the live stores and reloads both repositories.
     */
    suspend fun restoreBackup(sourceZipFile: File): Result<RestoreResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = readAndValidateZip(sourceZipFile)

                // Staged first, promoted only after every entry validated: no
                // valid archive ever leaves a half-swapped store behind, and an
                // invalid one never reaches this point at all.
                writeStoreAtomically(INVOICES_FILE_NAME, entries.payloads.getValue(INVOICES_ENTRY))
                writeStoreAtomically(MAPPINGS_FILE_NAME, entries.payloads.getValue(MAPPINGS_ENTRY))

                invoiceRepository.reload()
                mappingRepository.reload()

                val manifest = entries.manifest
                RestoreResult(
                    manifest = manifest,
                    restoredInvoices = manifest.invoiceCount,
                    restoredMappings = manifest.mappingCount,
                )
            }
        }

    // -- Backup --------------------------------------------------------------

    /** Live store bytes, or the canonical empty document when nothing is saved yet. */
    private fun readStoreBytes(fileName: String, emptyJson: String): ByteArray {
        val file = File(storageDirectory, fileName)
        return if (file.isFile) file.readBytes() else emptyJson.toByteArray(Charsets.UTF_8)
    }

    /** Writes the three entries to a temp file, fsyncs, and promotes it atomically. */
    private fun writeZipAtomically(target: File, entries: Map<String, ByteArray>) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Cannot create backup directory: $parent")
            }
        }

        val staging = File(target.parentFile, target.name + TEMP_FILE_SUFFIX)
        try {
            staging.outputStream().use { fileStream ->
                // No `use` on the zip itself: closing it would close the file
                // stream first, and the fsync below must run while the
                // descriptor is still open. The outer `use` closes everything.
                val zip = ZipOutputStream(fileStream)
                try {
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    zip.finish()
                    zip.flush()
                    fileStream.flush()
                    fileStream.fd.sync()
                } finally {
                    zip.close()
                }
            }
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staging.delete()
        }
    }

    /** `"1404/07/10 (2026-10-02)"`: Jalali for the merchant, Gregorian for support. */
    private fun backupTimestamp(today: LocalDate = LocalDate.now()): String {
        val (jy, jm, jd) = gregorianToJalali(today.year, today.monthValue, today.dayOfMonth)
        val jalali = "%04d/%02d/%02d".format(jy, jm, jd)
        return "$jalali (${today})"
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDays = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
        var y = gy - 1600
        var m = gm - 1
        var d = gd - 1
        var gDayNo = 365 * y + (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        for (i in 0 until m) gDayNo += gDays[i]
        if (m > 1 && ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0))) gDayNo++
        gDayNo += d
        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053
        jDayNo %= 12053
        y = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461
        if (jDayNo >= 366) {
            y += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }
        m = 0
        while (m < 11 && jDayNo >= jDays[m]) {
            jDayNo -= jDays[m]
            m++
        }
        return Triple(y, m + 1, jDayNo + 1)
    }

    // -- Restore -------------------------------------------------------------

    /** Validated payloads plus their envelope, or a thrown Persian failure. */
    private fun readAndValidateZip(source: File): ValidatedBackup {
        if (!source.isFile) throw IllegalStateException(MSG_NOT_A_BACKUP)

        val entries = try {
            ZipFile(source).use { zip ->
                Collections.list(zip.entries())
                    .filter { !it.isDirectory }
                    .associate { entry ->
                        if (entry.size > MAX_ENTRY_BYTES) throw IllegalStateException(MSG_BACKUP_TOO_LARGE)
                        entry.name to zip.getInputStream(entry).readBytes()
                    }
            }
        } catch (cause: IllegalStateException) {
            throw cause
        } catch (cause: Exception) {
            throw IllegalStateException(MSG_CORRUPT_BACKUP, cause)
        }

        // Only the three known entries travel; anything else in the archive is
        // ignored rather than trusted — this also neutralizes zip-slip paths,
        // which can never match a known entry name.
        val manifestBytes = entries[MANIFEST_ENTRY] ?: throw IllegalStateException(MSG_CORRUPT_BACKUP)
        val invoicesBytes = entries[INVOICES_ENTRY] ?: throw IllegalStateException(MSG_CORRUPT_BACKUP)
        val mappingsBytes = entries[MAPPINGS_ENTRY] ?: throw IllegalStateException(MSG_CORRUPT_BACKUP)

        val manifest = try {
            json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
        } catch (cause: Exception) {
            throw IllegalStateException(MSG_CORRUPT_BACKUP, cause)
        }
        if (manifest.version != BACKUP_VERSION) throw IllegalStateException(MSG_UNSUPPORTED_BACKUP)

        // Well-formedness now, not silent emptiness later: a payload that is not
        // JSON at all must abort the restore instead of degrading the live
        // store into nothing.
        try {
            Json.parseToJsonElement(invoicesBytes.toString(Charsets.UTF_8))
            Json.parseToJsonElement(mappingsBytes.toString(Charsets.UTF_8))
        } catch (cause: Exception) {
            throw IllegalStateException(MSG_CORRUPT_BACKUP, cause)
        }

        return ValidatedBackup(
            manifest = manifest,
            payloads = mapOf(
                INVOICES_ENTRY to invoicesBytes,
                MAPPINGS_ENTRY to mappingsBytes,
            ),
        )
    }

    /** Promotes one validated payload over its live store file, fsynced first. */
    private fun writeStoreAtomically(fileName: String, bytes: ByteArray) {
        if (!storageDirectory.exists() && !storageDirectory.mkdirs()) {
            throw IOException("Cannot create store directory: $storageDirectory")
        }
        val target = File(storageDirectory, fileName)
        val staging = File(storageDirectory, fileName + TEMP_FILE_SUFFIX)
        try {
            staging.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staging.delete()
        }
    }

    private data class ValidatedBackup(
        val manifest: BackupManifest,
        val payloads: Map<String, ByteArray>,
    )

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val INVOICES_ENTRY = "invoices.json"
        const val MAPPINGS_ENTRY = "product_mappings.json"

        const val INVOICES_FILE_NAME = "invoices.json"
        const val MAPPINGS_FILE_NAME = "product_mappings.json"

        const val TEMP_FILE_SUFFIX = ".tmp"

        const val EMPTY_INVOICES_JSON = """{"version":1,"invoices":[]}"""
        const val EMPTY_MAPPINGS_JSON = """{"mappings":[]}"""

        /** Rejects absurd entries before they are read fully into memory. */
        const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L

        const val MSG_NOT_A_BACKUP = "فایل انتخاب‌شده یک نسخه پشتیبان معتبر نیست."
        const val MSG_CORRUPT_BACKUP = "فایل پشتیبان خراب است؛ هیچ تغییری اعمال نشد."
        const val MSG_UNSUPPORTED_BACKUP = "نسخه این فایل پشتیبان پشتیبانی نمی‌شود."
        const val MSG_BACKUP_TOO_LARGE = "حجم فایل پشتیبان بیش از حد مجاز است."

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

/** Current backup envelope layout. */
const val BACKUP_VERSION = 1

/** Product version stamped into the envelope. */
const val APP_VERSION = "1.0.0"
