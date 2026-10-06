package com.invoiceextract.desktop.data.storage

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.validation.IssueSeverity
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hermetic JVM tests for [DesktopInvoiceRepository].
 *
 * Everything runs against a throwaway folder and never touches the real `%APPDATA%`: the
 * repository's storage-directory parameter is the seam that makes the production path and
 * the test path the same code, so what is verified here is what ships.
 *
 * No mock web server, no real daemon — the only I/O is the store itself, exercised through
 * the public contract (save, restart the instance, reload) rather than through file
 * assertions alone.
 */
class DesktopInvoiceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        // Mirrors the instance the DI module hands the repository, so the suite exercises
        // the exact file format production writes — including its pretty-printing.
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `fresh store starts empty instead of throwing`() = runBlocking {
        val repository = repository()

        assertEquals(emptyList<Invoice>(), repository.getInvoices().first())
        assertNull(repository.getInvoiceById("nope"))
    }

    @Test
    fun `save is readable after a repository restart`() = runBlocking {
        val saved = sampleInvoice(id = "inv-1")
        repository().saveInvoice(saved).getOrThrow()

        // A brand-new instance on the same folder proves the bytes actually reached the
        // disk: its memory is empty until its first read loads the file.
        val reloaded = repository()

        assertEquals(saved, reloaded.getInvoiceById("inv-1"))
    }

    @Test
    fun `same id twice rewrites instead of duplicating`() = runBlocking {
        val repository = repository()
        repository.saveInvoice(sampleInvoice(id = "inv-1", grandTotal = 100.0)).getOrThrow()
        repository.saveInvoice(sampleInvoice(id = "inv-1", grandTotal = 250.0)).getOrThrow()

        val all = repository.getInvoices().first()

        assertEquals(1, all.size)
        assertEquals(250.0, all.single().grandTotal, DELTA)
    }

    @Test
    fun `history comes out newest first`() = runBlocking {
        val repository = repository()
        repository.saveInvoice(sampleInvoice(id = "old", createdAtEpochMs = 1_000L)).getOrThrow()
        repository.saveInvoice(sampleInvoice(id = "new", createdAtEpochMs = 9_000L)).getOrThrow()

        assertEquals(listOf("new", "old"), repository.getInvoices().first().map { it.id })
    }

    @Test
    fun `the flow emits saves without any reload`() = runBlocking {
        val repository = repository()
        assertTrue(repository.getInvoices().first().isEmpty())

        // Same instance, no restart, no re-read: the emission follows the write because
        // both go through the one in-memory list.
        repository.saveInvoice(sampleInvoice(id = "inv-1")).getOrThrow()

        assertEquals(listOf("inv-1"), repository.getInvoices().first().map { it.id })
    }

    @Test
    fun `delete removes the record`() = runBlocking {
        val repository = repository()
        repository.saveInvoice(sampleInvoice(id = "gone")).getOrThrow()

        repository.deleteInvoice("gone").getOrThrow()

        assertNull(repository.getInvoiceById("gone"))
        assertTrue(repository.getInvoices().first().isEmpty())
    }

    @Test
    fun `delete of an unknown id succeeds and changes nothing`() = runBlocking {
        val repository = repository()
        val kept = sampleInvoice(id = "kept")
        repository.saveInvoice(kept).getOrThrow()

        repository.deleteInvoice("nope").getOrThrow()

        assertEquals(listOf(kept), repository.getInvoices().first())
    }

    @Test
    fun `a deleted record stays deleted after a repository restart`() = runBlocking {
        val repository = repository()
        repository.saveInvoice(sampleInvoice(id = "gone")).getOrThrow()
        repository.deleteInvoice("gone").getOrThrow()

        assertNull(repository().getInvoiceById("gone"))
    }

    @Test
    fun `a save leaves the store file and no temp file behind`() = runBlocking {
        repository().saveInvoice(sampleInvoice(id = "inv-1")).getOrThrow()

        assertTrue("the store must exist after a save", storeFile().isFile)
        assertFalse("an atomic rename must not leave the temp file behind", tempFile().exists())
    }

    @Test
    fun `the store file is human-readable pretty-printed json`() = runBlocking {
        repository().saveInvoice(sampleInvoice(id = "inv-1")).getOrThrow()

        val raw = storeFile().readText()
        assertTrue("the store is meant to be user-inspectable", raw.contains("\n"))
        assertTrue(
            "a pretty-printed key sits on its own indented line",
            raw.lines().any { it.startsWith(" ") && it.contains("\"id\"") },
        )
    }

    @Test
    fun `a corrupt store degrades to empty history instead of throwing`() = runBlocking {
        storeFile().writeText("{ this is not a store")

        val repository = repository()

        assertEquals(emptyList<Invoice>(), repository.getInvoices().first())
        assertNull(repository.getInvoiceById("inv-1"))
    }

    @Test
    fun `source path and product codes round-trip losslessly`() = runBlocking {
        val saved = sampleInvoice(id = "inv-1").copy(
            sourceFilePath = tempFolder.root.resolve("scan.pdf").absolutePath,
            items = listOf(
                sampleInvoice(id = "inv-1").items.single().copy(productCode = "WH-001"),
            ),
        )
        repository().saveInvoice(saved).getOrThrow()

        val reloaded = repository().getInvoiceById("inv-1")
        assertEquals(saved.sourceFilePath, reloaded?.sourceFilePath)
        assertEquals("WH-001", reloaded?.items?.single()?.productCode)
    }

    @Test
    fun `client and project tags round-trip losslessly`() = runBlocking {
        val tagged = sampleInvoice(id = "inv-1").copy(
            clientName = "کارفرمای نمونه",
            projectName = "کمپین بهار",
        )
        repository().saveInvoice(tagged).getOrThrow()

        val reloaded = repository().getInvoiceById("inv-1")
        assertEquals("کارفرمای نمونه", reloaded?.clientName)
        assertEquals("کمپین بهار", reloaded?.projectName)
    }

    @Test
    fun `a full invoice round-trips losslessly`() = runBlocking {
        val saved = sampleInvoice(id = "inv-1").copy(
            validationStatus = ValidationStatus.Warning(
                reasons = listOf(
                    ValidationIssue(
                        field = "grandTotal",
                        description = "جمع با جزئیات مغایرت دارد",
                        severity = IssueSeverity.WARNING,
                    ),
                ),
            ),
        )
        repository().saveInvoice(saved).getOrThrow()

        assertEquals(saved, repository().getInvoiceById("inv-1"))
    }

    @Test
    fun `payment status and due date round-trip losslessly`() = runBlocking {
        val saved = sampleInvoice(id = "inv-1").copy(
            paymentStatus = PaymentStatus.OVERDUE,
            dueDate = "1403/06/31",
        )
        repository().saveInvoice(saved).getOrThrow()

        val reloaded = repository().getInvoiceById("inv-1")
        assertEquals(PaymentStatus.OVERDUE, reloaded?.paymentStatus)
        assertEquals("1403/06/31", reloaded?.dueDate)
    }

    @Test
    fun `marking paid survives a repository restart`() = runBlocking {
        repository().saveInvoice(sampleInvoice(id = "inv-1")).getOrThrow()
        repository().saveInvoice(
            sampleInvoice(id = "inv-1").copy(paymentStatus = PaymentStatus.PAID),
        ).getOrThrow()

        val reloaded = repository().getInvoiceById("inv-1")
        assertEquals(PaymentStatus.PAID, reloaded?.paymentStatus)
    }

    @Test
    fun `a legacy archive without payment fields decodes as pending`() = runBlocking {
        storeFile().writeText(
            """
            {
                "version": 1,
                "invoices": [
                    {
                        "id": "legacy-1",
                        "invoiceNumber": "77",
                        "grandTotal": 500000.0,
                        "currency": "TOMAN"
                    }
                ]
            }
            """.trimIndent(),
        )

        val reloaded = repository().getInvoiceById("legacy-1")
        assertEquals(PaymentStatus.PENDING, reloaded?.paymentStatus)
        assertNull(reloaded?.dueDate)
    }

    private fun repository(): DesktopInvoiceRepository =
        DesktopInvoiceRepository(json = json, storageDirectory = tempFolder.root)

    private fun storeFile(): File = File(tempFolder.root, "invoices.json")

    private fun tempFile(): File = File(tempFolder.root, "invoices.json.tmp")

    private fun sampleInvoice(
        id: String,
        grandTotal: Double = 3_270_000.0,
        createdAtEpochMs: Long = 1_750_000_000_000L,
    ): Invoice = Invoice(
        id = id,
        invoiceNumber = "10234567890",
        date = "1403/05/20",
        sellerName = "شرکت نمونه",
        sellerTaxId = "14008238774",
        buyerName = "علی محمدی",
        buyerTaxId = "14007654321",
        items = listOf(
            InvoiceItem(
                id = "item-1",
                name = "هدفون بی‌سیم",
                quantity = 2.0,
                unitPrice = 1_500_000.0,
                discount = 0.0,
                tax = 270_000.0,
                totalPrice = 3_270_000.0,
                confidence = 0.92f,
            ),
        ),
        subtotal = 3_000_000.0,
        totalTax = 270_000.0,
        totalDiscount = 0.0,
        grandTotal = grandTotal,
        currency = CurrencyType.TOMAN,
        rawOcrText = "فاکتور فروش",
        createdAtEpochMs = createdAtEpochMs,
    )

    private companion object {
        const val DELTA = 0.0
    }
}
