package com.invoiceextract.desktop.data.storage

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Currency persistence for [DesktopInvoiceRepository]: a foreign invoice keeps
 * its currency, day rate and original amount across a restart, while a store
 * written before multi-currency support loads with clean `null` FX fields.
 */
class DesktopInvoiceRepositoryCurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `foreign currency and rate survive a repository restart`() = runBlocking {
        val saved = Invoice(
            id = "inv-usd",
            sellerName = "Google",
            grandTotal = 100.0,
            currency = CurrencyType.USD,
            exchangeRate = 95_000.0,
            originalForeignAmount = 100.0,
        )
        repository().saveInvoice(saved).getOrThrow()

        val reloaded = repository().getInvoiceById("inv-usd")

        assertEquals(saved, reloaded)
        assertEquals(CurrencyType.USD, reloaded?.currency)
        assertEquals(95_000.0, reloaded?.exchangeRate)
        assertEquals(100.0, reloaded?.originalForeignAmount)
        assertEquals(9_500_000L, reloaded?.effectiveTomanTotal)
    }

    @Test
    fun `legacy store without fx fields decodes with nulls`() = runBlocking {
        File(tempFolder.root, "invoices.json").writeText(
            LEGACY_STORE_JSON,
            StandardCharsets.UTF_8,
        )

        val loaded = repository().getInvoices().first()

        assertEquals(1, loaded.size)
        assertEquals(CurrencyType.TOMAN, loaded.single().currency)
        assertNull(loaded.single().exchangeRate)
        assertNull(loaded.single().originalForeignAmount)
        assertEquals(3_270_000L, loaded.single().effectiveTomanTotal)
    }

    private fun repository(): DesktopInvoiceRepository =
        DesktopInvoiceRepository(json = json, storageDirectory = tempFolder.root)

    private companion object {
        const val LEGACY_STORE_JSON = """
            {
                "version": 1,
                "invoices": [
                    {
                        "id": "inv-legacy",
                        "invoiceNumber": "10234567890",
                        "date": "1403/05/20",
                        "sellerName": "شرکت نمونه",
                        "items": [],
                        "subtotal": 3000000.0,
                        "totalTax": 270000.0,
                        "totalDiscount": 0.0,
                        "grandTotal": 3270000.0,
                        "currency": "TOMAN",
                        "rawOcrText": "",
                        "validation": {"status": "VALID", "issues": []},
                        "createdAtEpochMs": 1750000000000
                    }
                ]
            }
        """
    }
}
