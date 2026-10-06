package com.invoiceextract.desktop.data.mapping

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hermetic JVM tests for [ProductMappingRepository].
 *
 * Everything runs against a throwaway folder and never touches the real `%APPDATA%`:
 * the repository's storage-directory parameter is the seam that makes the production
 * path and the test path the same code, so what is verified here is what ships.
 *
 * No daemon, no store fixtures — the only I/O is the mapping file itself, exercised
 * through the public contract (save, lookup, delete, restart) rather than through
 * file assertions alone.
 */
class ProductMappingRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        // Mirrors the instance the DI module hands the repository, so the suite
        // exercises the exact file format production writes.
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `fresh store starts empty instead of throwing`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))

        assertEquals(emptyList<ProductMapping>(), repository.getMappings().first())
        assertNull(repository.findMapping("قلم", "فروشنده"))
    }

    @Test
    fun `save is readable after a repository restart`() = runBlocking {
        val dir = tempFolder.newFolder("store")
        repository(dir).saveMapping("هدفون بی‌سیم", "شرکت نمونه", "WH-001", "Headphones")

        // A brand-new instance on the same folder proves the bytes actually reached the
        // disk: its memory is empty until its first read loads the file.
        val reloaded = repository(dir).getMappings().first()

        assertEquals(1, reloaded.size)
        assertEquals("هدفون بی‌سیم", reloaded.single().rawItemName)
        assertEquals("شرکت نمونه", reloaded.single().vendorName)
        assertEquals("WH-001", reloaded.single().internalProductCode)
        assertEquals("Headphones", reloaded.single().internalProductName)
    }

    @Test
    fun `save rewrites the same key instead of stacking rows`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))

        // Typing a code character by character must collapse into one row.
        repository.saveMapping("قلم", "الف", "A")
        repository.saveMapping("قلم", "الف", "AB")
        repository.saveMapping("قلم", "الف", "AB-1")

        val rows = repository.getMappings().first()
        assertEquals(1, rows.size)
        assertEquals("AB-1", rows.single().internalProductCode)
    }

    @Test
    fun `vendor-specific mapping wins over the global one`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))
        repository.saveMapping("قلم", null, "GLOBAL-1")
        repository.saveMapping("قلم", "الف", "VENDOR-A")

        assertEquals("VENDOR-A", repository.findMapping("قلم", "الف")?.internalProductCode)
    }

    @Test
    fun `global mapping answers an unknown vendor`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))
        repository.saveMapping("قلم", null, "GLOBAL-1")
        repository.saveMapping("قلم", "الف", "VENDOR-A")

        assertEquals("GLOBAL-1", repository.findMapping("قلم", "ب")?.internalProductCode)
        assertEquals("GLOBAL-1", repository.findMapping("قلم", null)?.internalProductCode)
    }

    @Test
    fun `lookup normalizes both sides before comparing`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))
        // Arabic kaf on the invoice, Persian kaf at mapping time: the same good.
        repository.saveMapping("كالا", "شركت", "NORM-1")

        val found = repository.findMapping("کالا", "شرکت")

        assertEquals("NORM-1", found?.internalProductCode)
    }

    @Test
    fun `unknown item resolves to null`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))
        repository.saveMapping("قلم", "الف", "VENDOR-A")

        assertNull(repository.findMapping("دفتر", "الف"))
        assertNull(repository.findMapping("", "الف"))
        assertNull(repository.findMapping("   ", null))
    }

    @Test
    fun `delete forgets the row and unknown ids are no-ops`() = runBlocking {
        val repository = repository(tempFolder.newFolder("store"))
        repository.saveMapping("قلم", "الف", "VENDOR-A")
        val id = repository.getMappings().first().single().id

        repository.deleteMapping(id)

        assertEquals(emptyList<ProductMapping>(), repository.getMappings().first())
        assertNull(repository.findMapping("قلم", "الف"))
        // Unknown ids must not throw — double-taps and stale rows stay silent.
        repository.deleteMapping(id)
        repository.deleteMapping("does-not-exist")
    }

    @Test
    fun `blank names and codes are rejected silently`() = runBlocking {
        val dir = tempFolder.newFolder("store")
        val repository = repository(dir)

        repository.saveMapping("", "الف", "X")
        repository.saveMapping("قلم", "الف", "  ")
        repository.saveMapping("   ", null, "Y")

        assertEquals(emptyList<ProductMapping>(), repository.getMappings().first())
        // Nothing valid ever arrived, so no store file should exist at all.
        assertTrue(File(dir, "product_mappings.json").let { !it.exists() })
    }

    private fun repository(dir: File): ProductMappingRepository =
        ProductMappingRepository(DesktopPersianNormalizer(), json, dir)
}
