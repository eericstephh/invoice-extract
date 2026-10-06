package com.invoiceextract.desktop.data.backup

import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Hermetic JVM tests for [DesktopBackupManager].
 *
 * Every store points at a throwaway folder — never `%APPDATA%` — so backup
 * and restore run the production file recipe (temp staging, atomic moves,
 * fsync) against scratch disks. Cases cover the happy paths (valid zip shape,
 * restore refreshing live repository flows) and the guarded ones (garbage
 * bytes, missing manifest and malformed payloads all fail with the live
 * stores byte-identical).
 */
class DesktopBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `backup packs manifest invoices and mappings`() = runBlocking {
        val stores = storesIn("stores")
        stores.invoices.saveInvoice(sampleInvoice("inv-1")).getOrThrow()
        stores.invoices.saveInvoice(sampleInvoice("inv-2")).getOrThrow()
        stores.mappings.saveMapping("کابل شارژ", "فروشگاه دوم", "WH-002")
        val target = tempFolder.root.resolve("backup.zip")

        val result = stores.manager.createBackup(target).getOrThrow()

        assertTrue(target.isFile)
        assertEquals(2, result.manifest.invoiceCount)
        assertEquals(1, result.manifest.mappingCount)
        assertEquals(1, result.manifest.version)
        assertTrue(result.manifest.timestamp.isNotBlank())

        ZipFile(target).use { zip ->
            assertEquals(
                setOf("manifest.json", "invoices.json", "product_mappings.json"),
                java.util.Collections.list(zip.entries()).map { it.name }.toSet(),
            )
            val manifest = json.decodeFromString<BackupManifest>(
                zip.getInputStream(zip.getEntry("manifest.json"))
                    .readBytes().toString(StandardCharsets.UTF_8),
            )
            assertEquals(2, manifest.invoiceCount)
            assertEquals(1, manifest.mappingCount)
            val packed = zip.getInputStream(zip.getEntry("invoices.json"))
                .readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(packed.contains("inv-1"))
            assertTrue(packed.contains("inv-2"))
        }
    }

    @Test
    fun `restore unpacks and refreshes both repositories`() = runBlocking {
        val source = storesIn("source")
        source.invoices.saveInvoice(sampleInvoice("inv-1")).getOrThrow()
        source.invoices.saveInvoice(sampleInvoice("inv-2")).getOrThrow()
        source.mappings.saveMapping("کابل شارژ", "فروشگاه دوم", "WH-002")
        val archive = tempFolder.root.resolve("backup.zip")
        source.manager.createBackup(archive).getOrThrow()

        // A fresh, empty home for both stores — the restore must fill it and
        // the live flows must observe the refill without new instances.
        val home = storesIn("home")
        assertTrue(home.invoices.getInvoices().first().isEmpty())

        val result = home.manager.restoreBackup(archive).getOrThrow()

        assertEquals(2, result.restoredInvoices)
        assertEquals(1, result.restoredMappings)
        assertEquals(
            listOf("inv-1", "inv-2"),
            home.invoices.getInvoices().first().map { it.id }.sorted(),
        )
        assertEquals(1, home.mappings.getMappings().first().size)
        assertEquals(
            "WH-002",
            home.mappings.getMappings().first().single().internalProductCode,
        )
    }

    @Test
    fun `garbage bytes fail without touching live files`() = runBlocking {
        val home = storesIn("home")
        home.invoices.saveInvoice(sampleInvoice("inv-1")).getOrThrow()
        val before = snapshot(home.dir)
        val garbage = tempFolder.newFile("garbage.zip")
        garbage.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02))

        val result = home.manager.restoreBackup(garbage)

        assertTrue(result.isFailure)
        assertStoresEqual(before, snapshot(home.dir))
        assertEquals(1, home.invoices.getInvoices().first().size)
    }

    @Test
    fun `zip without manifest fails without touching live files`() = runBlocking {
        val home = storesIn("home")
        home.invoices.saveInvoice(sampleInvoice("inv-1")).getOrThrow()
        val before = snapshot(home.dir)
        val archive = tempFolder.root.resolve("nomanifest.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("invoices.json"))
            zip.write("{}".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val result = home.manager.restoreBackup(archive)

        assertTrue(result.isFailure)
        assertStoresEqual(before, snapshot(home.dir))
        assertEquals(1, home.invoices.getInvoices().first().size)
    }

    @Test
    fun `malformed payload fails without touching live files`() = runBlocking {
        val home = storesIn("home")
        home.invoices.saveInvoice(sampleInvoice("inv-1")).getOrThrow()
        val before = snapshot(home.dir)
        val archive = tempFolder.root.resolve("badpayload.zip")
        val manifest = json.encodeToString(
            BackupManifest.serializer(),
            BackupManifest(timestamp = "1404/07/10 (2026-10-02)", invoiceCount = 1, mappingCount = 0),
        )
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("invoices.json"))
            zip.write("not json at all{{{".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("product_mappings.json"))
            zip.write("{\"mappings\":[]}".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val result = home.manager.restoreBackup(archive)

        assertTrue(result.isFailure)
        assertStoresEqual(before, snapshot(home.dir))
        assertEquals(1, home.invoices.getInvoices().first().size)
    }

    @Test
    fun `backup of empty stores still restores`() = runBlocking {
        val stores = storesIn("stores")
        val manager = stores.manager
        val target = tempFolder.root.resolve("empty.zip")

        val backup = manager.createBackup(target).getOrThrow()
        assertEquals(0, backup.manifest.invoiceCount)
        assertEquals(0, backup.manifest.mappingCount)

        val home = storesIn("home")
        val restored = home.manager.restoreBackup(target).getOrThrow()

        assertEquals(0, restored.restoredInvoices)
        assertEquals(0, restored.restoredMappings)
        assertTrue(home.invoices.getInvoices().first().isEmpty())
    }

    private fun storesIn(name: String): Stores {
        val dir = tempFolder.root.resolve(name)
        val invoices = DesktopInvoiceRepository(json = json, storageDirectory = dir)
        val mappings = ProductMappingRepository(
            normalizer = DesktopPersianNormalizer(),
            json = json,
            storageDirectory = dir,
        )
        return Stores(
            dir = dir,
            invoices = invoices,
            mappings = mappings,
            manager = DesktopBackupManager(
                invoiceRepository = invoices,
                mappingRepository = mappings,
                storageDirectory = dir,
                json = json,
            ),
        )
    }

    /** Every live store file by name, so corruption tests prove byte-identity. */
    private fun snapshot(dir: File): Map<String, ByteArray> =
        dir.listFiles()
            ?.filter { it.isFile }
            ?.associate { it.name to it.readBytes() }
            .orEmpty()

    /** Byte-wise store equality: [ByteArray] needs content comparison, never `equals`. */
    private fun assertStoresEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) ->
            assertArrayEquals(bytes, actual.getValue(name))
        }
    }

    private fun sampleInvoice(id: String): Invoice = Invoice(
        id = id,
        invoiceNumber = "1001",
        date = "1403/05/20",
        sellerName = "شرکت نمونه",
        grandTotal = 1_000_000.0,
        currency = CurrencyType.TOMAN,
    )

    private data class Stores(
        val dir: File,
        val invoices: DesktopInvoiceRepository,
        val mappings: ProductMappingRepository,
        val manager: DesktopBackupManager,
    )
}
