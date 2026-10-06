package com.invoiceextract.desktop.data.automation

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.document.DesktopDocumentProcessor
import com.invoiceextract.desktop.data.engine.DesktopOllamaLifecycleManager
import com.invoiceextract.desktop.data.licensing.DesktopLicenseManager
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopFolderWatcherService].
 *
 * The live NIO loop is driven only through its configuration surface — no
 * daemon, no scanner — so lifecycle, extension filtering and the
 * write-stabilization guard all assert without processing a single file.
 */
class DesktopFolderWatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `fresh watcher is disabled without a folder`() {
        val service = serviceIn("stores")

        assertNull(service.config.value.folderPath)
        assertFalse(service.config.value.isEnabled)
        assertNull(service.latestEvent.value)

        service.shutdown()
    }

    @Test
    fun `lifecycle starts stops and retargets`() {
        val service = serviceIn("stores")
        val first = tempFolder.root.resolve("first").apply { mkdirs() }
        val second = tempFolder.root.resolve("second").apply { mkdirs() }

        service.setFolder(first.absolutePath)
        assertEquals(first.absolutePath, service.config.value.folderPath)
        assertFalse(service.config.value.isEnabled)

        service.setEnabled(true)
        assertTrue(service.config.value.isEnabled)

        service.setEnabled(false)
        assertFalse(service.config.value.isEnabled)

        service.setFolder(second.absolutePath)
        assertEquals(second.absolutePath, service.config.value.folderPath)

        service.shutdown()
    }

    @Test
    fun `enabling without a folder is refused`() {
        val service = serviceIn("stores")

        service.setEnabled(true)

        assertFalse(service.config.value.isEnabled)
        assertNull(service.config.value.folderPath)

        service.shutdown()
    }

    @Test
    fun `non directory paths never become the folder`() {
        val service = serviceIn("stores")
        val plainFile = tempFolder.newFile("not-a-dir.txt")

        service.setFolder(plainFile.absolutePath)

        assertNull(service.config.value.folderPath)
        assertFalse(service.config.value.isEnabled)

        service.shutdown()
    }

    @Test
    fun `only invoice extensions are watched`() {
        val service = serviceIn("stores")

        assertTrue(service.isWatchedFile("scan.pdf"))
        assertTrue(service.isWatchedFile("SCAN.PDF"))
        assertTrue(service.isWatchedFile("photo.png"))
        assertTrue(service.isWatchedFile("photo.jpg"))
        assertTrue(service.isWatchedFile("photo.jpeg"))
        assertFalse(service.isWatchedFile("notes.txt"))
        assertFalse(service.isWatchedFile("ledger.xls"))
        assertFalse(service.isWatchedFile("noextension"))

        service.shutdown()
    }

    @Test
    fun `stable file passes stabilization quickly`() = runBlocking {
        val service = serviceIn("stores")
        val file = tempFolder.newFile("scan.pdf")
        file.writeText("%PDF-quiet", StandardCharsets.UTF_8)

        assertTrue(service.waitForStable(file, pollMs = 10L, requiredStableRounds = 2, maxAttempts = 50))

        service.shutdown()
    }

    @Test
    fun `missing file never stabilizes`() = runBlocking {
        val service = serviceIn("stores")
        val missing = tempFolder.root.resolve("ghost.pdf")

        assertFalse(service.waitForStable(missing, pollMs = 10L, requiredStableRounds = 2, maxAttempts = 5))

        service.shutdown()
    }

    @Test
    fun `a file that stops growing stabilizes`() = runBlocking {
        val service = serviceIn("stores")
        val file = tempFolder.newFile("scan.pdf")
        // A slow scanner in miniature: four appends 30ms apart, then silence.
        // The guard may fire between two appends or after the last one — both
        // are "the file stopped changing", so either way it must return true.
        val writer = Thread {
            repeat(4) {
                Thread.sleep(30L)
                file.appendText("0123456789")
            }
        }
        writer.start()

        assertTrue(service.waitForStable(file, pollMs = 10L, requiredStableRounds = 3, maxAttempts = 200))

        writer.join(5_000L)
        service.shutdown()
    }

    @Test
    fun `config path mirrors the folder path`() {
        assertEquals("C:/scan", FolderWatcherConfig(folderPath = "C:/scan").path)
        assertNull(FolderWatcherConfig().path)
    }

    @Test
    fun `dismissing clears the toast`() {
        val service = serviceIn("stores")

        service.dismissEvent()

        assertNull(service.latestEvent.value)

        service.shutdown()
    }

    private fun serviceIn(name: String): DesktopFolderWatcherService {
        val dir = tempFolder.root.resolve(name)
        val normalizer = DesktopPersianNormalizer()
        return DesktopFolderWatcherService(
            processInvoiceUseCase = DesktopProcessInvoiceUseCase(
                documentProcessor = DesktopDocumentProcessor(
                    normalizer = normalizer,
                    imageOcrEngine = DesktopImageOcrEngine(normalizer = normalizer),
                ),
                ollamaExtractor = LocalOllamaAiExtractor(client = OkHttpClient(), json = json),
                mappingRepository = ProductMappingRepository(
                    normalizer = normalizer,
                    json = json,
                    storageDirectory = dir,
                ),
                invoiceValidator = InvoiceValidator(),
            ),
            invoiceRepository = DesktopInvoiceRepository(json = json, storageDirectory = dir),
            lifecycleManager = DesktopOllamaLifecycleManager(
                extractor = LocalOllamaAiExtractor(client = OkHttpClient(), json = json),
            ),
            licenseManager = DesktopLicenseManager(json = json, storageDirectory = dir),
        )
    }
}
