package com.invoiceextract.desktop.data.preview

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Hermetic JVM tests for [DesktopDocumentPreviewManager].
 *
 * Everything runs against files this suite writes itself into a throwaway folder —
 * a rendered PNG, a two-page PDFBox document, a missing path and a garbage PDF — so
 * no fixture binaries are checked in and no daemon is involved. Navigation,
 * clamping, teardown and every failure sentence are asserted through the public
 * state flows, which is exactly what the preview pane reads.
 */
class DesktopDocumentPreviewManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `image loads as its single page`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()
        val image = writeImage("photo.png")

        manager.loadDocument(image)

        assertEquals(1, manager.pageCount.value)
        assertEquals(0, manager.currentPageIndex.value)
        assertNotNull(manager.currentBitmap.value)
        assertNull(manager.errorMessage.value)
    }

    @Test
    fun `multipage pdf navigates with clamping at both ends`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()
        val pdf = writePdf(pages = 2)

        manager.loadDocument(pdf)

        assertEquals(2, manager.pageCount.value)
        assertEquals(0, manager.currentPageIndex.value)

        manager.nextPage()
        assertEquals(1, manager.currentPageIndex.value)
        assertNotNull(manager.currentBitmap.value)

        // Past the last page: a quiet no-op, never an exception.
        manager.nextPage()
        assertEquals(1, manager.currentPageIndex.value)

        manager.prevPage()
        assertEquals(0, manager.currentPageIndex.value)

        // Before the first page: same clamp.
        manager.prevPage()
        assertEquals(0, manager.currentPageIndex.value)
    }

    @Test
    fun `missing file reports the persian fallback`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()

        manager.loadDocument(File(tempFolder.root, "deleted.pdf"))

        assertEquals(0, manager.pageCount.value)
        assertNull(manager.currentBitmap.value)
        assertEquals(
            "فایل مبدأ سند در دسترس نیست یا حذف شده است.",
            manager.errorMessage.value,
        )
    }

    @Test
    fun `undecodable bytes report the fallback instead of throwing`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()
        val garbage = tempFolder.newFile("garbage.pdf")
        garbage.writeBytes("%PDF-1.4\nthis is not a real pdf body".toByteArray())

        manager.loadDocument(garbage)

        assertEquals(0, manager.pageCount.value)
        assertNull(manager.currentBitmap.value)
        assertTrue(!manager.errorMessage.value.isNullOrBlank())
    }

    @Test
    fun `close releases the document and resets the state`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()
        manager.loadDocument(writePdf(pages = 2))
        assertEquals(2, manager.pageCount.value)

        manager.close()

        assertEquals(0, manager.pageCount.value)
        assertEquals(0, manager.currentPageIndex.value)
        assertNull(manager.currentBitmap.value)
        assertNull(manager.errorMessage.value)
        // Repeating the close must stay silent.
        manager.close()
    }

    @Test
    fun `loading a second document closes the first`() = runBlocking {
        val manager = DesktopDocumentPreviewManager()
        manager.loadDocument(writePdf(pages = 2))
        val photo = writeImage("second.png")

        manager.loadDocument(photo)

        assertEquals(1, manager.pageCount.value)
        assertEquals(0, manager.currentPageIndex.value)
        assertNotNull(manager.currentBitmap.value)
        assertNull(manager.errorMessage.value)
    }

    /** A black-on-white raster on disk, the way a clean scan looks after preprocessing. */
    private fun writeImage(name: String): File {
        val file = tempFolder.newFile(name)
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 200, 100)
            graphics.color = Color.BLACK
            graphics.fillRect(20, 20, 160, 60)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", file)
        return file
    }

    /** A raster-free PDF with [pages] blank pages, so the text layer stays empty. */
    private fun writePdf(pages: Int): File {
        val file = tempFolder.newFile("scan-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            repeat(pages) { document.addPage(PDPage()) }
            document.save(file)
        }
        return file
    }
}
